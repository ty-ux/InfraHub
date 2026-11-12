package com.infrahub.release.service;

import com.infrahub.release.dto.GovernanceOpsSlaQuery;
import com.infrahub.release.dto.GovernanceSlaResponse;
import com.infrahub.release.entity.ResourceGovernanceActionExecution;
import com.infrahub.release.entity.ResourceGovernanceActionPlan;
import com.infrahub.release.entity.ResourceGovernanceSuggestion;
import com.infrahub.release.enums.ActionExecutionStatus;
import com.infrahub.release.enums.ActionPlanStatus;
import com.infrahub.release.enums.RiskLevel;
import com.infrahub.release.enums.SuggestionStatus;
import com.infrahub.release.mapper.ResourceGovernanceActionExecutionMapper;
import com.infrahub.release.mapper.ResourceGovernanceActionPlanMapper;
import com.infrahub.release.mapper.ResourceGovernanceSuggestionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * SLA 闭环效率统计服务.
 * Contract: V2/L section 4.3, 6.
 *
 * SLA 计算口径（Contract section 6.2）：
 * - 建议单确认耗时：从 suggestion.createdAt 到 suggestion.handledAt（ACKED 状态）
 * - 计划确认耗时：从 actionPlan.createdAt 到 actionPlan.confirmedAt（CONFIRMED 状态）
 * - 执行完成耗时：从 execution.startedAt 到 execution.finishedAt（SUCCESS 状态）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GovernanceOpsSlaService {

    /**
     * 默认 SLA 目标值（Contract section 6.1）
     */
    private static final long DEFAULT_SUGGESTION_ACK_SLA_MS = 86_400_000L; // 24 小时
    private static final long DEFAULT_PLAN_CONFIRM_SLA_MS = 86_400_000L;  // 24 小时
    private static final long DEFAULT_EXECUTION_COMPLETE_SLA_MS = 259_200_000L; // 72 小时

    private final ResourceGovernanceSuggestionMapper suggestionMapper;
    private final ResourceGovernanceActionPlanMapper actionPlanMapper;
    private final ResourceGovernanceActionExecutionMapper executionMapper;

    /**
     * 计算 SLA 统计数据.
     * GET 查询无写副作用（Contract section 6.4）。
     *
     * @param query 查询参数
     * @return SLA 响应
     */
    @Transactional(readOnly = true)
    public GovernanceSlaResponse calculateSla(GovernanceOpsSlaQuery query) {
        // 获取 SLA 目标值
        long suggestionAckSlaMs = query.getSuggestionAckSlaMs() != null
                ? query.getSuggestionAckSlaMs() : DEFAULT_SUGGESTION_ACK_SLA_MS;
        long planConfirmSlaMs = query.getPlanConfirmSlaMs() != null
                ? query.getPlanConfirmSlaMs() : DEFAULT_PLAN_CONFIRM_SLA_MS;
        long executionCompleteSlaMs = query.getExecutionCompleteSlaMs() != null
                ? query.getExecutionCompleteSlaMs() : DEFAULT_EXECUTION_COMPLETE_SLA_MS;

        // 查询建议单数据
        List<ResourceGovernanceSuggestion> suggestions = querySuggestions(query);

        // 查询动作计划数据
        List<ResourceGovernanceActionPlan> actionPlans = queryActionPlans(query);

        // 查询执行记录数据
        List<ResourceGovernanceActionExecution> executions = queryExecutions(query);

        // 计算建议单确认耗时
        SlaMetrics suggestionMetrics = calculateSuggestionAckMetrics(suggestions, suggestionAckSlaMs);

        // 计划确认耗时
        SlaMetrics planMetrics = calculatePlanConfirmMetrics(actionPlans, planConfirmSlaMs);

        // 执行完成耗时
        SlaMetrics executionMetrics = calculateExecutionCompleteMetrics(executions, executionCompleteSlaMs);

        // 计算总体 SLA 达标率
        long totalHit = suggestionMetrics.hitCount + planMetrics.hitCount + executionMetrics.hitCount;
        long totalSamples = suggestionMetrics.sampleCount + planMetrics.sampleCount + executionMetrics.sampleCount;
        double slaHitRate = totalSamples > 0 ? (double) totalHit / totalSamples : 0.0;

        // 使用第一个有效的 SLA 目标值作为返回值（按 Contract 优先级）
        Long slaTargetMs = suggestionAckSlaMs;

        GovernanceSlaResponse response = new GovernanceSlaResponse();
        response.setAvgSuggestionAckDurationMs(suggestionMetrics.avgDurationMs);
        response.setAvgPlanConfirmDurationMs(planMetrics.avgDurationMs);
        response.setAvgExecutionCompleteDurationMs(executionMetrics.avgDurationMs);
        response.setOverdueSuggestionCount(suggestionMetrics.overdueCount);
        response.setOverduePlanCount(planMetrics.overdueCount);
        response.setOverdueExecutionCount(executionMetrics.overdueCount);
        response.setSlaTargetMs(slaTargetMs);
        response.setSlaHitRate(slaHitRate);
        return response;
    }

    /**
     * 查询建议单列表.
     */
    private List<ResourceGovernanceSuggestion> querySuggestions(GovernanceOpsSlaQuery query) {
        return suggestionMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ResourceGovernanceSuggestion>()
                        .eq(query.getEnvId() != null, ResourceGovernanceSuggestion::getEnvId, query.getEnvId())
                        .eq(query.getResourcePoolId() != null, ResourceGovernanceSuggestion::getResourcePoolId, query.getResourcePoolId())
                        .ge(query.getStartTime() != null, ResourceGovernanceSuggestion::getCreatedAt, query.getStartTime())
                        .le(query.getEndTime() != null, ResourceGovernanceSuggestion::getCreatedAt, query.getEndTime())
                        .in(ResourceGovernanceSuggestion::getStatus, SuggestionStatus.ACKED, SuggestionStatus.RESOLVED)
        );
    }

    /**
     * 查询动作计划列表.
     */
    private List<ResourceGovernanceActionPlan> queryActionPlans(GovernanceOpsSlaQuery query) {
        return actionPlanMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ResourceGovernanceActionPlan>()
                        .eq(query.getEnvId() != null, ResourceGovernanceActionPlan::getEnvId, query.getEnvId())
                        .eq(query.getResourcePoolId() != null, ResourceGovernanceActionPlan::getResourcePoolId, query.getResourcePoolId())
                        .ge(query.getStartTime() != null, ResourceGovernanceActionPlan::getCreatedAt, query.getStartTime())
                        .le(query.getEndTime() != null, ResourceGovernanceActionPlan::getCreatedAt, query.getEndTime())
                        .in(ResourceGovernanceActionPlan::getStatus, ActionPlanStatus.CONFIRMED, ActionPlanStatus.EXECUTED)
        );
    }

    /**
     * 查询执行记录列表.
     */
    private List<ResourceGovernanceActionExecution> queryExecutions(GovernanceOpsSlaQuery query) {
        return executionMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ResourceGovernanceActionExecution>()
                        .eq(ResourceGovernanceActionExecution::getStatus, ActionExecutionStatus.SUCCESS)
                        .ge(query.getStartTime() != null, ResourceGovernanceActionExecution::getStartedAt, query.getStartTime())
                        .le(query.getEndTime() != null, ResourceGovernanceActionExecution::getStartedAt, query.getEndTime())
        );
    }

    /**
     * 计算建议单确认 SLA 指标.
     * Contract section 6.2: 从 createdAt 到 handledAt
     */
    private SlaMetrics calculateSuggestionAckMetrics(List<ResourceGovernanceSuggestion> suggestions, long slaMs) {
        if (suggestions == null || suggestions.isEmpty()) {
            return new SlaMetrics(0.0, 0L, 0L, 0L);
        }

        long totalDuration = 0;
        long hitCount = 0;
        long overdueCount = 0;
        long sampleCount = 0;

        for (ResourceGovernanceSuggestion suggestion : suggestions) {
            if (suggestion.getCreatedAt() == null || suggestion.getHandledAt() == null) {
                continue;
            }

            long durationMs = Duration.between(suggestion.getCreatedAt(), suggestion.getHandledAt()).toMillis();
            totalDuration += durationMs;
            sampleCount++;

            if (durationMs <= slaMs) {
                hitCount++;
            } else {
                overdueCount++;
            }
        }

        double avgDurationMs = sampleCount > 0 ? (double) totalDuration / sampleCount : 0.0;
        return new SlaMetrics(avgDurationMs, hitCount, overdueCount, sampleCount);
    }

    /**
     * 计算计划确认 SLA 指标.
     * Contract section 6.2: 从 createdAt 到 confirmedAt
     */
    private SlaMetrics calculatePlanConfirmMetrics(List<ResourceGovernanceActionPlan> actionPlans, long slaMs) {
        if (actionPlans == null || actionPlans.isEmpty()) {
            return new SlaMetrics(0.0, 0L, 0L, 0L);
        }

        long totalDuration = 0;
        long hitCount = 0;
        long overdueCount = 0;
        long sampleCount = 0;

        for (ResourceGovernanceActionPlan plan : actionPlans) {
            if (plan.getCreatedAt() == null || plan.getConfirmedAt() == null) {
                continue;
            }

            long durationMs = Duration.between(plan.getCreatedAt(), plan.getConfirmedAt()).toMillis();
            totalDuration += durationMs;
            sampleCount++;

            if (durationMs <= slaMs) {
                hitCount++;
            } else {
                overdueCount++;
            }
        }

        double avgDurationMs = sampleCount > 0 ? (double) totalDuration / sampleCount : 0.0;
        return new SlaMetrics(avgDurationMs, hitCount, overdueCount, sampleCount);
    }

    /**
     * 计算执行完成 SLA 指标.
     * Contract section 6.2: 从 startedAt 到 finishedAt
     */
    private SlaMetrics calculateExecutionCompleteMetrics(List<ResourceGovernanceActionExecution> executions, long slaMs) {
        if (executions == null || executions.isEmpty()) {
            return new SlaMetrics(0.0, 0L, 0L, 0L);
        }

        long totalDuration = 0;
        long hitCount = 0;
        long overdueCount = 0;
        long sampleCount = 0;

        for (ResourceGovernanceActionExecution execution : executions) {
            if (execution.getStartedAt() == null || execution.getFinishedAt() == null) {
                continue;
            }

            long durationMs = Duration.between(execution.getStartedAt(), execution.getFinishedAt()).toMillis();
            totalDuration += durationMs;
            sampleCount++;

            if (durationMs <= slaMs) {
                hitCount++;
            } else {
                overdueCount++;
            }
        }

        double avgDurationMs = sampleCount > 0 ? (double) totalDuration / sampleCount : 0.0;
        return new SlaMetrics(avgDurationMs, hitCount, overdueCount, sampleCount);
    }

    /**
     * SLA 指标内部类.
     */
    private record SlaMetrics(
            double avgDurationMs,
            long hitCount,
            long overdueCount,
            long sampleCount
    ) {}
}

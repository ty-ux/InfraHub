package com.infrahub.release.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.infrahub.release.dto.GovernanceEffectivenessResponse;
import com.infrahub.release.dto.GovernanceOpsEffectivenessQuery;
import com.infrahub.release.entity.ResourceGovernanceActionExecution;
import com.infrahub.release.enums.ActionExecutionStatus;
import com.infrahub.release.enums.ImpactType;
import com.infrahub.release.enums.RiskLevel;
import com.infrahub.release.mapper.ResourceGovernanceActionExecutionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 治理效果评估服务.
 * Contract: V2/L section 4.4, 7.
 *
 * 评估原则（Contract section 7.1）：
 * - 不自动判定真实业务成功：效果评估仅基于治理记录/影响回填/风险等级变化做统计
 * - sampleSize 必须可见：所有效果评估响应必须包含 sampleSize 字段
 * - UNKNOWN 或数据不足不得强行算改善：afterRiskLevel 为 UNKNOWN 时，不计入改善
 *
 * 改善判定规则（Contract section 7.2）：
 * - afterRiskLevel 优于 beforeRiskLevel → 改善（improved）
 * - afterRiskLevel 等于 beforeRiskLevel → 未变化（unchanged）
 * - afterRiskLevel 劣于 beforeRiskLevel → 恶化（worsened）
 * - beforeRiskLevel 或 afterRiskLevel 为 UNKNOWN → 不计入统计
 *
 * 风险等级排序（Contract section 7.3）：
 * CRITICAL > WARNING > NORMAL > UNKNOWN
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GovernanceOpsEffectivenessService {

    private final ResourceGovernanceActionExecutionMapper executionMapper;
    private final ObjectMapper objectMapper;

    /**
     * 计算效果评估数据.
     * GET 查询无写副作用（Contract section 6.4）。
     *
     * @param query 查询参数
     * @return 效果评估响应列表
     */
    @Transactional(readOnly = true)
    public List<GovernanceEffectivenessResponse> calculateEffectiveness(GovernanceOpsEffectivenessQuery query) {
        // 查询有影响记录的执行记录
        List<ResourceGovernanceActionExecution> executions = queryExecutionsWithImpact(query);

        if (executions.isEmpty()) {
            // 数据不足时返回空列表，sampleSize=0
            return List.of(buildEmptyResponse());
        }

        // 按影响类型分组统计
        List<GovernanceEffectivenessResponse> responses = new ArrayList<>();

        for (ImpactType impactType : ImpactType.values()) {
            if (impactType == ImpactType.NONE) {
                continue; // NONE 类型不统计
            }

            // 筛选该影响类型的执行记录
            List<ResourceGovernanceActionExecution> filteredExecutions = executions.stream()
                    .filter(e -> e.getImpactType() == impactType)
                    .toList();

            if (filteredExecutions.isEmpty()) {
                continue;
            }

            // 统计改善情况
            EffectivenessMetrics metrics = calculateMetrics(filteredExecutions);

            GovernanceEffectivenessResponse response = new GovernanceEffectivenessResponse();
            response.setImpactType(impactType.name());
            response.setBeforeRiskLevel(null); // 聚合级别不设置单值
            response.setAfterRiskLevel(null);  // 聚合级别不设置单值
            response.setImprovedCount(metrics.improvedCount);
            response.setUnchangedCount(metrics.unchangedCount);
            response.setWorsenedCount(metrics.worsenedCount);
            response.setImprovementRate(metrics.improvementRate);
            response.setSampleSize(metrics.sampleSize);

            responses.add(response);
        }

        // 如果没有有效的影响类型分组，返回整体统计
        if (responses.isEmpty()) {
            EffectivenessMetrics overallMetrics = calculateMetrics(executions);
            GovernanceEffectivenessResponse response = new GovernanceEffectivenessResponse();
            response.setImpactType(null);
            response.setBeforeRiskLevel(null);
            response.setAfterRiskLevel(null);
            response.setImprovedCount(overallMetrics.improvedCount);
            response.setUnchangedCount(overallMetrics.unchangedCount);
            response.setWorsenedCount(overallMetrics.worsenedCount);
            response.setImprovementRate(overallMetrics.improvementRate);
            response.setSampleSize(overallMetrics.sampleSize);
            responses.add(response);
        }

        return responses;
    }

    /**
     * 查询有影响记录的执行记录.
     */
    private List<ResourceGovernanceActionExecution> queryExecutionsWithImpact(GovernanceOpsEffectivenessQuery query) {
        LambdaQueryWrapper<ResourceGovernanceActionExecution> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ResourceGovernanceActionExecution::getStatus, ActionExecutionStatus.SUCCESS);
        wrapper.isNotNull(ResourceGovernanceActionExecution::getImpactType);
        wrapper.isNotNull(ResourceGovernanceActionExecution::getImpactRecordedAt);

        // 筛选条件
        if (query.getStartTime() != null) {
            wrapper.ge(ResourceGovernanceActionExecution::getImpactRecordedAt, query.getStartTime());
        }
        if (query.getEndTime() != null) {
            wrapper.le(ResourceGovernanceActionExecution::getImpactRecordedAt, query.getEndTime());
        }
        if (query.getImpactType() != null && !query.getImpactType().isEmpty()) {
            try {
                ImpactType impactType = ImpactType.valueOf(query.getImpactType());
                wrapper.eq(ResourceGovernanceActionExecution::getImpactType, impactType);
            } catch (IllegalArgumentException e) {
                log.warn("Invalid impactType filter: {}", query.getImpactType());
            }
        }

        return executionMapper.selectList(wrapper);
    }

    /**
     * 计算效果评估指标.
     */
    private EffectivenessMetrics calculateMetrics(List<ResourceGovernanceActionExecution> executions) {
        long improvedCount = 0;
        long unchangedCount = 0;
        long worsenedCount = 0;
        long validSamples = 0;

        for (ResourceGovernanceActionExecution execution : executions) {
            RiskLevelChange change = extractRiskLevelChange(execution);

            if (change == null || change.beforeRiskLevel == null || change.afterRiskLevel == null) {
                continue; // 数据不完整，不计入统计
            }

            // UNKNOWN 不计入统计（Contract section 7.2）
            if (change.beforeRiskLevel == RiskLevel.UNKNOWN || change.afterRiskLevel == RiskLevel.UNKNOWN) {
                continue;
            }

            validSamples++;

            int comparison = compareRiskLevel(change.beforeRiskLevel, change.afterRiskLevel);
            if (comparison > 0) {
                // beforeRiskLevel 风险更高（order值更大），治理后风险降低 → 改善
                improvedCount++;
            } else if (comparison == 0) {
                unchangedCount++;
            } else {
                // beforeRiskLevel 风险更低（order值更小），治理后风险升高 → 恶化
                worsenedCount++;
            }
        }

        // improvementRate = 改善数 / 有效样本数（Contract section 7.1）
        double improvementRate = validSamples > 0 ? (double) improvedCount / validSamples : 0.0;

        return new EffectivenessMetrics(improvedCount, unchangedCount, worsenedCount, validSamples, improvementRate);
    }

    /**
     * 从执行记录中提取风险等级变化.
     * 风险等级变化存储在 impactPayload 或 beforePayload/afterPayload 中.
     */
    private RiskLevelChange extractRiskLevelChange(ResourceGovernanceActionExecution execution) {
        try {
            Object impactPayload = execution.getImpactPayload();
            if (impactPayload == null) {
                return null;
            }

            // 尝试解析 payload
            Map<String, Object> payload;
            if (impactPayload instanceof Map) {
                payload = (Map<String, Object>) impactPayload;
            } else if (impactPayload instanceof String) {
                payload = objectMapper.readValue((String) impactPayload, Map.class);
            } else {
                payload = objectMapper.convertValue(impactPayload, Map.class);
            }

            RiskLevel beforeRiskLevel = extractRiskLevel(payload, "beforeRiskLevel", "risk_level_before", "riskLevelBefore");
            RiskLevel afterRiskLevel = extractRiskLevel(payload, "afterRiskLevel", "risk_level_after", "riskLevelAfter");

            return new RiskLevelChange(beforeRiskLevel, afterRiskLevel);
        } catch (Exception e) {
            log.debug("Failed to extract risk level change from execution: id={}, error={}",
                    execution.getId(), e.getMessage());
            return null;
        }
    }

    /**
     * 从 payload 中提取风险等级.
     */
    private RiskLevel extractRiskLevel(Map<String, Object> payload, String... keys) {
        for (String key : keys) {
            Object value = payload.get(key);
            if (value == null) {
                continue;
            }

            try {
                if (value instanceof RiskLevel) {
                    return (RiskLevel) value;
                }
                if (value instanceof String) {
                    return RiskLevel.valueOf((String) value);
                }
            } catch (IllegalArgumentException e) {
                log.debug("Invalid risk level value: {}", value);
            }
        }
        return null;
    }

    /**
     * 比较风险等级.
     * Contract section 7.3: CRITICAL > WARNING > NORMAL > UNKNOWN
     *
     * @return 正数表示 before > after（恶化），负数表示 before < after（改善），0 表示相等
     */
    private int compareRiskLevel(RiskLevel before, RiskLevel after) {
        return before.getOrder() - after.getOrder();
    }

    /**
     * 构建空数据响应.
     * Contract section 7.1: sampleSize 必须可见
     */
    private GovernanceEffectivenessResponse buildEmptyResponse() {
        GovernanceEffectivenessResponse response = new GovernanceEffectivenessResponse();
        response.setImpactType(null);
        response.setBeforeRiskLevel(null);
        response.setAfterRiskLevel(null);
        response.setImprovedCount(0L);
        response.setUnchangedCount(0L);
        response.setWorsenedCount(0L);
        response.setImprovementRate(0.0);
        response.setSampleSize(0L);
        return response;
    }

    /**
     * 效果评估指标内部类.
     */
    private record EffectivenessMetrics(
            long improvedCount,
            long unchangedCount,
            long worsenedCount,
            long sampleSize,
            double improvementRate
    ) {}

    /**
     * 风险等级变化内部类.
     */
    private record RiskLevelChange(RiskLevel beforeRiskLevel, RiskLevel afterRiskLevel) {}
}

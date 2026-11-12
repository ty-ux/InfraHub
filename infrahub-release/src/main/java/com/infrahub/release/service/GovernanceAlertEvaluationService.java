package com.infrahub.release.service;

import com.infrahub.release.dto.GovernanceAlertEvaluationResponse;
import com.infrahub.release.entity.GovernanceAlertRecord;
import com.infrahub.release.entity.GovernanceAlertRule;
import com.infrahub.release.enums.AlertOperator;
import com.infrahub.release.enums.GovernanceAlertLevel;
import com.infrahub.release.enums.GovernanceAlertStatus;
import com.infrahub.release.enums.GovernanceOpsMetricType;
import com.infrahub.release.mapper.GovernanceAlertRecordMapper;
import com.infrahub.release.mapper.GovernanceOpsMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 告警评估服务
 * Contract: v2-stage-m-contract.md Section 2.2, Section 7
 *
 * MANUAL_ONLY 约束：
 * - evaluate 为显式 POST 手动触发
 * - 不做自动定时任务
 * - 不触发自动执行动作
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GovernanceAlertEvaluationService {

    private final GovernanceAlertRuleService ruleService;
    private final GovernanceAlertRecordMapper alertRecordMapper;
    private final GovernanceOpsMapper governanceOpsMapper;

    /**
     * 手动触发告警评估
     * Contract: POST /api/assets/governance-ops/alerts/evaluate
     *
     * @param envId 环境 ID（可选，null 表示全局）
     * @param resourcePoolId 资源池 ID（可选，null 表示全局）
     * @param serviceId 服务 ID（可选，null 表示全局）
     * @return 评估响应
     */
    @Transactional
    public GovernanceAlertEvaluationResponse evaluate(Long envId, Long resourcePoolId, Long serviceId) {
        log.info("Starting governance alert evaluation: envId={}, resourcePoolId={}, serviceId={}",
                envId, resourcePoolId, serviceId);

        OffsetDateTime evaluatedAt = OffsetDateTime.now();
        List<GovernanceAlertRule> enabledRules = ruleService.getEnabledRulesForScope(envId, resourcePoolId, serviceId);
        int evaluatedRuleCount = enabledRules.size();
        List<Long> alertIds = new ArrayList<>();

        for (GovernanceAlertRule rule : enabledRules) {
            Double currentValue = getCurrentMetricValue(rule.getMetricType(), envId, resourcePoolId, serviceId);

            if (currentValue != null && evaluateRule(rule, currentValue)) {
                GovernanceAlertRecord record = createAlertRecord(rule, currentValue, envId, resourcePoolId, serviceId, evaluatedAt);
                alertRecordMapper.insert(record);
                alertIds.add(record.getId());
                log.info("Alert triggered: alertNo={}, ruleId={}, metricType={}, currentValue={}, threshold={}",
                        record.getAlertNo(), rule.getId(), rule.getMetricType(), currentValue, rule.getThresholdValue());
            }
        }

        log.info("Governance alert evaluation completed: evaluatedRuleCount={}, triggeredAlertCount={}",
                evaluatedRuleCount, alertIds.size());

        GovernanceAlertEvaluationResponse response = new GovernanceAlertEvaluationResponse();
        response.setEvaluatedAt(evaluatedAt);
        response.setEvaluatedRuleCount(evaluatedRuleCount);
        response.setTriggeredAlertCount(alertIds.size());
        response.setAlertIds(alertIds);
        response.setManualOnly(true);
        return response;
    }

    /**
     * 获取当前指标值
     * 调用 L 阶段 GovernanceOpsMapper 聚合方法获取实时数据
     */
    private Double getCurrentMetricValue(GovernanceOpsMetricType metricType, Long envId, Long resourcePoolId, Long serviceId) {
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime startTime = now.minusDays(1);

        if (metricType == GovernanceOpsMetricType.CRITICAL_RISK_COUNT) {
            Long count = governanceOpsMapper.countRisksByLevel(envId, resourcePoolId, "CRITICAL", startTime, now);
            return count != null ? count.doubleValue() : 0.0;
        }
        if (metricType == GovernanceOpsMetricType.WARNING_RISK_COUNT) {
            Long count = governanceOpsMapper.countRisksByLevel(envId, resourcePoolId, "WARNING", startTime, now);
            return count != null ? count.doubleValue() : 0.0;
        }
        if (metricType == GovernanceOpsMetricType.OPEN_SUGGESTION_COUNT) {
            Long count = governanceOpsMapper.countOpenSuggestions(envId, resourcePoolId, startTime, now);
            return count != null ? count.doubleValue() : 0.0;
        }
        if (metricType == GovernanceOpsMetricType.FAILED_EXECUTION_COUNT) {
            Long count = governanceOpsMapper.countExecutionsByStatus(envId, resourcePoolId, "FAILED", startTime, now);
            return count != null ? count.doubleValue() : 0.0;
        }
        if (metricType == GovernanceOpsMetricType.SLA_HIT_RATE) {
            Long overdue = governanceOpsMapper.countOverdueSuggestions(envId, resourcePoolId, 86400000L, startTime, now);
            Long total = governanceOpsMapper.countOpenSuggestions(envId, resourcePoolId, startTime, now);
            if (total == null || total == 0L) {
                return 1.0;
            }
            Long hit = total - (overdue != null ? overdue : 0L);
            return hit.doubleValue() / total.doubleValue();
        }
        if (metricType == GovernanceOpsMetricType.OVERDUE_REVIEW_ITEM_COUNT) {
            Long count = governanceOpsMapper.countOverdueSuggestions(envId, resourcePoolId, 86400000L, startTime, now);
            return count != null ? count.doubleValue() : 0.0;
        }
        Long count = governanceOpsMapper.countImpactRecorded(envId, resourcePoolId, startTime, now);
        return count != null ? count.doubleValue() : 0.0;
    }

    /**
     * 评估规则是否命中
     * 支持 GT/GTE/LT/LTE/EQ 运算符
     */
    private boolean evaluateRule(GovernanceAlertRule rule, Double currentValue) {
        Double threshold = rule.getThresholdValue();
        AlertOperator operator = rule.getOperator();

        if (operator == AlertOperator.GT) {
            return currentValue > threshold;
        }
        if (operator == AlertOperator.GTE) {
            return currentValue >= threshold;
        }
        if (operator == AlertOperator.LT) {
            return currentValue < threshold;
        }
        if (operator == AlertOperator.LTE) {
            return currentValue <= threshold;
        }
        return Math.abs(currentValue - threshold) < 0.0001;
    }

    /**
     * 创建告警记录
     * 本阶段不去重，每次评估生成新记录
     */
    private GovernanceAlertRecord createAlertRecord(GovernanceAlertRule rule, Double currentValue,
                                                     Long envId, Long resourcePoolId, Long serviceId,
                                                     OffsetDateTime createdAt) {
        GovernanceAlertRecord record = new GovernanceAlertRecord();
        record.setAlertNo(generateAlertNo(createdAt));
        record.setRuleId(rule.getId());
        record.setRuleName(rule.getRuleName());
        record.setMetricType(rule.getMetricType());
        record.setCurrentValue(currentValue);
        record.setThresholdValue(rule.getThresholdValue());
        record.setOperator(rule.getOperator());
        record.setAlertLevel(rule.getAlertLevel());
        record.setAlertStatus(GovernanceAlertStatus.OPEN);
        record.setEnvId(rule.getEnvId() != null ? rule.getEnvId() : envId);
        record.setResourcePoolId(rule.getResourcePoolId() != null ? rule.getResourcePoolId() : resourcePoolId);
        record.setServiceId(rule.getServiceId() != null ? rule.getServiceId() : serviceId);
        record.setReason(buildReason(rule, currentValue));
        record.setCreatedAt(createdAt);

        return record;
    }

    /**
     * 生成告警编号
     * 格式：ALTR-YYYYMMDD-XXXXXX
     */
    private String generateAlertNo(OffsetDateTime timestamp) {
        Long seq = alertRecordMapper.getNextAlertNoSequence();
        String datePart = timestamp.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        return String.format("ALTR-%s-%06d", datePart, seq != null ? seq : 1L);
    }

    /**
     * 构建告警原因
     */
    private String buildReason(GovernanceAlertRule rule, Double currentValue) {
        return String.format("指标 %s 当前值 %.2f %s 阈值 %.2f",
                rule.getMetricType().name(),
                currentValue,
                getOperatorDescription(rule.getOperator()),
                rule.getThresholdValue());
    }

    private String getOperatorDescription(AlertOperator operator) {
        if (operator == AlertOperator.GT) {
            return "大于";
        }
        if (operator == AlertOperator.GTE) {
            return "大于等于";
        }
        if (operator == AlertOperator.LT) {
            return "小于";
        }
        if (operator == AlertOperator.LTE) {
            return "小于等于";
        }
        return "等于";
    }
}

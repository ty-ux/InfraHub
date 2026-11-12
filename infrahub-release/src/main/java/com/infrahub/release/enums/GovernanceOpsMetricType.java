package com.infrahub.release.enums;

/**
 * 治理运营指标类型枚举
 * Contract: v2-stage-m-contract.md Section 5.1
 */
public enum GovernanceOpsMetricType {

    CRITICAL_RISK_COUNT,
    WARNING_RISK_COUNT,
    OPEN_SUGGESTION_COUNT,
    FAILED_EXECUTION_COUNT,
    SLA_HIT_RATE,
    OVERDUE_REVIEW_ITEM_COUNT,
    IMPACT_RECORDED_COUNT
}
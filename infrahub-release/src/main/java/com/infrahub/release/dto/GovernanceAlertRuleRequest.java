package com.infrahub.release.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 告警规则请求 DTO
 * Contract: v2-stage-m-contract.md Section 4.1
 */
@Data
public class GovernanceAlertRuleRequest {

    @NotBlank(message = "ruleName is required")
    private String ruleName;

    @NotBlank(message = "metricType is required")
    private String metricType;

    @NotBlank(message = "operator is required")
    private String operator;

    @NotNull(message = "thresholdValue is required")
    private Double thresholdValue;

    @NotBlank(message = "alertLevel is required")
    private String alertLevel;

    private Long envId;

    private Long resourcePoolId;

    private Long serviceId;

    @NotNull(message = "enabled is required")
    private Boolean enabled;

    private String description;

    @AssertTrue(message = "metricType must be one of: CRITICAL_RISK_COUNT, WARNING_RISK_COUNT, OPEN_SUGGESTION_COUNT, FAILED_EXECUTION_COUNT, SLA_HIT_RATE, OVERDUE_REVIEW_ITEM_COUNT, IMPACT_RECORDED_COUNT")
    public boolean isValidMetricType() {
        if (metricType == null || metricType.isEmpty()) {
            return true;
        }
        return switch (metricType) {
            case "CRITICAL_RISK_COUNT", "WARNING_RISK_COUNT", "OPEN_SUGGESTION_COUNT",
                 "FAILED_EXECUTION_COUNT", "SLA_HIT_RATE", "OVERDUE_REVIEW_ITEM_COUNT",
                 "IMPACT_RECORDED_COUNT" -> true;
            default -> false;
        };
    }

    @AssertTrue(message = "operator must be one of: GT, GTE, LT, LTE, EQ")
    public boolean isValidOperator() {
        if (operator == null || operator.isEmpty()) {
            return true;
        }
        return switch (operator) {
            case "GT", "GTE", "LT", "LTE", "EQ" -> true;
            default -> false;
        };
    }

    @AssertTrue(message = "alertLevel must be one of: INFO, WARNING, CRITICAL")
    public boolean isValidAlertLevel() {
        if (alertLevel == null || alertLevel.isEmpty()) {
            return true;
        }
        return switch (alertLevel) {
            case "INFO", "WARNING", "CRITICAL" -> true;
            default -> false;
        };
    }
}
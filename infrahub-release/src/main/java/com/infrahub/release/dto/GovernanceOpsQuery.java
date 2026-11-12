package com.infrahub.release.dto;

import jakarta.validation.constraints.AssertTrue;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 治理运营查询参数
 * Contract: v2-stage-l-contract.md Section 3.1
 */
@Data
public class GovernanceOpsQuery {

    private Long envId;

    private Long resourcePoolId;

    private Long serviceId;

    private OffsetDateTime startTime;

    private OffsetDateTime endTime;

    /**
     * 时间间隔: HOUR, DAY, WEEK, MONTH
     */
    private String interval;

    private String riskLevel;

    private String suggestionStatus;

    private String actionPlanStatus;

    private String executionStatus;

    private String impactType;

    @AssertTrue(message = "interval must be one of: HOUR, DAY, WEEK, MONTH")
    public boolean isValidInterval() {
        if (interval == null || interval.isEmpty()) {
            return true;
        }
        return switch (interval) {
            case "HOUR", "DAY", "WEEK", "MONTH" -> true;
            default -> false;
        };
    }

    @AssertTrue(message = "startTime must be before or equal to endTime")
    public boolean isValidTimeRange() {
        if (startTime == null || endTime == null) {
            return true;
        }
        return !startTime.isAfter(endTime);
    }
}

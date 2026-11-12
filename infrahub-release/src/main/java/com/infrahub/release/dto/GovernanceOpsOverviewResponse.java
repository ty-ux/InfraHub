package com.infrahub.release.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 治理运营总览响应
 * Contract: v2-stage-l-contract.md Section 4.1
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GovernanceOpsOverviewResponse {

    private Long totalRiskCount;

    private Long criticalRiskCount;

    private Long warningRiskCount;

    private Long openSuggestionCount;

    private Long confirmedPlanCount;

    private Long runningExecutionCount;

    private Long completedExecutionCount;

    private Long failedExecutionCount;

    private Long impactRecordedCount;

    private Double avgCloseDurationMs;

    private Boolean manualOnly;
}
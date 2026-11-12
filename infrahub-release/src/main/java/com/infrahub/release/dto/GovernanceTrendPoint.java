package com.infrahub.release.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 治理趋势数据点
 * Contract: v2-stage-l-contract.md Section 4.2
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GovernanceTrendPoint {

    private String timeBucket;

    private Long riskCount;

    private Long suggestionCount;

    private Long actionPlanCount;

    private Long executionCount;

    private Long impactCount;

    private Long successCount;

    private Long failedCount;
}
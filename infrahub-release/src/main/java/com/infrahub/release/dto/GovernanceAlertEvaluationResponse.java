package com.infrahub.release.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 告警评估响应 DTO
 * Contract: v2-stage-m-contract.md Section 4.3
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GovernanceAlertEvaluationResponse {

    private OffsetDateTime evaluatedAt;

    private Integer evaluatedRuleCount;

    private Integer triggeredAlertCount;

    private List<Long> alertIds;

    private Boolean manualOnly;
}
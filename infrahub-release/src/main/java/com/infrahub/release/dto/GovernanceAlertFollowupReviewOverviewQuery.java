package com.infrahub.release.dto;

import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 复盘概览查询参数 DTO
 * Contract: v2-stage-s-contract.md Section 4.1
 */
@Data
public class GovernanceAlertFollowupReviewOverviewQuery {

    private OffsetDateTime startTime;

    private OffsetDateTime endTime;
}

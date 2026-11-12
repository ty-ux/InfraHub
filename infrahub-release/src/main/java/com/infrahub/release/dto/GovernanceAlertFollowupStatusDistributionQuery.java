package com.infrahub.release.dto;

import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 跟进状态分布查询参数 DTO
 * Contract: v2-stage-s-contract.md Section 4.3
 */
@Data
public class GovernanceAlertFollowupStatusDistributionQuery {

    private OffsetDateTime startTime;

    private OffsetDateTime endTime;

    private Long alertRecordId;

    private Long notificationRecordId;
}

package com.infrahub.release.dto;

import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 回执效果统计查询参数 DTO
 * Contract: v2-stage-s-contract.md Section 4.2
 */
@Data
public class GovernanceAlertReceiptStatsQuery {

    private OffsetDateTime startTime;

    private OffsetDateTime endTime;

    private String channelType;

    private Long targetUserId;
}

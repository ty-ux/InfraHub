package com.infrahub.release.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 按告警维度分析响应项 DTO
 * Contract: v2-stage-s-contract.md Section 3.5.1
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GovernanceAlertFollowupByAlertItem {

    private Long alertRecordId;

    private String alertNo;

    private String alertTitle;

    private String alertLevel;

    private String alertStatus;

    private Long notificationCount;

    private Long followupCount;

    private Long uniqueHandlerCount;

    private Long confirmedReceiptCount;

    private Long pendingReceiptCount;

    private Double closedLoopRate;

    private OffsetDateTime latestFollowupAt;

    private OffsetDateTime createdAt;
}

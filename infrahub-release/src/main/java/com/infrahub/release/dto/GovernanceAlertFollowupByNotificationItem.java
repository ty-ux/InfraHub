package com.infrahub.release.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 按通知维度分析响应项 DTO
 * Contract: v2-stage-s-contract.md Section 3.6.1
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GovernanceAlertFollowupByNotificationItem {

    private Long notificationRecordId;

    private String notificationNo;

    private Long alertRecordId;

    private String alertNo;

    private String alertTitle;

    private String channelType;

    private String notificationStatus;

    private Long targetUserId;

    private String targetUserName;

    private Long followupCount;

    private String latestFollowupStatus;

    private String latestReceiptStatus;

    private OffsetDateTime firstFollowupAt;

    private OffsetDateTime latestFollowupAt;

    private OffsetDateTime createdAt;
}

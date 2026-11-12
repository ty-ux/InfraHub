package com.infrahub.release.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 复盘明细列表响应项 DTO
 * Contract: v2-stage-s-contract.md Section 3.7.1
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GovernanceAlertFollowupReviewDetailItem {

    private Long followupId;

    private String followUpNo;

    private Long notificationRecordId;

    private String notificationNo;

    private Long alertRecordId;

    private String alertNo;

    private String alertTitle;

    private String alertLevel;

    private String alertStatus;

    private String channelType;

    private Long targetUserId;

    private String targetUserName;

    private String followUpType;

    private String followUpContent;

    private String followUpResult;

    private String followUpStatus;

    private String receiptStatus;

    private String receiptNote;

    private Long createdBy;

    private String createdByName;

    private OffsetDateTime createdAt;

    private Long confirmedBy;

    private String confirmedByName;

    private OffsetDateTime confirmedAt;

    private Boolean firstFollowupForNotification;

    private Long minutesSinceNotification;
}

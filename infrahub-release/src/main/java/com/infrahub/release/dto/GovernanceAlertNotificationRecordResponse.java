package com.infrahub.release.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 通知记录响应 DTO
 * Contract: v2-stage-q-contract.md Section 3.3
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GovernanceAlertNotificationRecordResponse {

    private Long id;

    private String notificationNo;

    private Long alertRecordId;

    private String alertNo;

    private String alertTitle;

    private String channelType;

    private String notificationStatus;

    private Long targetUserId;

    private String targetUserName;

    private String subject;

    private String content;

    private String notifyReason;

    private String evidencePayload;

    private Long createdBy;

    private String createdByName;

    private LocalDateTime createdAt;
}
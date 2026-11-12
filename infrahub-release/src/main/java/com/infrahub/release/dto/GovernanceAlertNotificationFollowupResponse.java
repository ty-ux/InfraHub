package com.infrahub.release.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 跟进记录响应 DTO
 * Contract: v2-stage-r-contract.md Section 3.3
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GovernanceAlertNotificationFollowupResponse {

    private Long id;

    private String followUpNo;

    private Long notificationRecordId;

    private String notificationNo;

    private Long alertRecordId;

    private String alertNo;

    private String followUpType;

    private String followUpContent;

    private String followUpResult;

    private String followUpStatus;

    private String receiptStatus;

    private String receiptNote;

    private String evidencePayload;

    private Long createdBy;

    private String createdByName;

    private LocalDateTime createdAt;

    private Long confirmedBy;

    private String confirmedByName;

    private LocalDateTime confirmedAt;
}

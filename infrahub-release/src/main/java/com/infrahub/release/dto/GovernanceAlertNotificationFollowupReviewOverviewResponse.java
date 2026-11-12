package com.infrahub.release.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 通知跟进复盘概览响应 DTO
 * Contract: v2-stage-s-contract.md Section 3.1
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GovernanceAlertNotificationFollowupReviewOverviewResponse {

    private Long totalNotifications;

    private Long followedUpNotifications;

    private Long pendingFollowupNotifications;

    private Long totalFollowupRecords;

    private Long completedFollowupRecords;

    private Long inProgressFollowupRecords;

    private Long cancelledFollowupRecords;

    private Long pendingReceiptCount;

    private Long confirmedReceiptCount;

    private Long failedReceiptCount;

    private Long noResponseCount;

    private Double followupCompletionRate;

    private Double receiptConfirmationRate;

    private Double averageFirstFollowupMinutes;

    private Long overdueFollowupCount;

    private OffsetDateTime updatedAt;
}

package com.infrahub.release.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 通知草稿响应 DTO
 * Contract: v2-stage-q-contract.md Section 3.1
 *
 * 此响应用于预览通知内容，不产生写副作用。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GovernanceAlertNotificationDraftResponse {

    private Long alertRecordId;

    private String alertTitle;

    private String alertLevel;

    private String alertStatus;

    private Long assignedTo;

    private String assignedToName;

    private Long escalatedTo;

    private String escalatedToName;

    private String channelType;

    private Long targetUserId;

    private String targetUserName;

    private String subject;

    private String content;

    private String suggestedAction;

    private LocalDateTime generatedAt;
}
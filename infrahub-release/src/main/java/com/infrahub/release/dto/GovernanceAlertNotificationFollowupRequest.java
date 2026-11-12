package com.infrahub.release.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 创建跟进记录请求 DTO
 * Contract: v2-stage-r-contract.md Section 2.1
 */
@Data
public class GovernanceAlertNotificationFollowupRequest {

    @NotNull(message = "通知记录ID不能为空")
    private Long notificationRecordId;

    @NotBlank(message = "跟进类型不能为空")
    private String followUpType;

    @NotBlank(message = "跟进内容不能为空")
    private String followUpContent;

    private String followUpResult;

    private String receiptStatus;

    private String evidencePayload;
}

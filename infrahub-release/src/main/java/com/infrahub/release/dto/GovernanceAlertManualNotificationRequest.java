package com.infrahub.release.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 人工确认通知请求 DTO
 * Contract: v2-stage-q-contract.md Section 3.2
 *
 * 此接口产生写副作用，创建通知记录，生成治理证据链事件。
 */
@Data
public class GovernanceAlertManualNotificationRequest {

    @NotNull(message = "告警记录ID不能为空")
    private Long alertRecordId;

    @NotBlank(message = "通知渠道类型不能为空")
    private String channelType;

    @NotNull(message = "目标用户ID不能为空")
    private Long targetUserId;

    @NotBlank(message = "目标用户姓名不能为空")
    private String targetUserName;

    @NotBlank(message = "通知主题不能为空")
    private String subject;

    @NotBlank(message = "通知内容不能为空")
    private String content;

    @NotBlank(message = "通知原因说明不能为空")
    private String notifyReason;

    private String evidencePayload;
}
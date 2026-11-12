package com.infrahub.release.dto;

import com.infrahub.release.entity.MonitorNotificationRecord;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * E3.2: 通知记录响应 DTO
 */
@Data
@Builder
public class NotificationRecordResponse {

    private Long id;
    private Long alertEventId;
    private Long alertRuleId;
    private String channelType;
    private String channelTypeLabel;
    private String channelTarget;
    private String notificationStatus;
    private String notificationStatusLabel;
    private Integer responseStatus;
    private String errorMessage;
    private OffsetDateTime sentAt;
    private OffsetDateTime createdAt;

    public static NotificationRecordResponse fromEntity(MonitorNotificationRecord entity) {
        return NotificationRecordResponse.builder()
                .id(entity.getId())
                .alertEventId(entity.getAlertEventId())
                .alertRuleId(entity.getAlertRuleId())
                .channelType(entity.getChannelType())
                .channelTypeLabel(getChannelTypeLabel(entity.getChannelType()))
                .channelTarget(entity.getChannelTarget())
                .notificationStatus(entity.getNotificationStatus())
                .notificationStatusLabel(getStatusLabel(entity.getNotificationStatus()))
                .responseStatus(entity.getResponseStatus())
                .errorMessage(entity.getErrorMessage())
                .sentAt(entity.getSentAt())
                .createdAt(entity.getCreatedAt())
                .build();
    }

    private static String getChannelTypeLabel(String type) {
        if ("WEBHOOK".equals(type)) return "Webhook";
        if ("DINGTALK".equals(type)) return "钉钉";
        if ("FEISHU".equals(type)) return "飞书";
        if ("EMAIL".equals(type)) return "邮件";
        if ("SMS".equals(type)) return "短信";
        return type;
    }

    private static String getStatusLabel(String status) {
        if ("SENT".equals(status)) return "已发送";
        if ("FAILED".equals(status)) return "发送失败";
        if ("PENDING".equals(status)) return "待发送";
        return status;
    }
}
package com.infrahub.release.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.infrahub.release.enums.GovernanceAlertNotificationChannelType;
import com.infrahub.release.enums.GovernanceAlertNotificationStatus;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 告警通知记录实体
 * Contract: v2-stage-q-contract.md Section 9.1
 *
 * 表名：release_governance_alert_notification_record
 *
 * MANUAL_ONLY 约束（Contract Section 1.1-1.3）：
 * - 不调用真实 SMTP/Webhook/短信/企业微信/钉钉/飞书发送器
 * - 不自动发送通知
 * - 不自动分派通知人
 * - 不自动升级通知
 * - 不做定时任务自动提醒
 */
@Data
@TableName(value = "release_governance_alert_notification_record", autoResultMap = true)
public class GovernanceAlertNotificationRecord {

    private Long id;

    private String notificationNo;

    private Long alertRecordId;

    private String alertNo;

    private String alertTitle;

    private GovernanceAlertNotificationChannelType channelType;

    private GovernanceAlertNotificationStatus notificationStatus;

    private Long targetUserId;

    private String targetUserName;

    private String subject;

    private String content;

    private String notifyReason;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private String evidencePayload;

    private Long createdBy;

    private String createdByName;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private Integer deleted;
}
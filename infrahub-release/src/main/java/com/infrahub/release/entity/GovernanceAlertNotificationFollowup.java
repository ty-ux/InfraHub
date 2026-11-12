package com.infrahub.release.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.infrahub.release.enums.GovernanceAlertNotificationFollowupStatus;
import com.infrahub.release.enums.GovernanceAlertNotificationFollowupType;
import com.infrahub.release.enums.GovernanceAlertReceiptStatus;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 告警通知跟进记录实体
 * Contract: v2-stage-r-contract.md Section 8.1
 *
 * 表名：release_governance_alert_notification_followup
 *
 * MANUAL_ONLY 约束（Contract Section 1.1, 14.1）：
 * - 不自动发送通知
 * - 不自动确认回执
 * - 不自动处理告警
 * - 不做 SSH/Docker/Kubernetes 调用
 */
@Data
@TableName(value = "release_governance_alert_notification_followup", autoResultMap = true)
public class GovernanceAlertNotificationFollowup {

    private Long id;

    private String followUpNo;

    private Long notificationRecordId;

    private String notificationNo;

    private Long alertRecordId;

    private String alertNo;

    private GovernanceAlertNotificationFollowupType followUpType;

    private String followUpContent;

    private String followUpResult;

    private GovernanceAlertNotificationFollowupStatus followUpStatus;

    private GovernanceAlertReceiptStatus receiptStatus;

    private String receiptNote;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private String evidencePayload;

    private Long createdBy;

    private String createdByName;

    private LocalDateTime createdAt;

    private Long confirmedBy;

    private String confirmedByName;

    private LocalDateTime confirmedAt;

    private LocalDateTime updatedAt;

    private Integer deleted;
}

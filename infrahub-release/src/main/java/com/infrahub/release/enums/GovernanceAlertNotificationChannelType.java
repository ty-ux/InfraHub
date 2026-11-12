package com.infrahub.release.enums;

/**
 * 通知渠道类型枚举
 * Contract: v2-stage-q-contract.md Section 5.1
 *
 * 说明：
 * - INTERNAL: 内部通知（系统内记录）
 * - EMAIL_DRAFT: 邮件草稿（仅生成草稿，不发送）
 * - WEBHOOK_DRAFT: Webhook 草稿（仅生成草稿，不发送）
 *
 * Q 阶段不实现真实邮件/Webhook 发送功能
 */
public enum GovernanceAlertNotificationChannelType {

    INTERNAL,
    EMAIL_DRAFT,
    WEBHOOK_DRAFT
}

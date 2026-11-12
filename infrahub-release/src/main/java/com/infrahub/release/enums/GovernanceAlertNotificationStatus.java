package com.infrahub.release.enums;

/**
 * 通知状态枚举
 * Contract: v2-stage-q-contract.md Section 5.2
 *
 * 状态流转：
 * [DRAFTED] --人工确认--> [MANUALLY_CONFIRMED]
 * [DRAFTED] --人工取消--> [CANCELLED]
 *
 * 说明：
 * - DRAFTED: 草稿状态
 * - MANUALLY_CONFIRMED: 人工确认已提醒（终态，不可回退）
 * - CANCELLED: 已取消（终态，不可回退）
 *
 * MANUALLY_CONFIRMED 只表示人工确认已提醒，不代表系统自动发送成功
 * Q 阶段不实现真实发送确认功能
 */
public enum GovernanceAlertNotificationStatus {

    DRAFTED,
    MANUALLY_CONFIRMED,
    CANCELLED
}

package com.infrahub.release.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.infrahub.release.dto.*;
import com.infrahub.release.mapper.GovernanceAlertFollowupReviewMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 告警通知跟进复盘分析服务
 * Contract: v2-stage-s-contract.md
 *
 * S 阶段只读分析 API：
 * - GET /by-handler: 按负责人维度闭环分析
 * - GET /by-alert: 按告警维度闭环分析
 * - GET /by-notification: 按通知维度闭环分析
 * - GET /details: 复盘明细列表查询
 *
 * MANUAL_ONLY 约束（Contract Section 1.1, 15.1）：
 * - 不自动发送通知
 * - 不自动确认回执
 * - 不自动处理告警
 * - 不做 SSH/Docker/Kubernetes 调用
 * - 不改变 M/N/O/P/Q/R 阶段状态机语义
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GovernanceAlertFollowupReviewService {

    private static final int MAX_PAGE_SIZE = 200;

    private final GovernanceAlertFollowupReviewMapper reviewMapper;

    /**
     * 按负责人维度分析
     * Contract: v2-stage-s-contract.md Section 2.4, 3.4, 4.4
     *
     * @param query 查询参数
     * @return 分页响应
     */
    @Transactional(readOnly = true)
    public GovernanceAlertFollowupByHandlerPageResponse queryByHandler(GovernanceAlertFollowupByHandlerQuery query) {
        int pageSize = Math.min(query.getPageSize(), MAX_PAGE_SIZE);
        int pageNum = query.getPageNum();

        Page<Map<String, Object>> page = new Page<>(pageNum, pageSize);
        Page<Map<String, Object>> resultPage = reviewMapper.queryByHandler(
                page,
                query.getStartTime(),
                query.getEndTime(),
                query.getHandlerId()
        );

        List<GovernanceAlertFollowupByHandlerItem> records = resultPage.getRecords().stream()
                .map(this::mapToByHandlerItem)
                .collect(Collectors.toList());

        return GovernanceAlertFollowupByHandlerPageResponse.builder()
                .records(records)
                .total(resultPage.getTotal())
                .pageNum((int) resultPage.getCurrent())
                .pageSize((int) resultPage.getSize())
                .build();
    }

    /**
     * 按告警维度分析
     * Contract: v2-stage-s-contract.md Section 2.5, 3.5, 4.5
     *
     * @param query 查询参数
     * @return 分页响应
     */
    @Transactional(readOnly = true)
    public GovernanceAlertFollowupByAlertPageResponse queryByAlert(GovernanceAlertFollowupByAlertQuery query) {
        int pageSize = Math.min(query.getPageSize(), MAX_PAGE_SIZE);
        int pageNum = query.getPageNum();

        Page<Map<String, Object>> page = new Page<>(pageNum, pageSize);
        Page<Map<String, Object>> resultPage = reviewMapper.queryByAlert(
                page,
                query.getStartTime(),
                query.getEndTime(),
                query.getAlertRecordId(),
                query.getAlertLevel(),
                query.getAlertStatus()
        );

        List<GovernanceAlertFollowupByAlertItem> records = resultPage.getRecords().stream()
                .map(this::mapToByAlertItem)
                .collect(Collectors.toList());

        return GovernanceAlertFollowupByAlertPageResponse.builder()
                .records(records)
                .total(resultPage.getTotal())
                .pageNum((int) resultPage.getCurrent())
                .pageSize((int) resultPage.getSize())
                .build();
    }

    /**
     * 按通知维度分析
     * Contract: v2-stage-s-contract.md Section 2.6, 3.6, 4.6
     *
     * @param query 查询参数
     * @return 分页响应
     */
    @Transactional(readOnly = true)
    public GovernanceAlertFollowupByNotificationPageResponse queryByNotification(GovernanceAlertFollowupByNotificationQuery query) {
        int pageSize = Math.min(query.getPageSize(), MAX_PAGE_SIZE);
        int pageNum = query.getPageNum();

        Page<Map<String, Object>> page = new Page<>(pageNum, pageSize);
        Page<Map<String, Object>> resultPage = reviewMapper.queryByNotification(
                page,
                query.getStartTime(),
                query.getEndTime(),
                query.getNotificationRecordId(),
                query.getAlertRecordId(),
                query.getTargetUserId(),
                query.getChannelType(),
                query.getFollowUpStatus(),
                query.getReceiptStatus()
        );

        List<GovernanceAlertFollowupByNotificationItem> records = resultPage.getRecords().stream()
                .map(this::mapToByNotificationItem)
                .collect(Collectors.toList());

        return GovernanceAlertFollowupByNotificationPageResponse.builder()
                .records(records)
                .total(resultPage.getTotal())
                .pageNum((int) resultPage.getCurrent())
                .pageSize((int) resultPage.getSize())
                .build();
    }

    /**
     * 复盘明细列表查询
     * Contract: v2-stage-s-contract.md Section 2.7, 3.7, 4.7
     *
     * @param query 查询参数
     * @return 分页响应
     */
    @Transactional(readOnly = true)
    public GovernanceAlertFollowupReviewDetailPageResponse queryDetails(GovernanceAlertFollowupReviewDetailQuery query) {
        int pageSize = Math.min(query.getPageSize(), MAX_PAGE_SIZE);
        int pageNum = query.getPageNum();

        Page<Map<String, Object>> page = new Page<>(pageNum, pageSize);
        Page<Map<String, Object>> resultPage = reviewMapper.queryDetails(
                page,
                query.getStartTime(),
                query.getEndTime(),
                query.getAlertRecordId(),
                query.getNotificationRecordId(),
                query.getTargetUserId(),
                query.getCreatedBy(),
                query.getFollowUpType(),
                query.getFollowUpStatus(),
                query.getReceiptStatus()
        );

        List<GovernanceAlertFollowupReviewDetailItem> records = resultPage.getRecords().stream()
                .map(this::mapToDetailItem)
                .collect(Collectors.toList());

        return GovernanceAlertFollowupReviewDetailPageResponse.builder()
                .records(records)
                .total(resultPage.getTotal())
                .pageNum((int) resultPage.getCurrent())
                .pageSize((int) resultPage.getSize())
                .build();
    }

    // ==================== 映射方法 ====================

    private GovernanceAlertFollowupByHandlerItem mapToByHandlerItem(Map<String, Object> row) {
        return GovernanceAlertFollowupByHandlerItem.builder()
                .handlerId(getLongValue(row, "handlerid"))
                .handlerName(getStringValue(row, "handlername"))
                .totalFollowupCount(getLongValue(row, "totalfollowupcount"))
                .completedCount(getLongValue(row, "completedcount"))
                .inProgressCount(getLongValue(row, "inprogresscount"))
                .cancelledCount(getLongValue(row, "cancelledcount"))
                .completionRate(getDoubleValue(row, "completionrate"))
                .averageFollowupMinutes(getDoubleValue(row, "averagefollowupminutes"))
                .firstFollowupCount(getLongValue(row, "firstfollowupcount"))
                .averageFirstFollowupMinutes(getDoubleValue(row, "averagefirstfollowupminutes"))
                .build();
    }

    private GovernanceAlertFollowupByAlertItem mapToByAlertItem(Map<String, Object> row) {
        return GovernanceAlertFollowupByAlertItem.builder()
                .alertRecordId(getLongValue(row, "alertrecordid"))
                .alertNo(getStringValue(row, "alertno"))
                .alertTitle(getStringValue(row, "alerttitle"))
                .alertLevel(getStringValue(row, "alertlevel"))
                .alertStatus(getStringValue(row, "alertstatus"))
                .notificationCount(getLongValue(row, "notificationcount"))
                .followupCount(getLongValue(row, "followupcount"))
                .uniqueHandlerCount(getLongValue(row, "uniquehandlercount"))
                .confirmedReceiptCount(getLongValue(row, "confirmedreceiptcount"))
                .pendingReceiptCount(getLongValue(row, "pendingreceiptcount"))
                .closedLoopRate(getDoubleValue(row, "closedlooprate"))
                .latestFollowupAt(getOffsetDateTimeValue(row, "latestfollowupat"))
                .createdAt(getOffsetDateTimeValue(row, "createdat"))
                .build();
    }

    private GovernanceAlertFollowupByNotificationItem mapToByNotificationItem(Map<String, Object> row) {
        return GovernanceAlertFollowupByNotificationItem.builder()
                .notificationRecordId(getLongValue(row, "notificationrecordid"))
                .notificationNo(getStringValue(row, "notificationno"))
                .alertRecordId(getLongValue(row, "alertrecordid"))
                .alertNo(getStringValue(row, "alertno"))
                .alertTitle(getStringValue(row, "alerttitle"))
                .channelType(getStringValue(row, "channeltype"))
                .notificationStatus(getStringValue(row, "notificationstatus"))
                .targetUserId(getLongValue(row, "targetuserid"))
                .targetUserName(getStringValue(row, "targetusername"))
                .followupCount(getLongValue(row, "followupcount"))
                .latestFollowupStatus(getStringValue(row, "latestfollowupstatus"))
                .latestReceiptStatus(getStringValue(row, "latestreceiptstatus"))
                .firstFollowupAt(getOffsetDateTimeValue(row, "firstfollowupat"))
                .latestFollowupAt(getOffsetDateTimeValue(row, "latestfollowupat"))
                .createdAt(getOffsetDateTimeValue(row, "createdat"))
                .build();
    }

    private GovernanceAlertFollowupReviewDetailItem mapToDetailItem(Map<String, Object> row) {
        return GovernanceAlertFollowupReviewDetailItem.builder()
                .followupId(getLongValue(row, "followupid"))
                .followUpNo(getStringValue(row, "followupno"))
                .notificationRecordId(getLongValue(row, "notificationrecordid"))
                .notificationNo(getStringValue(row, "notificationno"))
                .alertRecordId(getLongValue(row, "alertrecordid"))
                .alertNo(getStringValue(row, "alertno"))
                .alertTitle(getStringValue(row, "alerttitle"))
                .alertLevel(getStringValue(row, "alertlevel"))
                .alertStatus(getStringValue(row, "alertstatus"))
                .channelType(getStringValue(row, "channeltype"))
                .targetUserId(getLongValue(row, "targetuserid"))
                .targetUserName(getStringValue(row, "targetusername"))
                .followUpType(getStringValue(row, "followuptype"))
                .followUpContent(getStringValue(row, "followupcontent"))
                .followUpResult(getStringValue(row, "followupresult"))
                .followUpStatus(getStringValue(row, "followupstatus"))
                .receiptStatus(getStringValue(row, "receiptstatus"))
                .receiptNote(getStringValue(row, "receiptnote"))
                .createdBy(getLongValue(row, "createdby"))
                .createdByName(getStringValue(row, "createdbyname"))
                .createdAt(getOffsetDateTimeValue(row, "createdat"))
                .confirmedBy(getLongValue(row, "confirmedby"))
                .confirmedByName(getStringValue(row, "confirmedbyname"))
                .confirmedAt(getOffsetDateTimeValue(row, "confirmedat"))
                .firstFollowupForNotification(getBooleanValue(row, "firstfollowupfornotification"))
                .minutesSinceNotification(getLongValue(row, "minutessincenotification"))
                .build();
    }

    // ==================== 辅助方法 ====================

    private String getStringValue(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value != null ? value.toString() : null;
    }

    private Long getLongValue(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Double getDoubleValue(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Boolean getBooleanValue(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return Boolean.parseBoolean(value.toString());
    }

    private java.time.OffsetDateTime getOffsetDateTimeValue(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof java.time.OffsetDateTime) {
            return (java.time.OffsetDateTime) value;
        }
        if (value instanceof java.time.LocalDateTime) {
            return ((java.time.LocalDateTime) value).atOffset(java.time.ZoneOffset.UTC);
        }
        try {
            return java.time.OffsetDateTime.parse(value.toString());
        } catch (Exception e) {
            return null;
        }
    }
}

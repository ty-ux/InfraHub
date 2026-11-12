package com.infrahub.release.service;

import com.infrahub.release.dto.*;
import com.infrahub.release.mapper.GovernanceAlertNotificationFollowupMapper;
import com.infrahub.release.mapper.GovernanceAlertNotificationRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 通知跟进复盘统计服务（只读）
 * Contract: v2-stage-s-contract.md
 *
 * MANUAL_ONLY 约束（Contract Section 1.1, 15.1）：
 * - 不自动发送通知
 * - 不自动确认回执
 * - 不自动处理告警
 * - 不做 SSH/Docker/Kubernetes 调用
 * - 不改变 M/N/O/P/Q/R 阶段状态机语义
 * - 所有 API 均为 GET 只读，不产生写副作用
 * - 不新增枚举，复用既有枚举
 * - 不新增数据库表
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GovernanceAlertNotificationFollowupReviewService {

    private final GovernanceAlertNotificationFollowupMapper followupMapper;
    private final GovernanceAlertNotificationRecordMapper notificationRecordMapper;

    // ==================== S-A-01: 复盘概览 ====================

    /**
     * 复盘概览统计
     * Contract: GET /api/assets/governance-ops/alert-notification-followup-review/overview
     * Section 3.1, 5.1-5.6
     *
     * GET 只读查询，无写副作用
     */
    @Transactional(readOnly = true)
    public GovernanceAlertNotificationFollowupReviewOverviewResponse getOverview(
            GovernanceAlertFollowupReviewOverviewQuery query) {
        OffsetDateTime startTime = query.getStartTime();
        OffsetDateTime endTime = query.getEndTime();

        // 通知统计（Q 阶段数据）
        Long totalNotifications = notificationRecordMapper.countTotalNotificationRecords(startTime, endTime);

        // 跟进统计（R 阶段数据）
        Long followedUpNotifications = followupMapper.countDistinctNotificationRecords(startTime, endTime);
        Long pendingFollowupNotifications = totalNotifications - followedUpNotifications;

        Long totalFollowupRecords = followupMapper.countTotalFollowupRecords(startTime, endTime);
        Long completedFollowupRecords = followupMapper.countByFollowUpStatus("COMPLETED", startTime, endTime);
        Long inProgressFollowupRecords = followupMapper.countByFollowUpStatus("IN_PROGRESS", startTime, endTime);
        Long cancelledFollowupRecords = followupMapper.countByFollowUpStatus("CANCELLED", startTime, endTime);

        // 回执统计（R 阶段数据）
        Long pendingReceiptCount = followupMapper.countByReceiptStatus("PENDING", startTime, endTime, null, null);
        Long confirmedReceiptCount = followupMapper.countByReceiptStatus("CONFIRMED", startTime, endTime, null, null);
        Long failedReceiptCount = followupMapper.countByReceiptStatus("FAILED", startTime, endTime, null, null);
        Long noResponseCount = followupMapper.countByReceiptStatus("NO_RESPONSE", startTime, endTime, null, null);

        // 比率指标（Contract Section 5.4）
        Double followupCompletionRate = calculateRate(
                completedFollowupRecords != null ? completedFollowupRecords : 0L,
                totalFollowupRecords != null ? totalFollowupRecords : 0L, 2);
        long receiptDenominator = (confirmedReceiptCount != null ? confirmedReceiptCount : 0L)
                + (failedReceiptCount != null ? failedReceiptCount : 0L)
                + (noResponseCount != null ? noResponseCount : 0L);
        Double receiptConfirmationRate = calculateRate(
                confirmedReceiptCount != null ? confirmedReceiptCount : 0L,
                receiptDenominator, 2);

        // 时间指标（Contract Section 5.5）
        Double averageFirstFollowupMinutes = followupMapper.calculateAverageFirstFollowupMinutes(startTime, endTime);
        if (averageFirstFollowupMinutes != null) {
            averageFirstFollowupMinutes = roundToOneDecimal(averageFirstFollowupMinutes);
        }

        // 逾期统计（Contract Section 5.6）
        Long overdueFollowupCount = followupMapper.countOverdueFollowupRecords(startTime, endTime);

        GovernanceAlertNotificationFollowupReviewOverviewResponse response =
                new GovernanceAlertNotificationFollowupReviewOverviewResponse();
        response.setTotalNotifications(totalNotifications);
        response.setFollowedUpNotifications(followedUpNotifications);
        response.setPendingFollowupNotifications(pendingFollowupNotifications);
        response.setTotalFollowupRecords(totalFollowupRecords);
        response.setCompletedFollowupRecords(completedFollowupRecords);
        response.setInProgressFollowupRecords(inProgressFollowupRecords);
        response.setCancelledFollowupRecords(cancelledFollowupRecords);
        response.setPendingReceiptCount(pendingReceiptCount);
        response.setConfirmedReceiptCount(confirmedReceiptCount);
        response.setFailedReceiptCount(failedReceiptCount);
        response.setNoResponseCount(noResponseCount);
        response.setFollowupCompletionRate(followupCompletionRate);
        response.setReceiptConfirmationRate(receiptConfirmationRate);
        response.setAverageFirstFollowupMinutes(averageFirstFollowupMinutes);
        response.setOverdueFollowupCount(overdueFollowupCount);
        response.setUpdatedAt(OffsetDateTime.now());
        return response;
    }

    // ==================== S-A-02: 回执效果统计 ====================

    /**
     * 回执效果统计
     * Contract: GET /api/assets/governance-ops/alert-notification-followup-review/receipt-stats
     * Section 3.2, 5.3-5.4
     *
     * GET 只读查询，无写副作用
     */
    @Transactional(readOnly = true)
    public GovernanceAlertReceiptStatsResponse getReceiptStats(GovernanceAlertReceiptStatsQuery query) {
        OffsetDateTime startTime = query.getStartTime();
        OffsetDateTime endTime = query.getEndTime();
        String channelType = query.getChannelType();
        Long targetUserId = query.getTargetUserId();

        // 按渠道类型统计
        List<Map<String, Object>> channelStats = followupMapper.countReceiptStatsByChannelType(
                startTime, endTime, channelType, targetUserId);
        List<GovernanceAlertReceiptStatsByChannelItem> byChannelType = channelStats.stream()
                .map(row -> {
                    Long total = toLong(row.get("totalCount"));
                    Long confirmed = toLong(row.get("confirmedCount"));
                    Long failed = toLong(row.get("failedCount"));
                    Long noResponse = toLong(row.get("noResponseCount"));
                    return new GovernanceAlertReceiptStatsByChannelItem(
                            String.valueOf(row.get("channelType")),
                            total,
                            confirmed,
                            failed,
                            noResponse,
                            calculateRate(confirmed, confirmed + failed + noResponse, 2)
                    );
                })
                .collect(Collectors.toList());

        // 按目标用户统计（Top 10）
        List<Map<String, Object>> userStats = followupMapper.countReceiptStatsByTargetUser(
                startTime, endTime, channelType, targetUserId);
        List<GovernanceAlertReceiptStatsByUserItem> byTargetUser = userStats.stream()
                .map(row -> {
                    Long total = toLong(row.get("totalCount"));
                    Long confirmed = toLong(row.get("confirmedCount"));
                    Long failed = toLong(row.get("failedCount"));
                    Long noResponse = toLong(row.get("noResponseCount"));
                    return new GovernanceAlertReceiptStatsByUserItem(
                            toLong(row.get("targetUserId")),
                            String.valueOf(row.get("targetUserName")),
                            total,
                            confirmed,
                            failed,
                            noResponse,
                            calculateRate(confirmed, confirmed + failed + noResponse, 2)
                    );
                })
                .collect(Collectors.toList());

        // 整体汇总
        Map<String, Object> overallStats = followupMapper.countOverallReceiptStats(
                startTime, endTime, channelType, targetUserId);
        Long totalAll = toLong(overallStats.get("totalCount"));
        Long confirmedAll = toLong(overallStats.get("confirmedCount"));
        Long failedAll = toLong(overallStats.get("failedCount"));
        Long noResponseAll = toLong(overallStats.get("noResponseCount"));
        GovernanceAlertReceiptStatsOverall overall = new GovernanceAlertReceiptStatsOverall(
                totalAll,
                confirmedAll,
                failedAll,
                noResponseAll,
                calculateRate(confirmedAll, confirmedAll + failedAll + noResponseAll, 2)
        );

        return new GovernanceAlertReceiptStatsResponse(byChannelType, byTargetUser, overall);
    }

    // ==================== S-A-03: 跟进状态分布 ====================

    /**
     * 跟进状态分布
     * Contract: GET /api/assets/governance-ops/alert-notification-followup-review/followup-status-distribution
     * Section 3.3, 5.2-5.3
     *
     * GET 只读查询，无写副作用
     */
    @Transactional(readOnly = true)
    public GovernanceAlertFollowupStatusDistributionResponse getFollowupStatusDistribution(
            GovernanceAlertFollowupStatusDistributionQuery query) {
        OffsetDateTime startTime = query.getStartTime();
        OffsetDateTime endTime = query.getEndTime();
        Long alertRecordId = query.getAlertRecordId();
        Long notificationRecordId = query.getNotificationRecordId();

        // 按跟进状态分组统计
        List<Map<String, Object>> followUpStatusGroups = followupMapper.countByFollowUpStatusGroup(
                startTime, endTime, alertRecordId, notificationRecordId);
        long followUpTotal = followUpStatusGroups.stream()
                .mapToLong(row -> toLong(row.get("count")))
                .sum();
        List<GovernanceAlertFollowupStatusItem> byFollowUpStatus = followUpStatusGroups.stream()
                .map(row -> {
                    long count = toLong(row.get("count"));
                    return new GovernanceAlertFollowupStatusItem(
                            String.valueOf(row.get("followUpStatus")),
                            count,
                            calculateRate(count, followUpTotal, 2)
                    );
                })
                .collect(Collectors.toList());

        // 按回执状态分组统计
        List<Map<String, Object>> receiptStatusGroups = followupMapper.countByReceiptStatusGroup(
                startTime, endTime, alertRecordId, notificationRecordId);
        long receiptTotal = receiptStatusGroups.stream()
                .mapToLong(row -> toLong(row.get("count")))
                .sum();
        List<GovernanceAlertReceiptStatusItem> byReceiptStatus = receiptStatusGroups.stream()
                .map(row -> {
                    long count = toLong(row.get("count"));
                    return new GovernanceAlertReceiptStatusItem(
                            String.valueOf(row.get("receiptStatus")),
                            count,
                            calculateRate(count, receiptTotal, 2)
                    );
                })
                .collect(Collectors.toList());

        // 按跟进类型分组统计
        List<Map<String, Object>> followUpTypeGroups = followupMapper.countByFollowUpTypeGroup(
                startTime, endTime, alertRecordId, notificationRecordId);
        long typeTotal = followUpTypeGroups.stream()
                .mapToLong(row -> toLong(row.get("count")))
                .sum();
        List<GovernanceAlertFollowupTypeItem> byFollowUpType = followUpTypeGroups.stream()
                .map(row -> {
                    long count = toLong(row.get("count"));
                    return new GovernanceAlertFollowupTypeItem(
                            String.valueOf(row.get("followUpType")),
                            count,
                            calculateRate(count, typeTotal, 2)
                    );
                })
                .collect(Collectors.toList());

        return new GovernanceAlertFollowupStatusDistributionResponse(
                byFollowUpStatus, byReceiptStatus, byFollowUpType);
    }

    // ==================== 私有方法 ====================

    /**
     * 计算百分比比率
     * Contract Section 5.4: 保留 scale 位小数
     */
    private Double calculateRate(long numerator, long denominator, int scale) {
        if (denominator == 0) {
            return 0.0;
        }
        BigDecimal num = BigDecimal.valueOf(numerator);
        BigDecimal den = BigDecimal.valueOf(denominator);
        return num.multiply(BigDecimal.valueOf(100))
                .divide(den, scale, RoundingMode.HALF_UP)
                .doubleValue();
    }

    /**
     * 保留 1 位小数
     * Contract Section 5.5: averageFirstFollowupMinutes 保留 1 位小数
     */
    private Double roundToOneDecimal(Double value) {
        if (value == null) {
            return null;
        }
        return BigDecimal.valueOf(value)
                .setScale(1, RoundingMode.HALF_UP)
                .doubleValue();
    }

    /**
     * 将数据库查询结果转为 Long
     */
    private Long toLong(Object value) {
        if (value == null) {
            return 0L;
        }
        if (value instanceof Long) {
            return (Long) value;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}

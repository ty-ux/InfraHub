package com.infrahub.release.controller;

import com.infrahub.common.api.ApiResponse;
import com.infrahub.common.support.TraceIdHolder;
import com.infrahub.release.dto.*;
import com.infrahub.release.service.GovernanceAlertNotificationFollowupReviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * 通知跟进复盘统计 API 控制器（只读）
 * Contract: V2/S Section 2.1-2.3
 *
 * API 路径冻结（Contract section 2.1-2.3）：
 * - GET /api/assets/governance-ops/alert-notification-followup-review/overview - 复盘概览统计
 * - GET /api/assets/governance-ops/alert-notification-followup-review/receipt-stats - 回执效果统计
 * - GET /api/assets/governance-ops/alert-notification-followup-review/followup-status-distribution - 跟进状态分布
 *
 * MANUAL_ONLY 约束（Contract section 1.1, 15.1）：
 * - 所有 API 均为 GET 只读，不产生写副作用
 * - 不自动发送通知
 * - 不自动确认回执
 * - 不自动处理告警
 * - 不做 SSH/Docker/Kubernetes 调用
 * - 不改变 M/N/O/P/Q/R 阶段状态机语义
 *
 * 与 Q/R 阶段 API 关系：
 * - Q 阶段 /alert-notifications：通知草稿、人工确认、记录查询（有 POST 写操作）
 * - R 阶段 /alert-notification-followups：跟进记录、回执确认（有 POST 写操作）
 * - S 阶段 /alert-notification-followup-review：复盘统计（仅 GET 只读）
 */
@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/api/assets/governance-ops/alert-notification-followup-review")
public class GovernanceAlertNotificationFollowupReviewController {

    private final GovernanceAlertNotificationFollowupReviewService reviewService;

    // ==================== US-S-A-01: 复盘概览 ====================

    /**
     * 复盘概览统计
     * Contract: GET /api/assets/governance-ops/alert-notification-followup-review/overview
     * Section 2.1, 3.1
     *
     * GET 只读查询，无写副作用
     *
     * @param query 查询参数
     * @return 复盘概览响应
     */
    @GetMapping("/overview")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<GovernanceAlertNotificationFollowupReviewOverviewResponse> getOverview(
            GovernanceAlertFollowupReviewOverviewQuery query) {
        GovernanceAlertNotificationFollowupReviewOverviewResponse response = reviewService.getOverview(query);
        return ApiResponse.success(response, TraceIdHolder.get());
    }

    // ==================== US-S-A-02: 回执效果统计 ====================

    /**
     * 回执效果统计
     * Contract: GET /api/assets/governance-ops/alert-notification-followup-review/receipt-stats
     * Section 2.2, 3.2
     *
     * GET 只读查询，无写副作用
     *
     * @param query 查询参数
     * @return 回执效果统计响应
     */
    @GetMapping("/receipt-stats")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<GovernanceAlertReceiptStatsResponse> getReceiptStats(
            GovernanceAlertReceiptStatsQuery query) {
        GovernanceAlertReceiptStatsResponse response = reviewService.getReceiptStats(query);
        return ApiResponse.success(response, TraceIdHolder.get());
    }

    // ==================== US-S-A-03: 跟进状态分布 ====================

    /**
     * 跟进状态分布
     * Contract: GET /api/assets/governance-ops/alert-notification-followup-review/followup-status-distribution
     * Section 2.3, 3.3
     *
     * GET 只读查询，无写副作用
     *
     * @param query 查询参数
     * @return 跟进状态分布响应
     */
    @GetMapping("/followup-status-distribution")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<GovernanceAlertFollowupStatusDistributionResponse> getFollowupStatusDistribution(
            GovernanceAlertFollowupStatusDistributionQuery query) {
        GovernanceAlertFollowupStatusDistributionResponse response = reviewService.getFollowupStatusDistribution(query);
        return ApiResponse.success(response, TraceIdHolder.get());
    }
}

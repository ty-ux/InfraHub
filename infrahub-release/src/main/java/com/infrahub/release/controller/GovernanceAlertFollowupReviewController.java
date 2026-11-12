package com.infrahub.release.controller;

import com.infrahub.common.api.ApiResponse;
import com.infrahub.common.support.TraceIdHolder;
import com.infrahub.release.dto.GovernanceAlertFollowupByAlertPageResponse;
import com.infrahub.release.dto.GovernanceAlertFollowupByAlertQuery;
import com.infrahub.release.dto.GovernanceAlertFollowupByHandlerPageResponse;
import com.infrahub.release.dto.GovernanceAlertFollowupByHandlerQuery;
import com.infrahub.release.dto.GovernanceAlertFollowupByNotificationPageResponse;
import com.infrahub.release.dto.GovernanceAlertFollowupByNotificationQuery;
import com.infrahub.release.dto.GovernanceAlertFollowupReviewDetailPageResponse;
import com.infrahub.release.dto.GovernanceAlertFollowupReviewDetailQuery;
import com.infrahub.release.service.GovernanceAlertFollowupReviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 告警通知跟进复盘分析 API 控制器.
 * Contract: V2/S Section 2.4-2.7.
 *
 * API 路径冻结（Contract Section 2.4-2.7）：
 * - GET /api/assets/governance-ops/alert-notification-followup-review/by-handler - 按负责人维度闭环分析
 * - GET /api/assets/governance-ops/alert-notification-followup-review/by-alert - 按告警维度闭环分析
 * - GET /api/assets/governance-ops/alert-notification-followup-review/by-notification - 按通知维度闭环分析
 * - GET /api/assets/governance-ops/alert-notification-followup-review/details - 复盘明细列表查询
 *
 * MANUAL_ONLY 约束（Contract Section 1.1, 15.1）：
 * - 所有 API 均为 GET 只读，不产生写副作用
 * - 不自动发送通知、不自动确认回执、不自动处理告警
 * - 不做 SSH/Docker/Kubernetes 调用
 * - 不改变 M/N/O/P/Q/R 阶段状态机语义
 *
 * 分页参数：pageNum/pageSize，pageSize 上限 200
 */
@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/api/assets/governance-ops/alert-notification-followup-review")
public class GovernanceAlertFollowupReviewController {

    private final GovernanceAlertFollowupReviewService reviewService;

    /**
     * 按负责人维度闭环分析.
     * Contract: GET /api/assets/governance-ops/alert-notification-followup-review/by-handler
     *
     * @param query 查询参数
     * @return 分页响应
     */
    @GetMapping("/by-handler")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<GovernanceAlertFollowupByHandlerPageResponse> queryByHandler(
            GovernanceAlertFollowupByHandlerQuery query) {
        GovernanceAlertFollowupByHandlerPageResponse response = reviewService.queryByHandler(query);
        return ApiResponse.success(response, TraceIdHolder.get());
    }

    /**
     * 按告警维度闭环分析.
     * Contract: GET /api/assets/governance-ops/alert-notification-followup-review/by-alert
     *
     * @param query 查询参数
     * @return 分页响应
     */
    @GetMapping("/by-alert")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<GovernanceAlertFollowupByAlertPageResponse> queryByAlert(
            GovernanceAlertFollowupByAlertQuery query) {
        GovernanceAlertFollowupByAlertPageResponse response = reviewService.queryByAlert(query);
        return ApiResponse.success(response, TraceIdHolder.get());
    }

    /**
     * 按通知维度闭环分析.
     * Contract: GET /api/assets/governance-ops/alert-notification-followup-review/by-notification
     *
     * @param query 查询参数
     * @return 分页响应
     */
    @GetMapping("/by-notification")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<GovernanceAlertFollowupByNotificationPageResponse> queryByNotification(
            GovernanceAlertFollowupByNotificationQuery query) {
        GovernanceAlertFollowupByNotificationPageResponse response = reviewService.queryByNotification(query);
        return ApiResponse.success(response, TraceIdHolder.get());
    }

    /**
     * 复盘明细列表查询.
     * Contract: GET /api/assets/governance-ops/alert-notification-followup-review/details
     *
     * @param query 查询参数
     * @return 分页响应
     */
    @GetMapping("/details")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<GovernanceAlertFollowupReviewDetailPageResponse> queryDetails(
            GovernanceAlertFollowupReviewDetailQuery query) {
        GovernanceAlertFollowupReviewDetailPageResponse response = reviewService.queryDetails(query);
        return ApiResponse.success(response, TraceIdHolder.get());
    }
}

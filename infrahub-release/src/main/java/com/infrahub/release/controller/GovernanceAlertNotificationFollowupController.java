package com.infrahub.release.controller;

import com.infrahub.common.api.ApiResponse;
import com.infrahub.common.support.TraceIdHolder;
import com.infrahub.release.dto.*;
import com.infrahub.release.service.GovernanceAlertNotificationFollowupService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * 告警通知跟进 API 控制器.
 * Contract: V2/R Section 2.
 *
 * API 路径冻结（Contract section 2.1-2.4）：
 * - POST /api/assets/governance-ops/alert-notification-followups - 创建跟进记录
 * - POST /api/assets/governance-ops/alert-notification-followups/{id}/confirm-receipt - 确认回执
 * - GET /api/assets/governance-ops/alert-notification-followups - 分页查询跟进记录
 * - GET /api/assets/governance-ops/alert-notification-followups/{id} - 查询单条跟进记录
 * - GET /api/assets/governance-ops/alert-notification-followups/by-notification/{notificationId} - 按通知查询跟进历史
 *
 * MANUAL_ONLY 约束（Contract section 1.1, 14.1）：
 * - 不自动发送通知
 * - 不自动确认回执
 * - 不自动处理告警
 * - 不做 SSH/Docker/Kubernetes 调用
 * - 不改变 M/N/O/P/Q 阶段状态机语义
 *
 * 与 Q 阶段 API 关系：
 * - Q 阶段 /alert-notifications：通知草稿、人工确认、记录查询
 * - R 阶段 /alert-notification-followups：跟进记录、回执确认、跟进历史
 */
@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/api/assets/governance-ops")
public class GovernanceAlertNotificationFollowupController {

    private final GovernanceAlertNotificationFollowupService followupService;

    // ==================== US-R-A-01: 创建跟进记录 ====================

    /**
     * 创建跟进记录.
     * Contract: POST /api/assets/governance-ops/alert-notification-followups
     *
     * 此接口产生写副作用，创建跟进记录，生成治理证据链事件。
     * MANUAL_ONLY: 显式手动触发，不做自动跟进
     *
     * @param request 跟进请求
     * @return 跟进记录响应
     */
    @PostMapping("/alert-notification-followups")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<GovernanceAlertNotificationFollowupResponse> createFollowup(
            @Valid @RequestBody GovernanceAlertNotificationFollowupRequest request) {
        GovernanceAlertNotificationFollowupResponse response = followupService.createFollowup(request);
        return ApiResponse.success(response, TraceIdHolder.get());
    }

    // ==================== US-R-A-02: 确认回执 ====================

    /**
     * 确认回执.
     * Contract: POST /api/assets/governance-ops/alert-notification-followups/{id}/confirm-receipt
     *
     * 此接口产生写副作用，更新跟进记录的回执状态，生成治理证据链事件。
     * MANUAL_ONLY: 显式手动触发，不做自动确认
     *
     * @param id 跟进记录ID
     * @param request 回执确认请求
     * @return 跟进记录响应
     */
    @PostMapping("/alert-notification-followups/{id}/confirm-receipt")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<GovernanceAlertNotificationFollowupResponse> confirmReceipt(
            @PathVariable Long id,
            @Valid @RequestBody GovernanceAlertReceiptConfirmRequest request) {
        GovernanceAlertNotificationFollowupResponse response = followupService.confirmReceipt(id, request);
        return ApiResponse.success(response, TraceIdHolder.get());
    }

    // ==================== US-R-A-03: 跟进记录查询 ====================

    /**
     * 分页查询跟进记录.
     * Contract: GET /api/assets/governance-ops/alert-notification-followups
     *
     * GET 查询无写副作用
     * pageSize 上限 200
     *
     * @param query 查询参数
     * @return 跟进记录分页响应
     */
    @GetMapping("/alert-notification-followups")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<GovernanceAlertNotificationFollowupPageResponse> queryFollowupRecords(
            GovernanceAlertNotificationFollowupQuery query) {
        GovernanceAlertNotificationFollowupPageResponse response = followupService.queryFollowupRecords(query);
        return ApiResponse.success(response, TraceIdHolder.get());
    }

    /**
     * 查询单条跟进记录详情.
     * Contract: GET /api/assets/governance-ops/alert-notification-followups/{id}
     *
     * GET 查询无写副作用
     *
     * @param id 跟进记录ID
     * @return 跟进记录响应
     */
    @GetMapping("/alert-notification-followups/{id}")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<GovernanceAlertNotificationFollowupResponse> getFollowupDetail(
            @PathVariable Long id) {
        GovernanceAlertNotificationFollowupResponse response = followupService.getFollowupDetail(id);
        return ApiResponse.success(response, TraceIdHolder.get());
    }

    /**
     * 按通知记录查询跟进历史.
     * Contract: GET /api/assets/governance-ops/alert-notification-followups/by-notification/{notificationId}
     *
     * GET 查询无写副作用
     * pageSize 上限 200
     *
     * @param notificationId 通知记录ID
     * @param pageNum 页码（默认 1）
     * @param pageSize 每页数量（默认 20，上限 200）
     * @return 跟进记录分页响应
     */
    @GetMapping("/alert-notification-followups/by-notification/{notificationId}")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<GovernanceAlertNotificationFollowupPageResponse> queryByNotificationId(
            @PathVariable Long notificationId,
            @RequestParam(required = false) Integer pageNum,
            @RequestParam(required = false) Integer pageSize) {
        GovernanceAlertNotificationFollowupPageResponse response = followupService.queryByNotificationId(
                notificationId, pageNum, pageSize);
        return ApiResponse.success(response, TraceIdHolder.get());
    }
}

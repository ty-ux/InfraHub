package com.infrahub.release.controller;

import com.infrahub.common.api.ApiResponse;
import com.infrahub.common.support.TraceIdHolder;
import com.infrahub.release.dto.*;
import com.infrahub.release.service.GovernanceAlertNotificationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * 告警通知 API 控制器.
 * Contract: V2/Q Section 2.
 *
 * API 路径冻结（Contract section 2.1-2.4）：
 * - GET /api/assets/governance-ops/alert-notifications/drafts - 通知草稿预览
 * - POST /api/assets/governance-ops/alert-notifications/manual-confirm - 人工确认已提醒
 * - GET /api/assets/governance-ops/alert-notifications/records - 通知记录查询（分页）
 * - GET /api/assets/governance-ops/alert-notifications/records/{id} - 通知详情查询
 *
 * MANUAL_ONLY 约束（Contract section 1.1-1.3）：
 * - 不调用真实 SMTP/Webhook/短信/企业微信/钉钉/飞书发送器
 * - 不自动发送通知
 * - 不自动分派通知人
 * - 不自动升级通知
 * - 不做定时任务自动提醒
 * - 不改变告警记录状态机、处理状态机、分派/升级状态机
 *
 * 与 P 阶段 API 关系：
 * - P 阶段 /alert-workbench：工作台只读视图、统计摘要
 * - Q 阶段 /alert-notifications：通知草稿、人工确认、记录查询
 */
@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/api/assets/governance-ops")
public class GovernanceAlertNotificationController {

    private final GovernanceAlertNotificationService notificationService;

    // ==================== US-009: 通知草稿预览 ====================

    /**
     * 生成通知草稿预览.
     * Contract: GET /api/assets/governance-ops/alert-notifications/drafts
     *
     * 此接口为只读查询，不产生写副作用，用于预览通知内容。
     *
     * @param alertRecordId 告警记录ID（必填）
     * @param channelType 通知渠道类型（默认 INTERNAL）
     * @param targetUserId 目标用户ID（可选）
     * @param targetUserName 目标用户姓名（可选）
     * @return 通知草稿响应
     */
    @GetMapping("/alert-notifications/drafts")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<GovernanceAlertNotificationDraftResponse> generateDraft(
            @RequestParam Long alertRecordId,
            @RequestParam(required = false) String channelType,
            @RequestParam(required = false) Long targetUserId,
            @RequestParam(required = false) String targetUserName) {
        GovernanceAlertNotificationDraftResponse response = notificationService.generateDraft(
                alertRecordId, channelType, targetUserId, targetUserName);
        return ApiResponse.success(response, TraceIdHolder.get());
    }

    // ==================== US-010: 人工确认已提醒 ====================

    /**
     * 人工确认已提醒.
     * Contract: POST /api/assets/governance-ops/alert-notifications/manual-confirm
     *
     * 此接口产生写副作用，创建通知记录，生成治理证据链事件。
     * MANUAL_ONLY: 显式手动触发，不做自动通知
     *
     * @param request 人工确认请求
     * @return 通知记录响应
     */
    @PostMapping("/alert-notifications/manual-confirm")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<GovernanceAlertNotificationRecordResponse> manualConfirm(
            @Valid @RequestBody GovernanceAlertManualNotificationRequest request) {
        GovernanceAlertNotificationRecordResponse response = notificationService.manualConfirm(request);
        return ApiResponse.success(response, TraceIdHolder.get());
    }

    // ==================== US-011: 通知记录查询 ====================

    /**
     * 分页查询通知记录.
     * Contract: GET /api/assets/governance-ops/alert-notifications/records
     *
     * GET 查询无写副作用
     * pageSize 上限 200
     *
     * @param query 查询参数
     * @return 通知记录分页响应
     */
    @GetMapping("/alert-notifications/records")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<GovernanceAlertNotificationRecordPageResponse> getNotificationRecords(
            GovernanceAlertNotificationRecordQuery query) {
        GovernanceAlertNotificationRecordPageResponse response = notificationService.queryNotificationRecords(query);
        return ApiResponse.success(response, TraceIdHolder.get());
    }

    /**
     * 查询单条通知记录详情.
     * Contract: GET /api/assets/governance-ops/alert-notifications/records/{id}
     *
     * GET 查询无写副作用
     *
     * @param id 通知记录ID
     * @return 通知记录响应
     */
    @GetMapping("/alert-notifications/records/{id}")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<GovernanceAlertNotificationRecordResponse> getNotificationRecordDetail(
            @PathVariable Long id) {
        GovernanceAlertNotificationRecordResponse response = notificationService.getNotificationRecordDetail(id);
        return ApiResponse.success(response, TraceIdHolder.get());
    }
}
package com.infrahub.release.controller;

import com.infrahub.common.api.ApiResponse;
import com.infrahub.common.support.TraceIdHolder;
import com.infrahub.release.dto.*;
import com.infrahub.release.service.GovernanceAlertAssignmentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * 告警分派 API 控制器.
 * Contract: V2/O Section 2.1-2.4.
 *
 * API 路径冻结（Contract section 2.1-2.4）：
 * - POST /api/assets/governance-ops/alerts/{id}/assign - 分派告警责任人
 * - POST /api/assets/governance-ops/alerts/{id}/reassign - 转派告警责任人
 * - POST /api/assets/governance-ops/alerts/{id}/unassign - 取消告警分派
 * - GET /api/assets/governance-ops/alerts/{id}/assignment-records - 分派历史
 * - GET /api/assets/governance-ops/alerts/my-assigned - 当前用户待处理告警
 *
 * MANUAL_ONLY 约束（Contract section 1.1-1.3）：
 * - 分派/转派/取消分派为人工触发，不自动执行
 * - 不调用 SSH/Docker/Kubernetes 执行器
 * - 不自动扩容/清理/发布
 * - 不自动发送通知
 * - 不改变 M/N 阶段定义的告警状态机语义
 */
@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/api/assets/governance-ops")
public class GovernanceAlertAssignmentController {

    private final GovernanceAlertAssignmentService assignmentService;

    // ==================== 告警分派 API ====================

    /**
     * 分派告警责任人.
     * Contract: POST /api/assets/governance-ops/alerts/{id}/assign
     *
     * MANUAL_ONLY: 显式手动触发，不做自动分派
     *
     * @param id 告警 ID
     * @param request 分派请求
     * @return 更新后的告警响应
     */
    @PostMapping("/alerts/{id}/assign")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<GovernanceAlertRecordResponse> assignAlert(
            @PathVariable Long id,
            @Valid @RequestBody GovernanceAlertAssignRequest request) {
        GovernanceAlertRecordResponse response = assignmentService.assign(id, request);
        return ApiResponse.success(response, TraceIdHolder.get());
    }

    /**
     * 转派告警责任人.
     * Contract: POST /api/assets/governance-ops/alerts/{id}/reassign
     *
     * MANUAL_ONLY: 显式手动触发，不做自动转派
     *
     * @param id 告警 ID
     * @param request 转派请求
     * @return 更新后的告警响应
     */
    @PostMapping("/alerts/{id}/reassign")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<GovernanceAlertRecordResponse> reassignAlert(
            @PathVariable Long id,
            @Valid @RequestBody GovernanceAlertReassignRequest request) {
        GovernanceAlertRecordResponse response = assignmentService.reassign(id, request);
        return ApiResponse.success(response, TraceIdHolder.get());
    }

    /**
     * 取消告警分派.
     * Contract: POST /api/assets/governance-ops/alerts/{id}/unassign
     *
     * MANUAL_ONLY: 显式手动触发，不做自动取消分派
     *
     * @param id 告警 ID
     * @param request 取消分派请求（可选）
     * @return 更新后的告警响应
     */
    @PostMapping("/alerts/{id}/unassign")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<GovernanceAlertRecordResponse> unassignAlert(
            @PathVariable Long id,
            @RequestBody(required = false) GovernanceAlertUnassignRequest request) {
        GovernanceAlertRecordResponse response = assignmentService.unassign(id, request);
        return ApiResponse.success(response, TraceIdHolder.get());
    }

    /**
     * 查询告警分派历史.
     * Contract: GET /api/assets/governance-ops/alerts/{id}/assignment-records
     *
     * GET 查询无写副作用
     *
     * @param id 告警 ID
     * @param pageNum 页码（默认 1）
     * @param pageSize 每页数量（默认 20）
     * @return 分派历史分页响应
     */
    @GetMapping("/alerts/{id}/assignment-records")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<GovernanceAlertAssignmentRecordPageResponse> getAssignmentRecords(
            @PathVariable Long id,
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "20") Integer pageSize) {
        GovernanceAlertAssignmentRecordPageResponse response = assignmentService.getAssignmentRecords(id, pageNum, pageSize);
        return ApiResponse.success(response, TraceIdHolder.get());
    }

    /**
     * 查询当前用户待处理告警.
     * Contract: GET /api/assets/governance-ops/alerts/my-assigned
     *
     * GET 查询无写副作用
     *
     * @param operatorId 操作人ID（必填）
     * @param pageNum 页码（默认 1）
     * @param pageSize 每页数量（默认 20）
     * @return 告警记录分页响应
     */
    @GetMapping("/alerts/my-assigned")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<GovernanceAlertRecordPageResponse> getMyAssigned(
            @RequestParam Long operatorId,
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "20") Integer pageSize) {
        GovernanceAlertRecordPageResponse response = assignmentService.getMyAssigned(operatorId, pageNum, pageSize);
        return ApiResponse.success(response, TraceIdHolder.get());
    }
}

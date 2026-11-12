package com.infrahub.release.controller;

import com.infrahub.common.api.ApiResponse;
import com.infrahub.common.context.UserContext;
import com.infrahub.common.context.UserContextHolder;
import com.infrahub.common.support.TraceIdHolder;
import com.infrahub.release.dto.*;
import com.infrahub.release.service.GovernanceAlertWorkbenchService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * 告警协同工作台 API 控制器 - P 阶段实现
 * Contract: v2-stage-p-contract.md Section 2
 *
 * API 路径冻结（Contract section 2.1-2.4）：
 * - GET /api/assets/governance-ops/alert-workbench/summary - 工作台概览统计
 * - GET /api/assets/governance-ops/alert-workbench/my-assigned - 当前用户待处理告警
 * - GET /api/assets/governance-ops/alert-workbench/overdue - 逾期告警列表
 * - GET /api/assets/governance-ops/alert-workbench/escalated - 已升级告警列表
 *
 * MANUAL_ONLY 约束（Contract section 1.1-1.3）：
 * - 所有 API 均为只读 GET 查询，不产生写副作用
 * - 不调用 SSH/Docker/Kubernetes 执行器
 * - 不自动扩容/清理/发布
 * - 不创建发布单
 * - 不自动分派/升级/ack/resolve/reopen
 * - 不改变 M/N/O 状态机语义
 *
 * 逾期口径（Contract section 6.2）：
 * - assignmentDueAt < now 且 alertStatus != RESOLVED
 */
@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/api/assets/governance-ops/alert-workbench")
public class GovernanceAlertWorkbenchController {

    private final GovernanceAlertWorkbenchService workbenchService;

    /**
     * 工作台概览统计
     * Contract: GET /api/assets/governance-ops/alert-workbench/summary
     *
     * GET 查询无写副作用
     *
     * @return 工作台概览响应
     */
    @GetMapping("/summary")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<GovernanceAlertWorkbenchSummaryResponse> getSummary() {
        Long currentUserId = getCurrentUserId();
        GovernanceAlertWorkbenchSummaryResponse response = workbenchService.getSummary(currentUserId);
        return ApiResponse.success(response, TraceIdHolder.get());
    }

    /**
     * 我的待办告警列表
     * Contract: GET /api/assets/governance-ops/alert-workbench/my-assigned
     *
     * GET 查询无写副作用
     * 语义：assigneeId = 当前登录用户的告警列表
     *
     * @param query 查询参数
     * @return 告警记录分页响应
     */
    @GetMapping("/my-assigned")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<GovernanceAlertRecordPageResponse> getMyAssigned(@Valid GovernanceAlertMyAssignedQuery query) {
        Long currentUserId = getCurrentUserId();
        GovernanceAlertRecordPageResponse response = workbenchService.getMyAssigned(currentUserId, query);
        return ApiResponse.success(response, TraceIdHolder.get());
    }

    /**
     * 逾期告警列表
     * Contract: GET /api/assets/governance-ops/alert-workbench/overdue
     *
     * GET 查询无写副作用
     * 逾期定义：assignmentDueAt < now 且 alertStatus != RESOLVED
     *
     * @param query 查询参数
     * @return 告警记录分页响应
     */
    @GetMapping("/overdue")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<GovernanceAlertRecordPageResponse> getOverdueAlerts(@Valid GovernanceAlertOverdueQuery query) {
        GovernanceAlertRecordPageResponse response = workbenchService.getOverdueAlerts(query);
        return ApiResponse.success(response, TraceIdHolder.get());
    }

    /**
     * 已升级告警列表
     * Contract: GET /api/assets/governance-ops/alert-workbench/escalated
     *
     * GET 查询无写副作用
     *
     * @param query 查询参数
     * @return 告警记录分页响应
     */
    @GetMapping("/escalated")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<GovernanceAlertRecordPageResponse> getEscalatedAlerts(@Valid GovernanceAlertEscalatedQuery query) {
        GovernanceAlertRecordPageResponse response = workbenchService.getEscalatedAlerts(query);
        return ApiResponse.success(response, TraceIdHolder.get());
    }

    /**
     * 获取当前登录用户 ID
     * Contract: v2-stage-p-contract.md Section 4.3 - assigneeId = 当前登录用户
     */
    private Long getCurrentUserId() {
        UserContext userContext = UserContextHolder.get();
        if (userContext != null && userContext.getUserId() != null) {
            return userContext.getUserId();
        }
        return null;
    }
}
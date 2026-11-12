package com.infrahub.release.controller;

import com.infrahub.common.api.ApiResponse;
import com.infrahub.common.support.TraceIdHolder;
import com.infrahub.release.dto.*;
import com.infrahub.release.service.GovernanceAlertEvaluationService;
import com.infrahub.release.service.GovernanceAlertEscalationService;
import com.infrahub.release.service.GovernanceAlertHandlingService;
import com.infrahub.release.service.GovernanceAlertRecordService;
import com.infrahub.release.service.GovernanceAlertRuleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * 治理运营告警 API 控制器.
 * Contract: V2/M Section 2 + V2/N Section 2.
 *
 * API 路径冻结（Contract section 2.1-2.2）：
 * - GET /api/assets/governance-ops/alert-rules - 告警规则列表
 * - POST /api/assets/governance-ops/alert-rules - 创建告警规则
 * - PUT /api/assets/governance-ops/alert-rules/{id} - 更新告警规则
 * - POST /api/assets/governance-ops/alert-rules/{id}/toggle - 启用/禁用告警规则
 * - POST /api/assets/governance-ops/alerts/evaluate - 手动触发告警评估
 * - GET /api/assets/governance-ops/alerts - 告警记录列表查询
 *
 * N 阶段新增 API（Contract section 2.1-2.3）：
 * - POST /api/assets/governance-ops/alerts/{id}/ack - 确认告警
 * - POST /api/assets/governance-ops/alerts/{id}/resolve - 解决告警
 * - POST /api/assets/governance-ops/alerts/{id}/reopen - 重开告警
 * - GET /api/assets/governance-ops/alerts/{id} - 告警详情
 * - GET /api/assets/governance-ops/alerts/{id}/handling-records - 处理历史
 *
 * O 阶段新增 API（Contract section 2.2-2.4）：
 * - POST /api/assets/governance-ops/alerts/{id}/escalate - 人工升级告警
 * - GET /api/assets/governance-ops/alerts/{id}/escalation-records - 升级历史
 * - GET /api/assets/governance-ops/alerts/escalated - 已升级告警列表
 *
 * MANUAL_ONLY 约束（Contract section 1.1-1.3）：
 * - evaluate 为显式 POST 手动触发，不是自动定时任务
 * - ack/resolve/reopen 为人工处理，不触发自动执行
 * - 不调用 SSH/Docker/Kubernetes 执行器
 * - 不自动扩容/清理/发布
 */
@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/api/assets/governance-ops")
public class GovernanceAlertController {

    private final GovernanceAlertRuleService ruleService;
    private final GovernanceAlertEvaluationService evaluationService;
    private final GovernanceAlertRecordService recordService;
    private final GovernanceAlertHandlingService handlingService;
    private final GovernanceAlertEscalationService escalationService;

    // ==================== 告警规则 API ====================

    /**
     * 告警规则列表（分页）.
     * Contract: GET /api/assets/governance-ops/alert-rules
     *
     * @param query 查询参数
     * @return 规则分页响应
     */
    @GetMapping("/alert-rules")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<GovernanceAlertRulePageResponse> getAlertRules(GovernanceAlertRuleQuery query) {
        GovernanceAlertRulePageResponse response = ruleService.queryRulesPage(query);
        return ApiResponse.success(response, TraceIdHolder.get());
    }

    /**
     * 创建告警规则.
     * Contract: POST /api/assets/governance-ops/alert-rules
     *
     * @param request 规则请求
     * @return 创建的规则响应
     */
    @PostMapping("/alert-rules")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<GovernanceAlertRuleResponse> createAlertRule(@Valid @RequestBody GovernanceAlertRuleRequest request) {
        GovernanceAlertRuleResponse response = ruleService.createRule(request);
        return ApiResponse.success(response, TraceIdHolder.get());
    }

    /**
     * 更新告警规则.
     * Contract: PUT /api/assets/governance-ops/alert-rules/{id}
     *
     * @param id 规则 ID
     * @param request 规则请求
     * @return 更新后的规则响应
     */
    @PutMapping("/alert-rules/{id}")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<GovernanceAlertRuleResponse> updateAlertRule(
            @PathVariable Long id,
            @Valid @RequestBody GovernanceAlertRuleRequest request) {
        GovernanceAlertRuleResponse response = ruleService.updateRule(id, request);
        return ApiResponse.success(response, TraceIdHolder.get());
    }

    /**
     * 启用/禁用告警规则.
     * Contract: POST /api/assets/governance-ops/alert-rules/{id}/toggle?enabled=
     *
     * @param id 规则 ID
     * @param enabled 是否启用
     * @return 更新后的规则响应
     */
    @PostMapping("/alert-rules/{id}/toggle")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<GovernanceAlertRuleResponse> toggleAlertRule(
            @PathVariable Long id,
            @RequestParam Boolean enabled) {
        GovernanceAlertRuleResponse response = ruleService.toggleRule(id, enabled);
        return ApiResponse.success(response, TraceIdHolder.get());
    }

    // ==================== 告警评估与记录 API ====================

    /**
     * 手动触发告警评估.
     * Contract: POST /api/assets/governance-ops/alerts/evaluate
     *
     * MANUAL_ONLY: 显式手动触发，不做自动定时任务
     *
     * @param envId 环境 ID（可选，null 表示全局）
     * @param resourcePoolId 资源池 ID（可选，null 表示全局）
     * @param serviceId 服务 ID（可选，null 表示全局）
     * @return 评估响应
     */
    @PostMapping("/alerts/evaluate")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<GovernanceAlertEvaluationResponse> evaluateAlerts(
            @RequestParam(required = false) Long envId,
            @RequestParam(required = false) Long resourcePoolId,
            @RequestParam(required = false) Long serviceId) {
        GovernanceAlertEvaluationResponse response = evaluationService.evaluate(envId, resourcePoolId, serviceId);
        return ApiResponse.success(response, TraceIdHolder.get());
    }

    /**
     * 告警记录列表查询.
     * Contract: GET /api/assets/governance-ops/alerts
     *
     * GET 查询无写副作用（Contract section 6.4）
     *
     * @param query 查询参数
     * @return 分页响应
     */
    @GetMapping("/alerts")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<GovernanceAlertRecordPageResponse> getAlerts(@Valid GovernanceAlertRecordQuery query) {
        GovernanceAlertRecordPageResponse response = recordService.queryAlertRecords(query);
        return ApiResponse.success(response, TraceIdHolder.get());
    }

    // ==================== N 阶段新增：告警处理 API ====================

    /**
     * 确认告警.
     * Contract: POST /api/assets/governance-ops/alerts/{id}/ack
     *
     * 状态转换: OPEN -> ACKED
     * MANUAL_ONLY: 不调用执行器，仅记录状态变更
     *
     * @param id 告警 ID
     * @param request 处理请求
     * @return 更新后的告警响应
     */
    @PostMapping("/alerts/{id}/ack")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<GovernanceAlertRecordResponse> ackAlert(
            @PathVariable Long id,
            @RequestBody(required = false) GovernanceAlertHandleRequest request) {
        GovernanceAlertRecordResponse response = handlingService.ack(id, request);
        return ApiResponse.success(response, TraceIdHolder.get());
    }

    /**
     * 解决告警.
     * Contract: POST /api/assets/governance-ops/alerts/{id}/resolve
     *
     * 状态转换: OPEN -> RESOLVED 或 ACKED -> RESOLVED
     * MANUAL_ONLY: 不调用执行器，仅记录状态变更
     *
     * @param id 告警 ID
     * @param request 处理请求
     * @return 更新后的告警响应
     */
    @PostMapping("/alerts/{id}/resolve")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<GovernanceAlertRecordResponse> resolveAlert(
            @PathVariable Long id,
            @RequestBody(required = false) GovernanceAlertHandleRequest request) {
        GovernanceAlertRecordResponse response = handlingService.resolve(id, request);
        return ApiResponse.success(response, TraceIdHolder.get());
    }

    /**
     * 重开告警.
     * Contract: POST /api/assets/governance-ops/alerts/{id}/reopen
     *
     * 状态转换: ACKED -> OPEN 或 RESOLVED -> OPEN
     * MANUAL_ONLY: 不调用执行器，仅记录状态变更
     *
     * @param id 告警 ID
     * @param request 处理请求
     * @return 更新后的告警响应
     */
    @PostMapping("/alerts/{id}/reopen")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<GovernanceAlertRecordResponse> reopenAlert(
            @PathVariable Long id,
            @RequestBody(required = false) GovernanceAlertHandleRequest request) {
        GovernanceAlertRecordResponse response = handlingService.reopen(id, request);
        return ApiResponse.success(response, TraceIdHolder.get());
    }

    /**
     * 获取告警详情.
     * Contract: GET /api/assets/governance-ops/alerts/{id}
     *
     * GET 查询无写副作用
     *
     * @param id 告警 ID
     * @return 告警详情响应
     */
    @GetMapping("/alerts/{id}")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<GovernanceAlertRecordResponse> getAlertDetail(@PathVariable Long id) {
        GovernanceAlertRecordResponse response = handlingService.getAlertDetail(id);
        return ApiResponse.success(response, TraceIdHolder.get());
    }

    /**
     * 查询告警处理历史.
     * Contract: GET /api/assets/governance-ops/alerts/{id}/handling-records
     *
     * GET 查询无写副作用
     *
     * @param id 告警 ID
     * @param pageNum 页码（默认 1）
     * @param pageSize 每页数量（默认 20）
     * @return 处理历史分页响应
     */
    @GetMapping("/alerts/{id}/handling-records")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<GovernanceAlertHandlingRecordPageResponse> getHandlingRecords(
            @PathVariable Long id,
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "20") Integer pageSize) {
        GovernanceAlertHandlingRecordPageResponse response = handlingService.getHandlingRecords(id, pageNum, pageSize);
        return ApiResponse.success(response, TraceIdHolder.get());
    }

    // ==================== O 阶段新增：告警升级 API ====================

    /**
     * 人工升级告警.
     * Contract: POST /api/assets/governance-ops/alerts/{id}/escalate
     *
     * 前置状态: OPEN 或 ACKED
     * 禁止状态: RESOLVED（已解决告警不可升级）
     * MANUAL_ONLY: 不调用执行器，仅记录升级状态和证据链
     * 不发送通知: 升级只记录证据链，不发送任何通知
     *
     * @param id 告警 ID
     * @param request 升级请求
     * @return 更新后的告警响应
     */
    @PostMapping("/alerts/{id}/escalate")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<GovernanceAlertRecordResponse> escalateAlert(
            @PathVariable Long id,
            @Valid @RequestBody GovernanceAlertEscalateRequest request) {
        GovernanceAlertRecordResponse response = escalationService.escalate(id, request);
        return ApiResponse.success(response, TraceIdHolder.get());
    }

    /**
     * 查询告警升级历史.
     * Contract: GET /api/assets/governance-ops/alerts/{id}/escalation-records
     *
     * GET 查询无写副作用
     *
     * @param id 告警 ID
     * @param pageNum 页码（默认 1）
     * @param pageSize 每页数量（默认 20）
     * @return 升级历史分页响应
     */
    @GetMapping("/alerts/{id}/escalation-records")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<GovernanceAlertEscalationRecordPageResponse> getEscalationRecords(
            @PathVariable Long id,
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "20") Integer pageSize) {
        GovernanceAlertEscalationRecordPageResponse response = escalationService.getEscalationRecords(id, pageNum, pageSize);
        return ApiResponse.success(response, TraceIdHolder.get());
    }

    /**
     * 查询已升级告警列表.
     * Contract: GET /api/assets/governance-ops/alerts/escalated
     *
     * GET 查询无写副作用
     *
     * @param pageNum 页码（默认 1）
     * @param pageSize 每页数量（默认 20）
     * @return 已升级告警分页响应
     */
    @GetMapping("/alerts/escalated")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<GovernanceAlertRecordPageResponse> getEscalatedAlerts(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "20") Integer pageSize) {
        GovernanceAlertRecordPageResponse response = escalationService.getEscalatedAlerts(pageNum, pageSize);
        return ApiResponse.success(response, TraceIdHolder.get());
    }
}
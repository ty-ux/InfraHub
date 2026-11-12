package com.infrahub.release.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.infrahub.common.api.ApiResponse;
import com.infrahub.common.support.TraceIdHolder;
import com.infrahub.release.dto.*;
import com.infrahub.release.entity.ResourceGovernanceRecord;
import com.infrahub.release.service.ResourceGovernanceActionExecutionService;
import com.infrahub.release.service.ResourceGovernanceRecordService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/api/assets")
public class ResourceGovernanceActionExecutionController {

    private final ResourceGovernanceActionExecutionService executionService;
    private final ResourceGovernanceRecordService governanceRecordService;

    @GetMapping("/governance-action-plans/{id}/executions")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<Page<ResourceGovernanceActionExecutionResponse>> listByPlan(
            @PathVariable Long id,
            @Valid ResourceGovernanceActionExecutionQuery query) {
        Page<ResourceGovernanceActionExecutionResponse> page = executionService.listByPlan(id, query);
        return ApiResponse.success(page, TraceIdHolder.get());
    }

    @PostMapping("/governance-action-plans/{id}/executions/start")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<ResourceGovernanceActionExecutionResponse> start(
            @PathVariable Long id,
            @RequestHeader(value = "X-User-Id", required = false, defaultValue = "0") Long operatorId) {
        ResourceGovernanceActionExecutionResponse response = executionService.start(id, operatorId);
        return ApiResponse.success(response, TraceIdHolder.get());
    }

    @GetMapping("/governance-action-executions/{id}")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<ResourceGovernanceActionExecutionResponse> getById(@PathVariable Long id) {
        ResourceGovernanceActionExecutionResponse response = executionService.getById(id);
        if (response == null) {
            return ApiResponse.failure("NOT_FOUND", "执行记录不存在", TraceIdHolder.get());
        }
        return ApiResponse.success(response, TraceIdHolder.get());
    }

    /**
     * 时间线查询 - Contract: GET /api/assets/governance-action-executions/{id}/timeline
     */
    @GetMapping("/governance-action-executions/{id}/timeline")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<TimelineResponse> getTimeline(@PathVariable Long id) {
        ResourceGovernanceActionExecutionResponse execution = executionService.getById(id);
        if (execution == null) {
            return ApiResponse.failure("NOT_FOUND", "执行记录不存在", TraceIdHolder.get());
        }

        List<ResourceGovernanceRecord> records = governanceRecordService.queryTimelineByActionExecutionNo(execution.getExecutionNo());

        TimelineResponse response = governanceRecordService.buildTimelineResponse(
                "ACTION_EXECUTION",
                id,
                execution.getExecutionNo(),
                records
        );

        return ApiResponse.success(response, TraceIdHolder.get());
    }

    @PostMapping("/governance-action-executions/{id}/complete")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<Void> complete(
            @PathVariable Long id,
            @RequestBody(required = false) @Valid CompleteActionExecutionRequest request,
            @RequestHeader(value = "X-User-Id", required = false, defaultValue = "0") Long operatorId) {
        executionService.complete(id, request, operatorId);
        return ApiResponse.success(null, TraceIdHolder.get());
    }

    @PostMapping("/governance-action-executions/{id}/fail")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<Void> fail(
            @PathVariable Long id,
            @RequestBody(required = false) @Valid FailActionExecutionRequest request,
            @RequestHeader(value = "X-User-Id", required = false, defaultValue = "0") Long operatorId) {
        executionService.fail(id, request, operatorId);
        return ApiResponse.success(null, TraceIdHolder.get());
    }

    @PostMapping("/governance-action-executions/{id}/cancel")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<Void> cancel(
            @PathVariable Long id,
            @RequestBody(required = false) @Valid CancelActionExecutionRequest request,
            @RequestHeader(value = "X-User-Id", required = false, defaultValue = "0") Long operatorId) {
        executionService.cancel(id, request, operatorId);
        return ApiResponse.success(null, TraceIdHolder.get());
    }

    // K-A: Impact recording API
    @PostMapping("/governance-action-executions/{id}/impact")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<ActionExecutionImpactResponse> recordImpact(
            @PathVariable Long id,
            @RequestBody @Valid RecordImpactRequest request,
            @RequestHeader(value = "X-User-Id", required = false, defaultValue = "0") Long operatorId) {
        ActionExecutionImpactResponse response = executionService.recordImpact(id, request, operatorId);
        return ApiResponse.success(response, TraceIdHolder.get());
    }
}
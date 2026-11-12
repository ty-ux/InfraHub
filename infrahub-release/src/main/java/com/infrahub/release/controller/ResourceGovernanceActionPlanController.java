package com.infrahub.release.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.infrahub.common.api.ApiResponse;
import com.infrahub.common.support.TraceIdHolder;
import com.infrahub.release.dto.*;
import com.infrahub.release.entity.ResourceGovernanceRecord;
import com.infrahub.release.service.ResourceGovernanceActionPlanService;
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
@RequestMapping("/api/assets/governance-action-plans")
public class ResourceGovernanceActionPlanController {

    private final ResourceGovernanceActionPlanService actionPlanService;
    private final ResourceGovernanceRecordService governanceRecordService;

    @GetMapping
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<Page<ResourceGovernanceActionPlanResponse>> list(@Valid ResourceGovernanceActionPlanQuery query) {
        Page<ResourceGovernanceActionPlanResponse> page = actionPlanService.query(query);
        return ApiResponse.success(page, TraceIdHolder.get());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<ResourceGovernanceActionPlanResponse> getById(@PathVariable Long id) {
        ResourceGovernanceActionPlanResponse response = actionPlanService.getById(id);
        if (response == null) {
            return ApiResponse.failure("NOT_FOUND", "执行计划不存在", TraceIdHolder.get());
        }
        return ApiResponse.success(response, TraceIdHolder.get());
    }

    /**
     * 时间线查询 - Contract: GET /api/assets/governance-action-plans/{id}/timeline
     */
    @GetMapping("/{id}/timeline")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<TimelineResponse> getTimeline(@PathVariable Long id) {
        ResourceGovernanceActionPlanResponse plan = actionPlanService.getById(id);
        if (plan == null) {
            return ApiResponse.failure("NOT_FOUND", "执行计划不存在", TraceIdHolder.get());
        }

        List<ResourceGovernanceRecord> records = governanceRecordService.queryTimelineByActionPlanNo(plan.getPlanNo());

        TimelineResponse response = governanceRecordService.buildTimelineResponse(
                "ACTION_PLAN",
                id,
                plan.getPlanNo(),
                records
        );

        return ApiResponse.success(response, TraceIdHolder.get());
    }

    @PostMapping("/from-suggestion/{suggestionId}")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<ResourceGovernanceActionPlanResponse> createFromSuggestion(
            @PathVariable Long suggestionId,
            @RequestBody @Valid CreateActionPlanRequest request,
            @RequestHeader(value = "X-User-Id", required = false, defaultValue = "0") Long operatorId) {
        ResourceGovernanceActionPlanResponse response = actionPlanService.createFromSuggestion(suggestionId, request, operatorId);
        return ApiResponse.success(response, TraceIdHolder.get());
    }

    @PostMapping("/{id}/confirm")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<Void> confirm(
            @PathVariable Long id,
            @RequestBody(required = false) ConfirmActionPlanRequest request,
            @RequestHeader(value = "X-User-Id", required = false, defaultValue = "0") Long operatorId) {
        actionPlanService.confirm(id, request, operatorId);
        return ApiResponse.success(null, TraceIdHolder.get());
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<Void> cancel(
            @PathVariable Long id,
            @RequestBody(required = false) CancelActionPlanRequest request,
            @RequestHeader(value = "X-User-Id", required = false, defaultValue = "0") Long operatorId) {
        actionPlanService.cancel(id, request, operatorId);
        return ApiResponse.success(null, TraceIdHolder.get());
    }
}

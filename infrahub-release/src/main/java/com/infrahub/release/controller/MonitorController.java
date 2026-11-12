package com.infrahub.release.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.infrahub.common.api.ApiResponse;
import com.infrahub.common.api.PageResponse;
import com.infrahub.common.context.UserContextHolder;
import com.infrahub.common.support.TraceIdHolder;
import com.infrahub.release.dto.CollectMonitorTargetResponse;
import com.infrahub.release.dto.CreateAlertRuleRequest;
import com.infrahub.release.dto.CreateHealthCheckRequest;
import com.infrahub.release.dto.CreateManualSnapshotRequest;
import com.infrahub.release.dto.EvaluateMonitorRulesRequest;
import com.infrahub.release.dto.MonitorAlertEventQuery;
import com.infrahub.release.dto.MonitorAlertRuleQuery;
import com.infrahub.release.dto.MonitorEvaluationResult;
import com.infrahub.release.dto.MonitorHealthCheckQuery;
import com.infrahub.release.dto.MonitorMetricTargetQuery;
import com.infrahub.release.dto.MonitorSnapshotQuery;
import com.infrahub.release.dto.ResolveAlertEventRequest;
import com.infrahub.release.dto.ResolveAlertEventResponse;
import com.infrahub.release.dto.MonitorCollectionScheduleStatus;
import com.infrahub.release.dto.MonitorCollectionItemResult;
import com.infrahub.release.dto.NotificationRecordResponse;
import com.infrahub.release.dto.RunOnceMonitorCollectionRequest;
import com.infrahub.release.dto.RunOnceMonitorCollectionResponse;
import com.infrahub.release.dto.ToggleResult;
import com.infrahub.release.dto.UpdateAlertRuleRequest;
import com.infrahub.release.dto.UpdateHealthCheckRequest;
import com.infrahub.release.entity.MonitorAlertEvent;
import com.infrahub.release.entity.MonitorAlertRule;
import com.infrahub.release.entity.MonitorHealthCheck;
import com.infrahub.release.entity.MonitorMetricTarget;
import com.infrahub.release.entity.MonitorSnapshot;
import com.infrahub.release.service.MonitorAlertEventService;
import com.infrahub.release.service.MonitorAlertRuleService;
import com.infrahub.release.service.MonitorCollectionRunService;
import com.infrahub.release.service.MonitorCollectionService;
import com.infrahub.release.service.MonitorEvaluationService;
import com.infrahub.release.service.MonitorHealthCheckService;
import com.infrahub.release.service.MonitorMetricTargetService;
import com.infrahub.release.service.MonitorNotificationRecordService;
import com.infrahub.release.service.MonitorScheduledCollectionService;
import com.infrahub.release.service.MonitorSnapshotService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/api/monitor")
public class MonitorController {

    private final MonitorMetricTargetService monitorMetricTargetService;
    private final MonitorSnapshotService monitorSnapshotService;
    private final MonitorAlertRuleService monitorAlertRuleService;
    private final MonitorAlertEventService monitorAlertEventService;
    private final MonitorHealthCheckService monitorHealthCheckService;
    private final MonitorEvaluationService monitorEvaluationService;
    private final MonitorCollectionService monitorCollectionService;
    private final MonitorCollectionRunService monitorCollectionRunService;
    private final MonitorNotificationRecordService notificationRecordService;
    private final ObjectProvider<MonitorScheduledCollectionService> scheduledCollectionServiceProvider;

    @GetMapping("/targets")
    @PreAuthorize("hasAnyAuthority('RELEASE_VIEW','SYSTEM_ADMIN')")
    public ApiResponse<PageResponse<MonitorMetricTarget>> pageTargets(@Valid MonitorMetricTargetQuery query) {
        Page<MonitorMetricTarget> page = monitorMetricTargetService.page(query);
        return ApiResponse.success(PageResponse.from(page), TraceIdHolder.get());
    }

    @PostMapping("/targets/{targetId}/collect")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<CollectMonitorTargetResponse> collectTarget(@PathVariable Long targetId) {
        CollectMonitorTargetResponse response = monitorCollectionService.collect(targetId);
        return ApiResponse.success(response, TraceIdHolder.get());
    }

    @PostMapping("/collections/run-once")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<RunOnceMonitorCollectionResponse> runOnceCollection(
            @RequestBody(required = false) RunOnceMonitorCollectionRequest request) {
        RunOnceMonitorCollectionResponse response = monitorCollectionRunService.runOnce(request);
        return ApiResponse.success(response, TraceIdHolder.get());
    }

    @GetMapping("/snapshots")
    @PreAuthorize("hasAnyAuthority('RELEASE_VIEW','SYSTEM_ADMIN')")
    public ApiResponse<PageResponse<MonitorSnapshot>> pageSnapshots(@Valid MonitorSnapshotQuery query) {
        Page<MonitorSnapshot> page = monitorSnapshotService.page(query);
        return ApiResponse.success(PageResponse.from(page), TraceIdHolder.get());
    }

    @PostMapping("/snapshots/manual")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<Long> createManualSnapshot(@RequestBody @Valid CreateManualSnapshotRequest request) {
        Long id = monitorSnapshotService.createManualSnapshot(request);
        return ApiResponse.success(id, TraceIdHolder.get());
    }

    @GetMapping("/alert-rules")
    @PreAuthorize("hasAnyAuthority('RELEASE_VIEW','SYSTEM_ADMIN')")
    public ApiResponse<PageResponse<MonitorAlertRule>> pageAlertRules(@Valid MonitorAlertRuleQuery query) {
        Page<MonitorAlertRule> page = monitorAlertRuleService.page(query);
        return ApiResponse.success(PageResponse.from(page), TraceIdHolder.get());
    }

    @PostMapping("/alert-rules")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<Long> createAlertRule(@RequestBody @Valid CreateAlertRuleRequest request) {
        Long id = monitorAlertRuleService.create(request);
        return ApiResponse.success(id, TraceIdHolder.get());
    }

    @PutMapping("/alert-rules/{id}")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<Void> updateAlertRule(@PathVariable Long id, @RequestBody @Valid UpdateAlertRuleRequest request) {
        monitorAlertRuleService.update(id, request);
        return ApiResponse.success(null, TraceIdHolder.get());
    }

    @DeleteMapping("/alert-rules/{id}")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<Void> deleteAlertRule(@PathVariable Long id) {
        monitorAlertRuleService.delete(id);
        return ApiResponse.success(null, TraceIdHolder.get());
    }

    @PutMapping("/alert-rules/{id}/enabled")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<ToggleResult> toggleAlertRuleEnabled(@PathVariable Long id, @RequestParam Boolean enabled) {
        Long approvalId = monitorAlertRuleService.toggleEnabled(id, enabled);
        if (approvalId != null) {
            // Approval was created
            return ApiResponse.success(ToggleResult.pendingApproval(approvalId), TraceIdHolder.get());
        }
        // Direct execution
        return ApiResponse.success(ToggleResult.directExecuted(), TraceIdHolder.get());
    }

    @PostMapping("/evaluations")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<MonitorEvaluationResult> evaluateRules(@RequestBody @Valid EvaluateMonitorRulesRequest request) {
        MonitorEvaluationResult result = monitorEvaluationService.evaluate(request);
        return ApiResponse.success(result, TraceIdHolder.get());
    }

    @GetMapping("/alert-events")
    @PreAuthorize("hasAnyAuthority('RELEASE_VIEW','SYSTEM_ADMIN')")
    public ApiResponse<PageResponse<MonitorAlertEvent>> pageAlertEvents(@Valid MonitorAlertEventQuery query) {
        Page<MonitorAlertEvent> page = monitorAlertEventService.page(query);
        return ApiResponse.success(PageResponse.from(page), TraceIdHolder.get());
    }

    @PostMapping("/alert-events/{id}/resolve")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<ResolveAlertEventResponse> resolveAlertEvent(
            @PathVariable Long id,
            @RequestBody(required = false) @Valid ResolveAlertEventRequest request) {
        MonitorAlertEvent event = monitorAlertEventService.manualResolve(
                id,
                UserContextHolder.getUserIdOrDefault(1L),
                request != null ? request.getHandleNote() : null);

        ResolveAlertEventResponse response = new ResolveAlertEventResponse();
        response.setEventId(event.getId());
        response.setAlertStatus(event.getAlertStatus());
        response.setRecoveredAt(event.getRecoveredAt() != null ? event.getRecoveredAt().toString() : null);
        response.setHandleBy(event.getHandleBy());
        response.setHandleNote(event.getHandleNote());

        return ApiResponse.success(response, TraceIdHolder.get());
    }

    @GetMapping("/health-checks")
    @PreAuthorize("hasAnyAuthority('RELEASE_VIEW','SYSTEM_ADMIN')")
    public ApiResponse<PageResponse<MonitorHealthCheck>> pageHealthChecks(@Valid MonitorHealthCheckQuery query) {
        Page<MonitorHealthCheck> page = monitorHealthCheckService.page(query);
        return ApiResponse.success(PageResponse.from(page), TraceIdHolder.get());
    }

    @PostMapping("/health-checks")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<Long> createHealthCheck(@RequestBody @Valid CreateHealthCheckRequest request) {
        Long id = monitorHealthCheckService.create(request);
        return ApiResponse.success(id, TraceIdHolder.get());
    }

    @PutMapping("/health-checks/{id}")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<Void> updateHealthCheck(@PathVariable Long id, @RequestBody @Valid UpdateHealthCheckRequest request) {
        monitorHealthCheckService.update(id, request);
        return ApiResponse.success(null, TraceIdHolder.get());
    }

    @DeleteMapping("/health-checks/{id}")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<Void> deleteHealthCheck(@PathVariable Long id) {
        monitorHealthCheckService.delete(id);
        return ApiResponse.success(null, TraceIdHolder.get());
    }

    @PutMapping("/health-checks/{id}/enabled")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<ToggleResult> toggleHealthCheckEnabled(@PathVariable Long id, @RequestParam Boolean enabled) {
        Long approvalId = monitorHealthCheckService.toggleEnabled(id, enabled);
        if (approvalId != null) {
            // Approval was created
            return ApiResponse.success(ToggleResult.pendingApproval(approvalId), TraceIdHolder.get());
        }
        // Direct execution
        return ApiResponse.success(ToggleResult.directExecuted(), TraceIdHolder.get());
    }

    @GetMapping("/collections/schedule/status")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<MonitorCollectionScheduleStatus> getScheduleStatus() {
        MonitorScheduledCollectionService service = scheduledCollectionServiceProvider.getIfAvailable();
        MonitorCollectionScheduleStatus status = service != null
                ? service.getStatus()
                : MonitorCollectionScheduleStatus.builder()
                        .enabled(false)
                        .fixedDelayMs(60000)
                        .lastMessage("定时采集服务未启用")
                        .build();
        return ApiResponse.success(status, TraceIdHolder.get());
    }

    // E3.2: 通知记录查询
    @GetMapping("/alert-events/{eventId}/notifications")
    @PreAuthorize("hasAnyAuthority('RELEASE_VIEW','SYSTEM_ADMIN')")
    public ApiResponse<java.util.List<NotificationRecordResponse>> getEventNotifications(@PathVariable Long eventId) {
        return ApiResponse.success(notificationRecordService.getByAlertEventId(eventId), TraceIdHolder.get());
    }

    @GetMapping("/alert-rules/{ruleId}/notifications")
    @PreAuthorize("hasAnyAuthority('RELEASE_VIEW','SYSTEM_ADMIN')")
    public ApiResponse<java.util.List<NotificationRecordResponse>> getRuleNotifications(
            @PathVariable Long ruleId,
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.success(notificationRecordService.getByRuleId(ruleId, limit), TraceIdHolder.get());
    }
}

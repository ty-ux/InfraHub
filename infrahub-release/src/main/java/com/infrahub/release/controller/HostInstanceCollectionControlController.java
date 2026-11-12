package com.infrahub.release.controller;

import com.infrahub.common.api.ApiResponse;
import com.infrahub.common.support.TraceIdHolder;
import com.infrahub.release.service.HostInstanceCollectionControlService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/monitor/host-instance-collection")
public class HostInstanceCollectionControlController {
    private final HostInstanceCollectionControlService control;

    @GetMapping
    @PreAuthorize("hasAnyAuthority('MONITOR_VIEW','RELEASE_VIEW','SYSTEM_ADMIN')")
    public ApiResponse<HostInstanceCollectionControlService.ScheduleState> state() {
        return ApiResponse.success(control.state(), TraceIdHolder.get());
    }

    @PutMapping
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<HostInstanceCollectionControlService.ScheduleState> update(@Valid @RequestBody SwitchRequest request) {
        control.setEnabled(request.enabled());
        return state();
    }

    public record SwitchRequest(@NotNull Boolean enabled) {}
}

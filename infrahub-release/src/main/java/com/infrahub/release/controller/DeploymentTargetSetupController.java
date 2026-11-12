package com.infrahub.release.controller;

import com.infrahub.common.api.ApiResponse;
import com.infrahub.common.support.TraceIdHolder;
import com.infrahub.release.dto.HostSshSetupRequest;
import com.infrahub.release.dto.RegisterRuntimeInstanceRequest;
import com.infrahub.release.entity.AssetHostSshConfig;
import com.infrahub.release.entity.RuntimeServiceInstance;
import com.infrahub.release.service.DeploymentTargetSetupService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
@PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
public class DeploymentTargetSetupController {
    private final DeploymentTargetSetupService setupService;

    @GetMapping("/assets/hosts/{id}/ssh-config")
    public ApiResponse<AssetHostSshConfig> getSsh(@PathVariable Long id) {
        return ApiResponse.success(setupService.getSsh(id), TraceIdHolder.get());
    }

    @PutMapping("/assets/hosts/{id}/ssh-config")
    public ApiResponse<AssetHostSshConfig> saveSsh(@PathVariable Long id,
            @RequestBody @Valid HostSshSetupRequest request) {
        return ApiResponse.success(setupService.saveSsh(id, request), TraceIdHolder.get());
    }

    @PostMapping("/runtime/service-instances")
    public ApiResponse<RuntimeServiceInstance> registerInstance(
            @RequestBody @Valid RegisterRuntimeInstanceRequest request) {
        return ApiResponse.success(setupService.registerInstance(request), TraceIdHolder.get());
    }
}

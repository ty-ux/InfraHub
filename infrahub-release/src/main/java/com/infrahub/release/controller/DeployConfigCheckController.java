package com.infrahub.release.controller;

import com.infrahub.common.api.ApiResponse;
import com.infrahub.common.error.ErrorCode;
import com.infrahub.common.exception.BizException;
import com.infrahub.common.support.TraceIdHolder;
import com.infrahub.release.dto.DeployConfigCheckResponse;
import com.infrahub.release.service.DeployConfigCheckService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/api/releases")
public class DeployConfigCheckController {

    private final DeployConfigCheckService deployConfigCheckService;

    @GetMapping("/deploy-config/check")
    @PreAuthorize("hasAnyAuthority('RELEASE_VIEW','SYSTEM_ADMIN')")
    public ApiResponse<DeployConfigCheckResponse> check(
            @RequestParam Long appId,
            @RequestParam Long envId,
            @RequestParam(required = false) Long serviceId) {

        if (appId == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "appId 不能为空");
        }
        if (envId == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "envId 不能为空");
        }

        DeployConfigCheckResponse response = deployConfigCheckService.checkDeployConfig(appId, envId, serviceId);
        return ApiResponse.success(response, TraceIdHolder.get());
    }
}
package com.infrahub.release.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.infrahub.common.api.ApiResponse;
import com.infrahub.common.api.PageResponse;
import com.infrahub.common.support.TraceIdHolder;
import com.infrahub.release.dto.RuntimeInstanceRemovalResponse;
import com.infrahub.release.dto.RuntimeInstanceStopResponse;
import com.infrahub.release.dto.RuntimeInstanceResourceViewResponse;
import com.infrahub.release.dto.RuntimeServiceInstanceDeployConfigResponse;
import com.infrahub.release.dto.RuntimeServiceInstanceLogsResponse;
import com.infrahub.release.dto.RuntimeServiceInstanceQuery;
import com.infrahub.release.dto.RuntimeServiceInstanceStatusResponse;
import com.infrahub.release.entity.RuntimeServiceInstance;
import com.infrahub.release.service.RuntimeInstanceDeregistrationService;
import com.infrahub.release.service.RuntimeInstanceStopService;
import com.infrahub.release.service.RuntimeServiceDiagnosticsService;
import com.infrahub.release.service.RuntimeServiceInstanceService;
import com.infrahub.release.service.RuntimeInstanceResourceViewService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/api/runtime/service-instances")
@PreAuthorize("hasAnyAuthority('RELEASE_VIEW','SYSTEM_ADMIN')")
public class RuntimeServiceInstanceController {

    private final RuntimeServiceDiagnosticsService runtimeServiceDiagnosticsService;
    private final RuntimeServiceInstanceService runtimeServiceInstanceService;
    private final RuntimeInstanceResourceViewService runtimeInstanceResourceViewService;
    private final RuntimeInstanceStopService runtimeInstanceStopService;
    private final RuntimeInstanceDeregistrationService runtimeInstanceDeregistrationService;

    @GetMapping
    public ApiResponse<List<RuntimeServiceInstance>> listByServiceAndEnv(
            @RequestParam Long serviceId,
            @RequestParam Long envId
    ) {
        return ApiResponse.success(runtimeServiceDiagnosticsService.listByServiceAndEnv(serviceId, envId), TraceIdHolder.get());
    }

    @GetMapping("/page")
    public ApiResponse<PageResponse<RuntimeServiceInstance>> page(@Valid RuntimeServiceInstanceQuery query) {
        LambdaQueryWrapper<RuntimeServiceInstance> wrapper = new LambdaQueryWrapper<>();

        if (query.getKeyword() != null && !query.getKeyword().isBlank()) {
            wrapper.like(RuntimeServiceInstance::getInstanceName, query.getKeyword());
        }
        if (query.getServiceId() != null) {
            wrapper.eq(RuntimeServiceInstance::getServiceId, query.getServiceId());
        }
        if (query.getEnvId() != null) {
            wrapper.eq(RuntimeServiceInstance::getEnvId, query.getEnvId());
        }
        if (query.getHostId() != null) {
            wrapper.eq(RuntimeServiceInstance::getHostId, query.getHostId());
        }
        if (query.getInstanceStatus() != null && !query.getInstanceStatus().isBlank()) {
            wrapper.eq(RuntimeServiceInstance::getInstanceStatus, query.getInstanceStatus());
        }
        if (query.getHealthStatus() != null && !query.getHealthStatus().isBlank()) {
            wrapper.eq(RuntimeServiceInstance::getHealthStatus, query.getHealthStatus());
        }

        wrapper.orderByDesc(RuntimeServiceInstance::getLastHeartbeat);

        Page<RuntimeServiceInstance> page = runtimeServiceInstanceService.page(
                new Page<>(query.getPageNum(), query.getPageSize()),
                wrapper
        );

        return ApiResponse.success(PageResponse.from(page), TraceIdHolder.get());
    }

    @GetMapping("/{id}/status")
    public ApiResponse<RuntimeServiceInstanceStatusResponse> status(@PathVariable Long id) {
        return ApiResponse.success(runtimeServiceDiagnosticsService.getStatus(id), TraceIdHolder.get());
    }

    @GetMapping("/{id}/deploy-config")
    public ApiResponse<RuntimeServiceInstanceDeployConfigResponse> deployConfig(@PathVariable Long id) {
        return ApiResponse.success(runtimeServiceDiagnosticsService.getDeployConfig(id), TraceIdHolder.get());
    }

    @GetMapping("/{id}/logs")
    public ApiResponse<RuntimeServiceInstanceLogsResponse> logs(
            @PathVariable Long id,
            @RequestParam(defaultValue = "200") Integer lines
    ) {
        return ApiResponse.success(runtimeServiceDiagnosticsService.getLogs(id, lines), TraceIdHolder.get());
    }

    @GetMapping("/{id}/resource-view")
    public ApiResponse<RuntimeInstanceResourceViewResponse> resourceView(@PathVariable Long id) {
        RuntimeInstanceResourceViewResponse response = runtimeInstanceResourceViewService.getResourceView(id);
        return ApiResponse.success(response, TraceIdHolder.get());
    }

    /**
     * 单实例停止请求。
     *
     * <p><b>当前一律拒绝</b>：平台尚无可证明"目标进程就是该实例"的身份材料，因此本接口不建立 SSH 连接、
     * 不发送任何信号，固定返回 {@code outcome=IDENTITY_UNCONFIRMED} 并写入审计。详见
     * {@link RuntimeInstanceStopService}。请求被接受不等于进程已停止。
     */
    @PostMapping("/{id}/stop")
    @PreAuthorize("hasAnyAuthority('RELEASE_EXECUTE','SYSTEM_ADMIN')")
    public ApiResponse<RuntimeInstanceStopResponse> stop(@PathVariable Long id) {
        return ApiResponse.success(runtimeInstanceStopService.stop(id), TraceIdHolder.get());
    }

    /**
     * 移除运行实例登记。**不停止服务器上的进程**，也不连接目标主机。
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<RuntimeInstanceRemovalResponse> deregister(@PathVariable Long id) {
        return ApiResponse.success(runtimeInstanceDeregistrationService.deregister(id), TraceIdHolder.get());
    }
}

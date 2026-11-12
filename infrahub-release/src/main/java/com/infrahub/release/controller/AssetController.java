package com.infrahub.release.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.infrahub.common.api.ApiResponse;
import com.infrahub.common.api.PageResponse;
import com.infrahub.common.context.UserContextHolder;
import com.infrahub.common.support.TraceIdHolder;
import com.infrahub.release.dto.AssetHostQuery;
import com.infrahub.release.dto.CreateActionPlanRequest;
import com.infrahub.release.dto.CreateAssetHostRequest;
import com.infrahub.release.dto.GovernanceSuggestionActionRequest;
import com.infrahub.release.dto.HostProfileResponse;
import com.infrahub.release.dto.ResourceGovernanceActionPlanResponse;
import com.infrahub.release.dto.ResourceGovernanceSuggestionQuery;
import com.infrahub.release.dto.ResourceGovernanceSuggestionResponse;
import com.infrahub.release.dto.ResourcePoolCapacityResponse;
import com.infrahub.release.dto.ResourcePoolQuery;
import com.infrahub.release.dto.ResourcePoolRiskResponse;
import com.infrahub.release.dto.RiskQuery;
import com.infrahub.release.entity.AssetHost;
import com.infrahub.release.entity.ResourcePool;
import com.infrahub.release.service.AssetHostProfileService;
import com.infrahub.release.service.AssetHostService;
import com.infrahub.release.service.ResourceCapacityRiskService;
import com.infrahub.release.service.ResourceGovernanceActionPlanService;
import com.infrahub.release.service.ResourceGovernanceSuggestionService;
import com.infrahub.release.service.ResourcePoolService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/api/assets")
public class AssetController {

    private final ResourcePoolService resourcePoolService;
    private final AssetHostService assetHostService;
    private final AssetHostProfileService assetHostProfileService;
    private final ResourceCapacityRiskService resourceCapacityRiskService;
    private final ResourceGovernanceSuggestionService resourceGovernanceSuggestionService;
    private final ResourceGovernanceActionPlanService resourceGovernanceActionPlanService;

    @GetMapping("/resource-pools")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<PageResponse<ResourcePool>> pageResourcePools(@Valid ResourcePoolQuery query) {
        LambdaQueryWrapper<ResourcePool> wrapper = new LambdaQueryWrapper<>();

        if (query.getKeyword() != null && !query.getKeyword().isBlank()) {
            wrapper.and(w -> w
                    .like(ResourcePool::getPoolCode, query.getKeyword())
                    .or()
                    .like(ResourcePool::getPoolName, query.getKeyword())
            );
        }
        if (query.getPoolType() != null && !query.getPoolType().isBlank()) {
            wrapper.eq(ResourcePool::getPoolType, query.getPoolType());
        }
        if (query.getStatus() != null && !query.getStatus().isBlank()) {
            wrapper.eq(ResourcePool::getStatus, query.getStatus());
        }

        wrapper.orderByDesc(ResourcePool::getCreatedAt);

        Page<ResourcePool> page = resourcePoolService.page(
                new Page<>(query.getPageNum(), query.getPageSize()),
                wrapper
        );

        return ApiResponse.success(PageResponse.from(page), TraceIdHolder.get());
    }

    @GetMapping("/resource-pools/{id}/capacity")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<ResourcePoolCapacityResponse> getResourcePoolCapacity(@PathVariable Long id) {
        ResourcePoolCapacityResponse capacity = resourcePoolService.getCapacity(id);
        return ApiResponse.success(capacity, TraceIdHolder.get());
    }

    @GetMapping("/resource-pools/{id}/risk")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<ResourcePoolRiskResponse> getResourcePoolRisk(@PathVariable Long id) {
        ResourcePoolRiskResponse risk = resourceCapacityRiskService.evaluateRisk(id);
        if (risk == null) {
            return ApiResponse.failure("NOT_FOUND", "资源池不存在", TraceIdHolder.get());
        }
        return ApiResponse.success(risk, TraceIdHolder.get());
    }

    @GetMapping("/resource-pools/risks")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<PageResponse<ResourcePoolRiskResponse>> listResourcePoolRisks(@Valid RiskQuery query) {
        PageResponse<ResourcePoolRiskResponse> risks = resourceCapacityRiskService.listRisks(query);
        return ApiResponse.success(risks, TraceIdHolder.get());
    }

    @GetMapping("/hosts")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<PageResponse<AssetHost>> pageHosts(@Valid AssetHostQuery query) {
        LambdaQueryWrapper<AssetHost> wrapper = new LambdaQueryWrapper<>();

        if (query.getKeyword() != null && !query.getKeyword().isBlank()) {
            wrapper.and(w -> w
                    .like(AssetHost::getHostname, query.getKeyword())
                    .or()
                    .like(AssetHost::getIp, query.getKeyword())
            );
        }
        if (query.getHostStatus() != null && !query.getHostStatus().isBlank()) {
            wrapper.eq(AssetHost::getHostStatus, query.getHostStatus());
        }
        if (query.getAgentStatus() != null && !query.getAgentStatus().isBlank()) {
            wrapper.eq(AssetHost::getAgentStatus, query.getAgentStatus());
        }
        if (query.getEnvId() != null) {
            wrapper.eq(AssetHost::getEnvId, query.getEnvId());
        }
        if (query.getResourcePoolId() != null) {
            wrapper.eq(AssetHost::getResourcePoolId, query.getResourcePoolId());
        }

        wrapper.orderByDesc(AssetHost::getCreatedAt);

        Page<AssetHost> page = assetHostService.page(
                new Page<>(query.getPageNum(), query.getPageSize()),
                wrapper
        );

        return ApiResponse.success(PageResponse.from(page), TraceIdHolder.get());
    }

    /**
     * 手动登记主机。
     *
     * <p>只写资产台账：不连接服务器、不安装 Agent、不创建运行实例、不写心跳时间。
     * 新主机以 hostStatus=UNKNOWN / agentStatus=UNKNOWN 入库，不默认声称在线或健康。
     */
    @PostMapping("/hosts")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<AssetHost> createHost(@RequestBody @Valid CreateAssetHostRequest request) {
        AssetHost host = assetHostService.createHost(request);
        return ApiResponse.success(host, TraceIdHolder.get());
    }

    @GetMapping("/hosts/{id}/profile")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<HostProfileResponse> getHostProfile(@PathVariable Long id) {
        HostProfileResponse profile = assetHostProfileService.getHostProfile(id);
        if (profile == null) {
            return ApiResponse.failure("NOT_FOUND", "主机不存在", TraceIdHolder.get());
        }
        return ApiResponse.success(profile, TraceIdHolder.get());
    }

    // ============ 资源治理建议管理 API ============

    @GetMapping("/governance-suggestions")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<PageResponse<ResourceGovernanceSuggestionResponse>> listGovernanceSuggestions(
            @Valid ResourceGovernanceSuggestionQuery query) {
        Page<ResourceGovernanceSuggestionResponse> page = resourceGovernanceSuggestionService.query(query);
        return ApiResponse.success(
                PageResponse.<ResourceGovernanceSuggestionResponse>builder()
                        .pageNum(page.getCurrent())
                        .pageSize(page.getSize())
                        .total(page.getTotal())
                        .records(page.getRecords())
                        .build(),
                TraceIdHolder.get()
        );
    }

    @GetMapping("/governance-suggestions/{id}")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<ResourceGovernanceSuggestionResponse> getGovernanceSuggestion(@PathVariable Long id) {
        ResourceGovernanceSuggestionResponse suggestion = resourceGovernanceSuggestionService.getById(id);
        if (suggestion == null) {
            return ApiResponse.failure("NOT_FOUND", "治理建议不存在", TraceIdHolder.get());
        }
        return ApiResponse.success(suggestion, TraceIdHolder.get());
    }

    @PostMapping("/resource-pools/{id}/suggestions/generate")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<List<ResourceGovernanceSuggestionResponse>> generateSuggestions(@PathVariable Long id) {
        List<ResourceGovernanceSuggestionResponse> suggestions = resourceGovernanceSuggestionService.generateSuggestions(id);
        return ApiResponse.success(suggestions, TraceIdHolder.get());
    }

    @PostMapping("/governance-suggestions/{id}/ack")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<ResourceGovernanceSuggestionResponse> ackGovernanceSuggestion(
            @PathVariable Long id,
            @RequestBody @Valid GovernanceSuggestionActionRequest request) {
        Long operatorId = UserContextHolder.getUserIdOrDefault(0L);
        resourceGovernanceSuggestionService.ack(id, request.getHandleNote(), operatorId);
        ResourceGovernanceSuggestionResponse suggestion = resourceGovernanceSuggestionService.getById(id);
        return ApiResponse.success(suggestion, TraceIdHolder.get());
    }

    @PostMapping("/governance-suggestions/{id}/ignore")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<ResourceGovernanceSuggestionResponse> ignoreGovernanceSuggestion(
            @PathVariable Long id,
            @RequestBody @Valid GovernanceSuggestionActionRequest request) {
        Long operatorId = UserContextHolder.getUserIdOrDefault(0L);
        resourceGovernanceSuggestionService.ignore(id, request.getHandleNote(), operatorId);
        ResourceGovernanceSuggestionResponse suggestion = resourceGovernanceSuggestionService.getById(id);
        return ApiResponse.success(suggestion, TraceIdHolder.get());
    }

    @PostMapping("/governance-suggestions/{id}/resolve")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<ResourceGovernanceSuggestionResponse> resolveGovernanceSuggestion(
            @PathVariable Long id,
            @RequestBody @Valid GovernanceSuggestionActionRequest request) {
        Long operatorId = UserContextHolder.getUserIdOrDefault(0L);
        resourceGovernanceSuggestionService.resolve(id, request.getHandleNote(), operatorId);
        ResourceGovernanceSuggestionResponse suggestion = resourceGovernanceSuggestionService.getById(id);
        return ApiResponse.success(suggestion, TraceIdHolder.get());
    }

    @PostMapping("/governance-suggestions/{id}/action-plan")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<ResourceGovernanceActionPlanResponse> createActionPlanFromSuggestion(
            @PathVariable Long id,
            @RequestBody(required = false) @Valid CreateActionPlanRequest request) {
        Long operatorId = UserContextHolder.getUserIdOrDefault(0L);
        // 支持 request body 为空，自动推导 actionType、targetType、planPayload
        ResourceGovernanceActionPlanResponse response = resourceGovernanceActionPlanService.createFromSuggestion(id, operatorId);
        return ApiResponse.success(response, TraceIdHolder.get());
    }
}

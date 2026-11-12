package com.infrahub.release.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.infrahub.base.entity.BaseEnv;
import com.infrahub.base.service.BaseEnvService;
import com.infrahub.common.api.ApiResponse;
import com.infrahub.common.api.PageResponse;
import com.infrahub.common.support.TraceIdHolder;
import com.infrahub.release.dto.CapacityPolicyQuery;
import com.infrahub.release.dto.CapacityPolicyRequest;
import com.infrahub.release.dto.CapacityPolicyResponse;
import com.infrahub.release.entity.CapacityPolicy;
import com.infrahub.release.entity.ResourcePool;
import com.infrahub.release.service.CapacityPolicyService;
import com.infrahub.release.service.ResourcePoolService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/api/assets/capacity-policies")
public class CapacityPolicyController {

    private final CapacityPolicyService capacityPolicyService;
    private final ResourcePoolService resourcePoolService;
    private final BaseEnvService baseEnvService;

    @GetMapping
    @PreAuthorize("hasAnyAuthority('RELEASE_VIEW','SYSTEM_ADMIN')")
    public ApiResponse<PageResponse<CapacityPolicyResponse>> query(@Valid CapacityPolicyQuery query) {
        Page<CapacityPolicy> page = capacityPolicyService.query(query);

        List<CapacityPolicyResponse> records = page.getRecords().stream()
                .map(this::enrichResponse)
                .toList();

        PageResponse<CapacityPolicyResponse> response = new PageResponse<>();
        response.setRecords(records);
        response.setTotal(page.getTotal());
        response.setPageNum(page.getCurrent());
        response.setPageSize(page.getSize());

        return ApiResponse.success(response, TraceIdHolder.get());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('RELEASE_VIEW','SYSTEM_ADMIN')")
    public ApiResponse<CapacityPolicyResponse> getById(@PathVariable Long id) {
        CapacityPolicy policy = capacityPolicyService.getById(id);
        if (policy == null) {
            return ApiResponse.failure("NOT_FOUND", "容量阈值策略不存在", TraceIdHolder.get());
        }
        return ApiResponse.success(enrichResponse(policy), TraceIdHolder.get());
    }

    @PostMapping
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<CapacityPolicyResponse> create(@RequestBody @Valid CapacityPolicyRequest request) {
        CapacityPolicy policy = capacityPolicyService.create(request);
        return ApiResponse.success(CapacityPolicyResponse.fromEntity(policy), TraceIdHolder.get());
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<CapacityPolicyResponse> update(@PathVariable Long id, @RequestBody @Valid CapacityPolicyRequest request) {
        CapacityPolicy policy = capacityPolicyService.update(id, request);
        return ApiResponse.success(CapacityPolicyResponse.fromEntity(policy), TraceIdHolder.get());
    }

    @PostMapping("/{id}/toggle")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<CapacityPolicyResponse> toggle(@PathVariable Long id, @RequestParam boolean enabled) {
        CapacityPolicy policy = capacityPolicyService.toggle(id, enabled);
        return ApiResponse.success(CapacityPolicyResponse.fromEntity(policy), TraceIdHolder.get());
    }

    private CapacityPolicyResponse enrichResponse(CapacityPolicy entity) {
        CapacityPolicyResponse response = CapacityPolicyResponse.fromEntity(entity);
        if (response == null) {
            return null;
        }

        if (entity.getResourcePoolId() != null) {
            ResourcePool pool = resourcePoolService.getById(entity.getResourcePoolId());
            if (pool != null) {
                response.setResourcePoolName(pool.getPoolName());
            }
        }

        if (entity.getEnvId() != null) {
            BaseEnv env = baseEnvService.getById(entity.getEnvId());
            if (env != null) {
                response.setEnvName(env.getEnvName());
            }
        }

        return response;
    }
}

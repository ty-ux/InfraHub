package com.infrahub.release.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.infrahub.common.api.ApiResponse;
import com.infrahub.common.support.TraceIdHolder;
import com.infrahub.release.dto.ReleaseEnvironmentPolicyDto;
import com.infrahub.release.entity.ReleaseEnvironmentPolicy;
import com.infrahub.release.mapper.ReleaseEnvironmentPolicyMapper;
import com.infrahub.release.service.ReleaseAdmissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/release-environment-policies")
@RequiredArgsConstructor
public class ReleaseEnvironmentPolicyController {

    private final ReleaseEnvironmentPolicyMapper policyMapper;
    private final ReleaseAdmissionService admissionService;

    @GetMapping
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<List<ReleaseEnvironmentPolicyDto>> list() {
        LambdaQueryWrapper<ReleaseEnvironmentPolicy> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByAsc(ReleaseEnvironmentPolicy::getEnvId)
               .orderByAsc(ReleaseEnvironmentPolicy::getAction)
               .orderByAsc(ReleaseEnvironmentPolicy::getRiskLevel);

        List<ReleaseEnvironmentPolicy> policies = policyMapper.selectList(wrapper);
        List<ReleaseEnvironmentPolicyDto> dtos = policies.stream()
            .map(this::toDto)
            .collect(Collectors.toList());

        return ApiResponse.success(dtos, TraceIdHolder.get());
    }

    @PutMapping
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<Void> saveAll(@RequestBody List<ReleaseEnvironmentPolicyDto> dtos) {
        for (ReleaseEnvironmentPolicyDto dto : dtos) {
            ReleaseEnvironmentPolicy policy = new ReleaseEnvironmentPolicy();
            BeanUtils.copyProperties(dto, policy);

            if (dto.getId() != null) {
                // Has ID, update directly
                policyMapper.updateById(policy);
            } else {
                // No ID, check for existing policy by envId + action + riskLevel (idempotent)
                LambdaQueryWrapper<ReleaseEnvironmentPolicy> wrapper = new LambdaQueryWrapper<>();
                wrapper.eq(ReleaseEnvironmentPolicy::getEnvId, dto.getEnvId())
                       .eq(ReleaseEnvironmentPolicy::getAction, dto.getAction())
                       .eq(ReleaseEnvironmentPolicy::getRiskLevel, dto.getRiskLevel());
                ReleaseEnvironmentPolicy existing = policyMapper.selectOne(wrapper);

                if (existing != null) {
                    // Update existing policy instead of creating new one
                    policy.setId(existing.getId());
                    policyMapper.updateById(policy);
                } else {
                    // No existing policy, insert new one
                    policyMapper.insert(policy);
                }
            }
        }
        return ApiResponse.success(null, TraceIdHolder.get());
    }

    @GetMapping("/effective")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<ReleaseEnvironmentPolicyDto> getEffective(
            @RequestParam Long envId,
            @RequestParam String action,
            @RequestParam String riskLevel) {

        // 先查具体风险等级
        LambdaQueryWrapper<ReleaseEnvironmentPolicy> specificWrapper = new LambdaQueryWrapper<>();
        specificWrapper.eq(ReleaseEnvironmentPolicy::getEnvId, envId)
                       .eq(ReleaseEnvironmentPolicy::getAction, action)
                       .eq(ReleaseEnvironmentPolicy::getRiskLevel, riskLevel)
                       .eq(ReleaseEnvironmentPolicy::getEnabled, true);

        ReleaseEnvironmentPolicy policy = policyMapper.selectOne(specificWrapper);

        // 再查 ALL 兜底
        if (policy == null) {
            LambdaQueryWrapper<ReleaseEnvironmentPolicy> allWrapper = new LambdaQueryWrapper<>();
            allWrapper.eq(ReleaseEnvironmentPolicy::getEnvId, envId)
                      .eq(ReleaseEnvironmentPolicy::getAction, action)
                      .eq(ReleaseEnvironmentPolicy::getRiskLevel, "ALL")
                      .eq(ReleaseEnvironmentPolicy::getEnabled, true);
            policy = policyMapper.selectOne(allWrapper);
        }

        if (policy == null) {
            return ApiResponse.success(null, TraceIdHolder.get());
        }

        return ApiResponse.success(toDto(policy), TraceIdHolder.get());
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        policyMapper.deleteById(id);
        return ApiResponse.success(null, TraceIdHolder.get());
    }

    private ReleaseEnvironmentPolicyDto toDto(ReleaseEnvironmentPolicy policy) {
        ReleaseEnvironmentPolicyDto dto = new ReleaseEnvironmentPolicyDto();
        BeanUtils.copyProperties(policy, dto);
        return dto;
    }
}
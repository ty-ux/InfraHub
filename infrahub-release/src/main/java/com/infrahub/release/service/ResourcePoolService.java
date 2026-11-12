package com.infrahub.release.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.infrahub.base.entity.BaseEnv;
import com.infrahub.base.service.BaseEnvService;
import com.infrahub.release.dto.ResourcePoolCapacityResponse;
import com.infrahub.release.entity.ResourcePool;
import com.infrahub.release.mapper.ResourcePoolMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ResourcePoolService extends ServiceImpl<ResourcePoolMapper, ResourcePool> {

    private final BaseEnvService baseEnvService;

    public ResourcePoolCapacityResponse getCapacity(Long resourcePoolId) {
        ResourcePool pool = this.getById(resourcePoolId);
        if (pool == null) {
            return null;
        }

        long hostCount = baseMapper.countHostsByPoolId(resourcePoolId);
        long onlineHostCount = baseMapper.countOnlineHostsByPoolId(resourcePoolId);
        long offlineHostCount = baseMapper.countOfflineHostsByPoolId(resourcePoolId);
        long unknownHostCount = baseMapper.countUnknownHostsByPoolId(resourcePoolId);
        long runtimeInstanceCount = baseMapper.countRuntimeInstancesByPoolId(resourcePoolId);

        String envName = null;
        Long envId = null;
        if (hostCount > 0) {
            Optional<Long> envIdOpt = baseMapper.findFirstEnvIdByPoolId(resourcePoolId);
            if (envIdOpt.isPresent()) {
                envId = envIdOpt.get();
                BaseEnv env = baseEnvService.getById(envId);
                if (env != null) {
                    envName = env.getEnvName();
                }
            }
        }

        BigDecimal cpuTotal = pool.getTotalCpu();
        BigDecimal cpuUsed = pool.getUsedCpu();
        BigDecimal cpuUsagePercent = calculateUsagePercent(cpuUsed, cpuTotal);

        BigDecimal memoryTotalGb = pool.getTotalMemoryGb();
        BigDecimal memoryUsedGb = pool.getUsedMemoryGb();
        BigDecimal memoryUsagePercent = calculateUsagePercent(memoryUsedGb, memoryTotalGb);

        BigDecimal diskTotalGb = pool.getTotalDiskGb();
        BigDecimal diskUsedGb = pool.getUsedDiskGb();
        BigDecimal diskUsagePercent = calculateUsagePercent(diskUsedGb, diskTotalGb);

        return ResourcePoolCapacityResponse.builder()
                .resourcePoolId(pool.getId())
                .resourcePoolName(pool.getPoolName())
                .poolCode(pool.getPoolCode())
                .envId(envId)
                .envName(envName)
                .hostCount(hostCount)
                .onlineHostCount(onlineHostCount)
                .offlineHostCount(offlineHostCount)
                .unknownHostCount(unknownHostCount)
                .runtimeInstanceCount(runtimeInstanceCount)
                .cpuTotal(cpuTotal)
                .cpuUsed(cpuUsed)
                .cpuUsagePercent(cpuUsagePercent)
                .memoryTotalGb(memoryTotalGb)
                .memoryUsedGb(memoryUsedGb)
                .memoryUsagePercent(memoryUsagePercent)
                .diskTotalGb(diskTotalGb)
                .diskUsedGb(diskUsedGb)
                .diskUsagePercent(diskUsagePercent)
                .build();
    }

    private BigDecimal calculateUsagePercent(BigDecimal used, BigDecimal total) {
        if (used == null || total == null || total.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        return used.multiply(BigDecimal.valueOf(100))
                .divide(total, 2, RoundingMode.HALF_UP);
    }
}

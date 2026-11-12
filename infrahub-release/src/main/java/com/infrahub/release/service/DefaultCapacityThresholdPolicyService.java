package com.infrahub.release.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.infrahub.release.dto.CapacityThreshold;
import com.infrahub.release.dto.ResourcePoolCapacityResponse;
import com.infrahub.release.entity.CapacityPolicy;
import com.infrahub.release.mapper.CapacityPolicyMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * 数据库驱动的容量阈值策略服务实现
 * 策略优先级：resourcePoolId 策略 > envId 策略 > 默认阈值
 * 只使用 enabled=true 的策略
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultCapacityThresholdPolicyService implements CapacityThresholdPolicyService {

    private final CapacityPolicyMapper capacityPolicyMapper;
    private final ResourcePoolService resourcePoolService;

    @Override
    public CapacityThreshold getThreshold(Long resourcePoolId) {
        if (resourcePoolId == null) {
            return CapacityThreshold.defaultThreshold();
        }

        // 获取资源池的容量信息以获得 envId
        ResourcePoolCapacityResponse capacity = resourcePoolService.getCapacity(resourcePoolId);
        Long envId = capacity != null ? capacity.getEnvId() : null;

        // 1. 优先查找 resourcePoolId 专属策略
        List<CapacityPolicy> poolPolicies = findEnabledPoliciesByPoolId(resourcePoolId);
        if (!poolPolicies.isEmpty()) {
            return buildThresholdFromPolicies(poolPolicies);
        }

        // 2. 其次查找 envId 策略
        if (envId != null) {
            List<CapacityPolicy> envPolicies = findEnabledPoliciesByEnvId(envId);
            if (!envPolicies.isEmpty()) {
                return buildThresholdFromPolicies(envPolicies);
            }
        }

        // 3. 最后使用默认阈值
        return CapacityThreshold.defaultThreshold();
    }

    private List<CapacityPolicy> findEnabledPoliciesByPoolId(Long resourcePoolId) {
        LambdaQueryWrapper<CapacityPolicy> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(CapacityPolicy::getResourcePoolId, resourcePoolId)
               .eq(CapacityPolicy::getEnabled, true);
        return capacityPolicyMapper.selectList(wrapper);
    }

    private List<CapacityPolicy> findEnabledPoliciesByEnvId(Long envId) {
        LambdaQueryWrapper<CapacityPolicy> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(CapacityPolicy::getEnvId, envId)
               .isNull(CapacityPolicy::getResourcePoolId)
               .eq(CapacityPolicy::getEnabled, true);
        return capacityPolicyMapper.selectList(wrapper);
    }

    private CapacityThreshold buildThresholdFromPolicies(List<CapacityPolicy> policies) {
        CapacityThreshold threshold = CapacityThreshold.defaultThreshold();

        for (CapacityPolicy policy : policies) {
            String metricType = policy.getMetricType();
            BigDecimal warning = new BigDecimal(policy.getWarningThreshold());
            BigDecimal critical = new BigDecimal(policy.getCriticalThreshold());

            switch (metricType) {
                case "CPU":
                    threshold.setCpuWarningThreshold(warning);
                    threshold.setCpuCriticalThreshold(critical);
                    break;
                case "MEMORY":
                    threshold.setMemoryWarningThreshold(warning);
                    threshold.setMemoryCriticalThreshold(critical);
                    break;
                case "DISK":
                    threshold.setDiskWarningThreshold(warning);
                    threshold.setDiskCriticalThreshold(critical);
                    break;
                case "HOST_OFFLINE":
                    threshold.setOfflineHostWarningThreshold(policy.getWarningThreshold().longValue());
                    threshold.setOfflineHostCriticalThreshold(policy.getCriticalThreshold().longValue());
                    break;
                case "UNKNOWN_HOST":
                    threshold.setUnknownHostWarningThreshold(policy.getWarningThreshold().longValue());
                    threshold.setUnknownHostCriticalThreshold(policy.getCriticalThreshold().longValue());
                    break;
                default:
                    log.warn("Unknown metric type in policy: {}", metricType);
            }
        }

        return threshold;
    }
}
package com.infrahub.release.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.infrahub.base.service.BaseEnvService;
import com.infrahub.common.error.ErrorCode;
import com.infrahub.common.exception.BizException;
import com.infrahub.release.dto.CapacityPolicyQuery;
import com.infrahub.release.dto.CapacityPolicyRequest;
import com.infrahub.release.entity.CapacityPolicy;
import com.infrahub.release.mapper.CapacityPolicyMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Set;

@Service
@RequiredArgsConstructor
public class CapacityPolicyService extends ServiceImpl<CapacityPolicyMapper, CapacityPolicy> {

    private static final Set<String> VALID_METRIC_TYPES = Set.of("CPU", "MEMORY", "DISK", "HOST_OFFLINE", "UNKNOWN_HOST");

    private final BaseEnvService baseEnvService;
    private final ResourcePoolService resourcePoolService;

    /**
     * Create a new policy.
     *
     * @param request the policy request
     * @return the created entity
     * @throws BizException if validation fails
     */
    public CapacityPolicy create(CapacityPolicyRequest request) {
        validateMetricType(request.getMetricType());
        validateThresholds(request.getWarningThreshold(), request.getCriticalThreshold());
        validateEnvOrResourcePool(request.getEnvId(), request.getResourcePoolId());

        if (request.getResourcePoolId() != null) {
            validateResourcePoolExists(request.getResourcePoolId());
        }
        if (request.getEnvId() != null) {
            validateEnvExists(request.getEnvId());
        }

        CapacityPolicy policy = new CapacityPolicy();
        policy.setPolicyName(request.getPolicyName());
        policy.setResourcePoolId(request.getResourcePoolId());
        policy.setEnvId(request.getEnvId());
        policy.setMetricType(request.getMetricType());
        policy.setWarningThreshold(request.getWarningThreshold());
        policy.setCriticalThreshold(request.getCriticalThreshold());
        policy.setEnabled(request.getEnabled() != null ? request.getEnabled() : true);
        policy.setDescription(request.getDescription());

        baseMapper.insert(policy);
        return policy;
    }

    /**
     * Update existing policy (idempotent).
     *
     * @param id the policy id
     * @param request the policy request
     * @return the updated entity
     * @throws BizException if not found or validation fails
     */
    public CapacityPolicy update(Long id, CapacityPolicyRequest request) {
        CapacityPolicy policy = getById(id);
        if (policy == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "策略不存在: " + id);
        }

        validateMetricType(request.getMetricType());
        validateThresholds(request.getWarningThreshold(), request.getCriticalThreshold());
        validateEnvOrResourcePool(request.getEnvId(), request.getResourcePoolId());

        if (request.getResourcePoolId() != null) {
            validateResourcePoolExists(request.getResourcePoolId());
        }
        if (request.getEnvId() != null) {
            validateEnvExists(request.getEnvId());
        }

        policy.setPolicyName(request.getPolicyName());
        policy.setResourcePoolId(request.getResourcePoolId());
        policy.setEnvId(request.getEnvId());
        policy.setMetricType(request.getMetricType());
        policy.setWarningThreshold(request.getWarningThreshold());
        policy.setCriticalThreshold(request.getCriticalThreshold());
        if (request.getEnabled() != null) {
            policy.setEnabled(request.getEnabled());
        }
        policy.setDescription(request.getDescription());

        baseMapper.updateById(policy);
        return policy;
    }

    /**
     * Enable/disable policy.
     *
     * @param id the policy id
     * @param enabled the enabled state
     * @return the updated entity
     * @throws BizException if not found
     */
    public CapacityPolicy toggle(Long id, boolean enabled) {
        CapacityPolicy policy = getById(id);
        if (policy == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "策略不存在: " + id);
        }

        policy.setEnabled(enabled);
        baseMapper.updateById(policy);
        return policy;
    }

    /**
     * Query policies with filters.
     *
     * @param query the query parameters
     * @return page of policies
     */
    public Page<CapacityPolicy> query(CapacityPolicyQuery query) {
        LambdaQueryWrapper<CapacityPolicy> wrapper = new LambdaQueryWrapper<>();

        if (query.getResourcePoolId() != null) {
            wrapper.eq(CapacityPolicy::getResourcePoolId, query.getResourcePoolId());
        }
        if (query.getEnvId() != null) {
            wrapper.eq(CapacityPolicy::getEnvId, query.getEnvId());
        }
        if (query.getMetricType() != null && !query.getMetricType().isEmpty()) {
            wrapper.eq(CapacityPolicy::getMetricType, query.getMetricType());
        }
        if (query.getEnabled() != null) {
            wrapper.eq(CapacityPolicy::getEnabled, query.getEnabled());
        }

        wrapper.orderByDesc(CapacityPolicy::getCreatedAt);

        return baseMapper.selectPage(new Page<>(query.getPageNum(), query.getPageSize()), wrapper);
    }

    /**
     * Get single policy by ID.
     *
     * @param id the policy id
     * @return the entity or null if not found
     */
    @Override
    public CapacityPolicy getById(java.io.Serializable id) {
        return baseMapper.selectById(id);
    }

    private void validateMetricType(String metricType) {
        if (metricType == null || !VALID_METRIC_TYPES.contains(metricType)) {
            throw new BizException(ErrorCode.VALIDATION_ERROR,
                    "无效的指标类型: " + metricType + ", 必须是: " + VALID_METRIC_TYPES);
        }
    }

    private void validateThresholds(Integer warningThreshold, Integer criticalThreshold) {
        if (warningThreshold == null || criticalThreshold == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "阈值不能为空");
        }
        if (warningThreshold < 0 || warningThreshold > 100 || criticalThreshold < 0 || criticalThreshold > 100) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "阈值必须在0-100之间");
        }
        if (warningThreshold >= criticalThreshold) {
            throw new BizException(ErrorCode.VALIDATION_ERROR,
                    "警告阈值必须小于严重阈值: warning=" + warningThreshold + ", critical=" + criticalThreshold);
        }
    }

    private void validateEnvOrResourcePool(Long envId, Long resourcePoolId) {
        if (envId == null && resourcePoolId == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "必须指定envId或resourcePoolId至少一个");
        }
    }

    private void validateResourcePoolExists(Long resourcePoolId) {
        if (resourcePoolService.getById(resourcePoolId) == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "资源池不存在: " + resourcePoolId);
        }
    }

    private void validateEnvExists(Long envId) {
        if (baseEnvService.getById(envId) == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "环境不存在: " + envId);
        }
    }
}

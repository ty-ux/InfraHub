package com.infrahub.release.service;

import com.infrahub.release.dto.CapacityThreshold;

/**
 * 容量阈值策略服务接口 (H-A)
 * H-B 依赖此接口获取阈值配置
 * 当前提供默认实现，H-A 完成后可替换为策略驱动实现
 */
public interface CapacityThresholdPolicyService {

    /**
     * 获取指定资源池的容量阈值配置
     *
     * @param resourcePoolId 资源池ID
     * @return 容量阈值配置，如果未配置则返回默认值
     */
    CapacityThreshold getThreshold(Long resourcePoolId);
}
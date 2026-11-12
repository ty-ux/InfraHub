package com.infrahub.release.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.infrahub.release.dto.MonitorMetricTargetQuery;
import com.infrahub.release.entity.MonitorMetricTarget;
import com.infrahub.release.mapper.MonitorMetricTargetMapper;
import org.springframework.stereotype.Service;

@Service
public class MonitorMetricTargetService extends ServiceImpl<MonitorMetricTargetMapper, MonitorMetricTarget> {

    public Page<MonitorMetricTarget> page(MonitorMetricTargetQuery query) {
        LambdaQueryWrapper<MonitorMetricTarget> wrapper = new LambdaQueryWrapper<>();

        if (query.getTargetType() != null && !query.getTargetType().isBlank()) {
            wrapper.eq(MonitorMetricTarget::getTargetType, query.getTargetType());
        }
        if (query.getTargetId() != null) {
            wrapper.eq(MonitorMetricTarget::getTargetId, query.getTargetId());
        }
        if (query.getStatus() != null && !query.getStatus().isBlank()) {
            wrapper.eq(MonitorMetricTarget::getStatus, query.getStatus());
        }
        if (query.getKeyword() != null && !query.getKeyword().isBlank()) {
            wrapper.like(MonitorMetricTarget::getMonitorKey, query.getKeyword());
        }

        wrapper.orderByDesc(MonitorMetricTarget::getCreatedAt);

        return page(new Page<>(query.getPageNum(), query.getPageSize()), wrapper);
    }
}

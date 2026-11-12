package com.infrahub.release.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.infrahub.release.dto.CreateManualSnapshotRequest;
import com.infrahub.release.dto.MonitorSnapshotQuery;
import com.infrahub.release.entity.MonitorSnapshot;
import com.infrahub.release.mapper.MonitorSnapshotMapper;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;

@Service
public class MonitorSnapshotService extends ServiceImpl<MonitorSnapshotMapper, MonitorSnapshot> {

    public Page<MonitorSnapshot> page(MonitorSnapshotQuery query) {
        return baseMapper.selectSnapshotPage(new Page<>(query.getPageNum(), query.getPageSize()), query);
    }

    public Long createManualSnapshot(CreateManualSnapshotRequest request) {
        MonitorSnapshot snapshot = new MonitorSnapshot();
        snapshot.setTargetType(request.getTargetType());
        snapshot.setTargetId(request.getTargetId());
        snapshot.setMetricPayload(request.getMetricPayload());
        snapshot.setSource(request.getSource() != null ? request.getSource() : "MANUAL");
        snapshot.setSnapshotTime(OffsetDateTime.now());
        snapshot.setCreatedAt(OffsetDateTime.now());
        baseMapper.insertManualSnapshot(snapshot);
        return snapshot.getId();
    }
}

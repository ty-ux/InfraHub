package com.infrahub.release.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName(value = "monitor_snapshot", autoResultMap = true)
public class MonitorSnapshot {

    private Long id;
    private String targetType;
    private Long targetId;
    private OffsetDateTime snapshotTime;

    private String metricPayload;

    private String source;
    private OffsetDateTime createdAt;
}

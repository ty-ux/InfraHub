package com.infrahub.release.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName("monitor_metric_target")
public class MonitorMetricTarget {

    private Long id;
    private String targetType;
    private Long targetId;
    private String monitorKey;
    private String scrapeConfig;
    private String status;
    private OffsetDateTime createdAt;
}

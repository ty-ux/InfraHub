package com.infrahub.release.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Data
@TableName("monitor_alert_rule")
public class MonitorAlertRule {

    private Long id;
    private String ruleName;
    private String targetType;
    private Long targetId;
    private String targetGroupCode;
    private String metricName;
    private String compareOp;
    private BigDecimal thresholdValue;
    private Integer durationSeconds;
    private String level;
    private String notifyChannel;
    private Boolean enabled;
    private OffsetDateTime createdAt;
}

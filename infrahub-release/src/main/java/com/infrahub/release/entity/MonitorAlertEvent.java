package com.infrahub.release.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Data
@TableName("monitor_alert_event")
public class MonitorAlertEvent {

    private Long id;
    private Long ruleId;
    private String targetType;
    private Long targetId;
    private String alertStatus;
    private BigDecimal alertValue;
    private String summary;
    private OffsetDateTime firedAt;
    private OffsetDateTime recoveredAt;
    private Long handleBy;
    private String handleNote;
}

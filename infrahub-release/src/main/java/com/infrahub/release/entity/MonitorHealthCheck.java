package com.infrahub.release.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName("monitor_health_check")
public class MonitorHealthCheck {

    private Long id;
    private String targetType;
    private Long targetId;
    private String checkType;
    private String checkEndpoint;
    private String expectedResult;
    private Integer timeoutSeconds;
    private Boolean enabled;
    private OffsetDateTime createdAt;
}

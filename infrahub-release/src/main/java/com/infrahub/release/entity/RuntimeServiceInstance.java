package com.infrahub.release.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName("runtime_service_instance")
public class RuntimeServiceInstance {

    private Long id;
    private Long serviceId;
    private Long envId;
    private Long hostId;
    private String instanceName;
    private String instanceType;
    private Long versionId;
    private String instanceStatus;
    private String healthStatus;
    private OffsetDateTime startTime;
    private OffsetDateTime lastHeartbeat;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}

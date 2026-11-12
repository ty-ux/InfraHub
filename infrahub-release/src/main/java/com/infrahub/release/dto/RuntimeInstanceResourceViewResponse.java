package com.infrahub.release.dto;

import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 运行实例资源视图响应 DTO
 * 聚合服务实例、环境、主机、资源池、当前版本、部署记录、监控快照等信息
 */
@Data
public class RuntimeInstanceResourceViewResponse {

    // 实例基本信息
    private Long instanceId;
    private String instanceName;
    private String instanceStatus;
    private String healthStatus;

    // 服务信息
    private Long serviceId;
    private String serviceName;

    // 环境信息
    private Long envId;
    private String envName;

    // 主机信息
    private Long hostId;
    private String hostname;
    private String ip;

    // 资源池信息（可空）
    private Long resourcePoolId;
    private String resourcePoolName;

    // 当前版本信息（可空）
    private Long currentVersionId;
    private String currentVersionNo;

    // 最近部署记录（可空）
    private Long latestDeployRecordId;
    private String latestDeployStatus;
    private OffsetDateTime latestDeployAt;

    // 监控状态（可空）
    private OffsetDateTime latestMonitorSnapshotAt;
    private String monitorStatus;
}

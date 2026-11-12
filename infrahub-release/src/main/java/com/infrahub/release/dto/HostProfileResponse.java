package com.infrahub.release.dto;

import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 主机画像响应DTO
 * 包含主机基础信息、环境/资源池上下文、运行实例统计、最近部署/监控信息及健康状态
 */
@Data
public class HostProfileResponse {

    private Long hostId;
    private String hostname;
    private String ip;

    private Long envId;
    private String envName;
    private Long resourcePoolId;
    private String resourcePoolName;

    private Integer runtimeInstanceCount;

    private OffsetDateTime latestDeployAt;
    private String latestDeployStatus;

    private OffsetDateTime latestMonitorSnapshotAt;

    private String healthStatus;
    private String healthReason;
}

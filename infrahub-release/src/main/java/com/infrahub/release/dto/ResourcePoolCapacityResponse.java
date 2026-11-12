package com.infrahub.release.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class ResourcePoolCapacityResponse {

    private Long resourcePoolId;
    private String resourcePoolName;
    private String poolCode;

    private Long envId;
    private String envName;

    private Long hostCount;
    private Long onlineHostCount;
    private Long offlineHostCount;
    private Long unknownHostCount;

    private Long runtimeInstanceCount;

    private BigDecimal cpuTotal;
    private BigDecimal cpuUsed;
    private BigDecimal cpuUsagePercent;

    private BigDecimal memoryTotalGb;
    private BigDecimal memoryUsedGb;
    private BigDecimal memoryUsagePercent;

    private BigDecimal diskTotalGb;
    private BigDecimal diskUsedGb;
    private BigDecimal diskUsagePercent;
}

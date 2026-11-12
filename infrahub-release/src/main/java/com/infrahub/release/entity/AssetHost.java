package com.infrahub.release.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Data
@TableName("asset_host")
public class AssetHost {

    private Long id;
    private String hostname;
    private String ip;
    private String osType;
    private BigDecimal cpuCores;
    private BigDecimal memoryGb;
    private BigDecimal diskGb;
    private String hostType;
    private String hostStatus;
    private Long envId;
    private Long resourcePoolId;
    private String agentStatus;
    private OffsetDateTime lastHeartbeat;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}

package com.infrahub.release.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Data
@TableName("asset_resource_pool")
public class ResourcePool {

    private Long id;
    private String poolCode;
    private String poolName;
    private String poolType;
    private String region;
    private BigDecimal totalCpu;
    private BigDecimal totalMemoryGb;
    private BigDecimal totalDiskGb;
    private BigDecimal usedCpu;
    private BigDecimal usedMemoryGb;
    private BigDecimal usedDiskGb;
    private String status;
    private OffsetDateTime createdAt;
}

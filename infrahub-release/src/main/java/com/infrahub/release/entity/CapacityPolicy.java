package com.infrahub.release.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.OffsetDateTime;

@Data
@TableName("resource_capacity_policy")
public class CapacityPolicy {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String policyName;
    private Long resourcePoolId;
    private Long envId;
    private String metricType;
    private Integer warningThreshold;
    private Integer criticalThreshold;
    private Boolean enabled;
    private String description;

    @TableField(fill = FieldFill.INSERT)
    private Long createdBy;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;
}

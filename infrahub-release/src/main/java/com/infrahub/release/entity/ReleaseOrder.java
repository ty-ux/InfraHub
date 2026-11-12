package com.infrahub.release.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName("release_order")
public class ReleaseOrder {

    private Long id;
    private String orderNo;
    private Long appId;
    private Long serviceId;
    private Long envId;
    private Long versionId;
    private String releaseType;
    private String status;
    private String rollbackStatus;
    private Integer rollbackCount;
    private OffsetDateTime lastRollbackAt;
    private String riskLevel;
    private String executorType;
    private Long executorConfigId;
    private Long approvedBy;
    private OffsetDateTime approvedAt;
    @TableField(fill = FieldFill.INSERT)
    private Long createdBy;
    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;
}

package com.infrahub.release.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName("release_canary_plan")
public class ReleaseCanaryPlan {

    private Long id;
    private Long releaseOrderId;
    private Integer batchCount;
    private Integer batchIntervalSeconds;
    private Boolean autoRollbackEnabled;
    private Boolean qualityGateEnabled;
    private Integer maxFiringAlerts;
    private String gateFailureAction;
    private String status;
    private Long rollbackOrderId;
    private String failureReason;
    @TableField(fill = FieldFill.INSERT)
    private Long createdBy;
    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;

    public static final String STATUS_CREATED = "CREATED";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_PAUSED = "PAUSED";
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_ROLLED_BACK = "ROLLED_BACK";
    public static final String STATUS_BLOCKED = "BLOCKED";

    public static final String GATE_ACTION_BLOCK = "BLOCK";
    public static final String GATE_ACTION_BLOCK_AND_ROLLBACK = "BLOCK_AND_ROLLBACK";
}
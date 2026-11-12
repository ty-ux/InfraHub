package com.infrahub.release.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName("release_canary_batch")
public class ReleaseCanaryBatch {

    private Long id;
    private Long planId;
    private Long releaseOrderId;
    private Integer batchNo;
    private String instanceIds;
    private String status;
    private OffsetDateTime startedAt;
    private OffsetDateTime endedAt;
    private String failureReason;
    private String qualityGateStatus;
    private String qualityGateReason;
    private OffsetDateTime qualityGateEvaluatedAt;
    private Long monitorSnapshotId;
    private String monitorAlertEventIds;
    private String qualityMetricsPayload;
    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_SKIPPED = "SKIPPED";

    public static final String GATE_STATUS_NOT_EVALUATED = "NOT_EVALUATED";
    public static final String GATE_STATUS_PASSED = "PASSED";
    public static final String GATE_STATUS_FAILED = "FAILED";
    public static final String GATE_STATUS_SKIPPED = "SKIPPED";
}
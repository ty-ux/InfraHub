package com.infrahub.release.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 灰度门禁评估历史实体
 * E3.1: 每次门禁评估（首次/重评估）都落一条记录
 */
@Data
@TableName("release_canary_gate_evaluation")
public class ReleaseCanaryGateEvaluation {

    public static final String TYPE_FIRST_EVALUATION = "FIRST_EVALUATION";
    public static final String TYPE_RE_EVALUATION = "RE_EVALUATION";

    public static final String RESULT_PASSED = "PASSED";
    public static final String RESULT_FAILED = "FAILED";
    public static final String RESULT_SKIPPED = "SKIPPED";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long releaseOrderId;

    private Long planId;

    private Long batchId;

    private String evaluationType;

    private String gateResult;

    private Integer hitCount;

    private Integer threshold;

    private Long monitorSnapshotId;

    private String monitorAlertEventIds;

    private String metricsPayload;

    private String reason;

    private Long evaluatedBy;

    private OffsetDateTime evaluatedAt;

    private OffsetDateTime createdAt;
}

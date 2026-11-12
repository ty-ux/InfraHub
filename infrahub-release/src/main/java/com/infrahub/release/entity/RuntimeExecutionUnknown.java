package com.infrahub.release.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 一次“远端结果无法证明”的执行事实。
 *
 * <p>存在的理由：{@code runtime_deploy_record.deploy_status='UNKNOWN'} 能说明某条记录未知，
 * 但没有地方保存“这条未知是否已经人工核对并解除”。把解除信息写进部署日志或审计自由文本，
 * 既无法做唯一性约束，也会被后续日志覆盖。未解除的行是阻断其它冲突入口的唯一权威事实。
 */
@Data
@TableName("runtime_execution_unknown")
public class RuntimeExecutionUnknown {

    public static final String STATUS_UNRESOLVED = "UNRESOLVED";
    public static final String STATUS_RESOLVED = "RESOLVED";

    public static final String SOURCE_RELEASE = "RELEASE";
    public static final String SOURCE_ROLLBACK = "ROLLBACK";

    public static final String DETECTION_EVIDENCE = "EVIDENCE";
    public static final String DETECTION_EXCEPTION = "EXCEPTION";
    public static final String DETECTION_TERMINAL_CLEANUP = "TERMINAL_CLEANUP";
    public static final String DETECTION_RECOVERY = "RECOVERY";

    public static final String OUTCOME_APPLIED = "REMOTE_EFFECT_APPLIED";
    public static final String OUTCOME_NOT_APPLIED = "REMOTE_EFFECT_NOT_APPLIED";
    public static final String OUTCOME_PARTIAL = "REMOTE_EFFECT_PARTIAL";

    private Long id;
    private Long serviceId;
    private Long envId;
    private String sourceType;
    private Long releaseOrderId;
    private Long rollbackOrderId;
    private Long deployRecordId;
    private Long planId;
    private Long batchId;
    private String status;
    private String detectionSource;
    private String detectionReason;
    private Long detectedBy;
    private OffsetDateTime detectedAt;
    private String resolutionOutcome;
    private String resolutionNote;
    private Long resolvedBy;
    private OffsetDateTime resolvedAt;
    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;
}

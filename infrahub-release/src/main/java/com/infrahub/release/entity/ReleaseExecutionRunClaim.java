package com.infrahub.release.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 一次发布执行的**一次性认领凭证**。
 *
 * <p>存在的理由：{@code pipeline_run.run_status='RUNNING'} 与 {@code release_order.status='RUNNING'}
 * 都是可观察状态，不是凭证。另一个调用方可以在“检查通过”与“调用远端”之间读到同样的状态，
 * 于是两个调用方都以为这次执行是自己的。一行在远端调用之前写入、调用结束后才释放的 HELD 记录
 * 才能关掉这个窗口，而且不需要跨 SSH 持有数据库事务。
 *
 * <p>每个执行身份只有一行：{@code release_order} 只能从 CREATED/APPROVED 变成一次 RUNNING，
 * 因此同一发布单的第二次认领必须被唯一索引挡住——这正是“同一运行的重复步骤、重复投递、
 * 并发运行不能再次执行”的落地方式。
 *
 * <p>本表只描述“谁在执行”，不描述“远端是否已停止”。{@code release_outcome='ABANDONED'}
 * 表示操作人放弃认领，不表示远端动作已经结束。
 */
@Data
@TableName("release_execution_run_claim")
public class ReleaseExecutionRunClaim {

    public static final String STATUS_HELD = "HELD";
    public static final String STATUS_RELEASED = "RELEASED";

    public static final String PURPOSE_DEPLOY = "DEPLOY";
    public static final String PURPOSE_ROLLBACK = "ROLLBACK";

    public static final String HOLDER_PIPELINE_STEP = "PIPELINE_STEP";

    public static final String OUTCOME_COMPLETED = "COMPLETED";
    public static final String OUTCOME_ABANDONED = "ABANDONED";

    private Long id;
    private Long releaseOrderId;
    private Long serviceId;
    private Long envId;
    private String claimPurpose;
    private String holderKind;
    private String holderToken;
    private Long pipelineRunId;
    private Long pipelineStepRunId;
    private Long rollbackOrderId;
    private String status;
    private String releaseOutcome;
    private String releaseReason;
    private Long releasedBy;
    private OffsetDateTime claimedAt;
    private OffsetDateTime releasedAt;
    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;
}

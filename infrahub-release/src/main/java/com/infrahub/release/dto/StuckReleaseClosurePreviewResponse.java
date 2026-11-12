package com.infrahub.release.dto;

import lombok.Data;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 卡住发布单的**收口前对账**（只读）。
 *
 * <p>回答的问题是：订单/认领/流水线运行/部署记录现在各是什么状态，人工给结论前平台能证明什么、
 * 不能证明什么。它**不做任何推断**：超时、进程无响应都不构成“远端已停止”的证据，因此这里
 * 只列事实与阻塞项，由操作者给出结论。
 *
 * <p>两个 {@code *Allowed} 字段是平台的准入判定，不是建议：为 false 时对应结论会被拒绝，
 * 原因在 {@code *RefusalReasons} 里逐条列出（可直接展示给操作者）。
 */
@Data
public class StuckReleaseClosurePreviewResponse {

    private Long releaseOrderId;
    private String orderStatus;
    private Long serviceId;
    private Long envId;
    private Long versionId;

    /** 该发布单的部署认领（可能不存在；不存在时说明没有一次可归属的执行）。 */
    private ClaimView claim;
    /** 认领指向的流水线运行（认领没有运行标识时为 null）。 */
    private RunView pipelineRun;

    private List<DeployRecordView> deployRecords = new ArrayList<>();
    private int runningRecordCount;
    private int successRecordCount;
    private int failedRecordCount;
    private int unknownRecordCount;
    /** 没有实例归属（没有归属行、或归属指向的实例已被移除）的记录数。 */
    private int recordCountWithoutInstanceAttribution;

    /** 本次执行可证明部署成功的实例（升序）。 */
    private List<Long> provenInstanceIds = new ArrayList<>();
    /** 该服务/环境当前存在、但本次未覆盖的实例（这些实例不会被更新版本）。 */
    private List<Long> currentInstanceIdsNotDeployed = new ArrayList<>();
    private boolean instanceAttributionProvable;
    /** 归属不可证明时的具体理由（半角分号串联），可直接展示。 */
    private String instanceAttributionRejectionReason;

    /** 本发布单名下未解除的未知事实条数（含合并进这些事实的其他批次登记）。 */
    private int unresolvedFactCountForOrder;
    /** 该服务/环境下未解除的未知事实条数（不限于本发布单）。 */
    private int unresolvedFactCountForServiceEnv;
    /** 该服务/环境下是否存在仍为 HELD 的部署认领。 */
    private boolean heldClaimOnServiceEnv;
    /** 该发布单是否还有未收敛的灰度计划。 */
    private boolean activeCanaryPlan;

    private boolean adoptSuccessAllowed;
    private List<String> adoptSuccessRefusalReasons = new ArrayList<>();
    private boolean closeAsFailedAllowed;
    private List<String> closeAsFailedRefusalReasons = new ArrayList<>();

    /**
     * 各条可选路径的准入判定：{@link ReleaseClosureRouteView#ROUTE_CLOSE_AS_FAILED 人工收口为失败}、
     * {@link ReleaseClosureRouteView#ROUTE_RESOLVE_AS_UNKNOWN 恢复为结果未知}、逐条解除未知事实、
     * 放弃 HELD 认领等。不可用的路径不会给出入口（{@code actionPath=null}），
     * 以免把操作者引到一条同样会被拒绝的路。
     */
    private List<ReleaseClosureRouteView> routes = new ArrayList<>();
    /** 是否存在现在就能提交的路径。 */
    private boolean anyRouteAvailable;
    /**
     * 一条可执行路径都没有时的明确说明：为什么没有、以及订单会怎样（保持 RUNNING 并继续阻断）。
     * 有路径时为空。它**不是**建议，而是死交的如实陈述。
     */
    private String noRouteReason;

    /** 一次性执行认领表是否可用（V32）。为 false 时不存在认领行，也没有“放弃认领”这个动作。 */
    private boolean claimTableAvailable;
    /** 结果未知登记表是否可用（V31）。为 false 时登记/恢复/解除都按失败关闭拒绝。 */
    private boolean unknownMarkerTableAvailable;
    /** 实例归属表是否可用（V34）。为 false 时任何成功结论都无法证明落在哪些实例上。 */
    private boolean instanceAttributionTableAvailable;
    /** 未知事实合并台账是否可用（V34）。为 false 时无法核实订单级事实是否吸收了其他批次。 */
    private boolean mergeLedgerAvailable;

    /** 展示用免责声明：平台没有探测远端。 */
    private String disclaimer;

    /** 部署认领视图。 */
    @Data
    public static class ClaimView {
        private Long claimId;
        private String status;
        private String releaseOutcome;
        private Long pipelineRunId;
        private String pipelineRunStatus;
        private OffsetDateTime claimedAt;
        private OffsetDateTime releasedAt;
    }

    /** 流水线运行视图（只读展示，不用于任何推断）。 */
    @Data
    public static class RunView {
        private Long pipelineRunId;
        private String runStatus;
        private OffsetDateTime startTime;
        private OffsetDateTime endTime;
    }

    /** 部署记录视图，带实例归属对账结果。 */
    @Data
    public static class DeployRecordView {
        private Long deployRecordId;
        private String deployStatus;
        private Integer deployBatch;
        private OffsetDateTime startedAt;
        private OffsetDateTime endedAt;
        /** 创建记录时写下的实例归属（没有归属行时为 null）。 */
        private Long instanceId;
        private String instanceName;
        /** ATTRIBUTED / MISSING / INSTANCE_REMOVED / INSTANCE_REBOUND / AMBIGUOUS。 */
        private String instanceAttribution;
    }
}

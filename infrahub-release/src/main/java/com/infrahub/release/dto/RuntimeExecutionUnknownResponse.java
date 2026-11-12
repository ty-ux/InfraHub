package com.infrahub.release.dto;

import com.infrahub.release.entity.RuntimeDeployRecord;
import com.infrahub.release.entity.RuntimeExecutionUnknown;
import com.infrahub.release.entity.RuntimeExecutionUnknownMerge;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 未解除的“结果未知”执行视图。
 *
 * <p>{@code legacy=true} 表示这条未知事实来自已有的 {@code runtime_deploy_record.deploy_status=UNKNOWN}，
 * 还没有对应的登记行；解除时必须按部署记录 ID 操作。{@code id} 为空不代表记录不存在。
 */
@Data
public class RuntimeExecutionUnknownResponse {

    public static final String FACT_LEVEL_ORDER = "ORDER_LEVEL";
    public static final String FACT_LEVEL_RECORD = "RECORD";

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
    private OffsetDateTime detectedAt;
    private Long detectedBy;
    private String resolutionOutcome;
    private String resolutionNote;
    private Long resolvedBy;
    private OffsetDateTime resolvedAt;
    /** 历史 UNKNOWN 部署记录没有登记行时为 true。 */
    private boolean legacy;
    /** 历史行的部署状态，便于页面确认。 */
    private String deployStatus;
    /**
     * 事实层级：{@code RECORD} 表示这条事实挂在一条具体部署记录上，
     * {@code ORDER_LEVEL} 表示它没有部署记录、只挂在订单上。
     *
     * <p>为什么必须显示：同一发布单可以同时存在两种层级的事实（例如某条部署记录写了 UNKNOWN，
     * 兜底又补了一条订单级登记）。它们是两条独立事实，必须逐条核对、逐条解除；
     * 解除其中一条不会、也不应该让另一条消失。只显示“有未知”会让操作者以为解除一次就放行了。
     */
    private String factLevel;
    /**
     * 这条订单级事实**吸收**了多少条别的登记（{@code runtime_execution_unknown_merge}）。
     *
     * <p>去重粒度决定了同一发布单下不同批次的订单级登记可能落到同一行：阻断不会消失，
     * 但“另一个批次也在未知中”这件事没有自己的行。解除这一条会把那些登记一并放行，
     * 因此必须让操作者在解除前就看到数量与明细，而不是解除后才发现。
     */
    private int mergedFactCount;
    /** 被吸收登记的明细（按合并顺序）。为空表示没有已留证的合并。 */
    private List<MergedFact> mergedFacts;
    /**
     * 合并台账是否可用（V34 是否已应用）。
     *
     * <p>为 false 时 {@code mergedFactCount} 与 {@code mergedFacts} **不是**“没有合并”，
     * 而是“无法核实”——页面必须照此表述，解除时也要求操作者显式确认。
     */
    private boolean mergeLedgerAvailable;

    /** 被吸收的登记：哪一次登记（来源、批次）没有独立留存。 */
    @Data
    public static class MergedFact {
        private Long unknownId;
        private String sourceType;
        private Long releaseOrderId;
        private Long rollbackOrderId;
        private Long planId;
        private Long batchId;
        private String detectionSource;
        private OffsetDateTime mergedAt;

        public static MergedFact fromMerge(RuntimeExecutionUnknownMerge row) {
            MergedFact fact = new MergedFact();
            fact.setUnknownId(row.getUnknownId());
            fact.setSourceType(row.getSourceType());
            fact.setReleaseOrderId(row.getReleaseOrderId());
            fact.setRollbackOrderId(row.getRollbackOrderId());
            fact.setPlanId(row.getPlanId());
            fact.setBatchId(row.getBatchId());
            fact.setDetectionSource(row.getDetectionSource());
            fact.setMergedAt(row.getMergedAt());
            return fact;
        }
    }

    public static RuntimeExecutionUnknownResponse fromEntity(RuntimeExecutionUnknown row) {
        RuntimeExecutionUnknownResponse response = new RuntimeExecutionUnknownResponse();
        response.setId(row.getId());
        response.setServiceId(row.getServiceId());
        response.setEnvId(row.getEnvId());
        response.setSourceType(row.getSourceType());
        response.setReleaseOrderId(row.getReleaseOrderId());
        response.setRollbackOrderId(row.getRollbackOrderId());
        response.setDeployRecordId(row.getDeployRecordId());
        response.setPlanId(row.getPlanId());
        response.setBatchId(row.getBatchId());
        response.setStatus(row.getStatus());
        response.setDetectionSource(row.getDetectionSource());
        response.setDetectionReason(row.getDetectionReason());
        response.setDetectedAt(row.getDetectedAt());
        response.setDetectedBy(row.getDetectedBy());
        response.setResolutionOutcome(row.getResolutionOutcome());
        response.setResolutionNote(row.getResolutionNote());
        response.setResolvedBy(row.getResolvedBy());
        response.setResolvedAt(row.getResolvedAt());
        response.setLegacy(false);
        response.setFactLevel(row.getDeployRecordId() == null ? FACT_LEVEL_ORDER : FACT_LEVEL_RECORD);
        return response;
    }

    public static RuntimeExecutionUnknownResponse fromLegacyRecord(RuntimeDeployRecord record, String reason) {
        RuntimeExecutionUnknownResponse response = new RuntimeExecutionUnknownResponse();
        response.setServiceId(record.getServiceId());
        response.setEnvId(record.getEnvId());
        response.setSourceType(record.getRollbackOrderId() == null
                ? RuntimeExecutionUnknown.SOURCE_RELEASE
                : RuntimeExecutionUnknown.SOURCE_ROLLBACK);
        response.setReleaseOrderId(record.getReleaseOrderId());
        response.setRollbackOrderId(record.getRollbackOrderId());
        response.setDeployRecordId(record.getId());
        response.setStatus(RuntimeExecutionUnknown.STATUS_UNRESOLVED);
        response.setDetectionSource("LEGACY");
        response.setDetectionReason(reason);
        response.setDetectedAt(record.getEndedAt() != null ? record.getEndedAt() : record.getCreatedAt());
        response.setLegacy(true);
        response.setDeployStatus(record.getDeployStatus());
        response.setFactLevel(FACT_LEVEL_RECORD);
        return response;
    }
}

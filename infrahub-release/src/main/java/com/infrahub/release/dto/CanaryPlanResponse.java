package com.infrahub.release.dto;

import com.infrahub.release.entity.ReleaseCanaryPlan;
import com.infrahub.release.support.CanaryAutoRollbackPolicy;
import com.infrahub.release.support.DeployExecutionOutcome;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;

@Data
@Builder
public class CanaryPlanResponse {

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
    /**
     * 计划级执行结果类别，取自计划失败原因开头的固定标记；没有标记时为 {@code null}（无信息）。
     */
    private String executionOutcome;
    /**
     * 计划自身的 {@code autoRollbackEnabled} 只是创建时的配置意愿，**不代表当前可用**：
     * 自动回滚在代码层被整体停用，本字段才是“现在是否会真的自动回滚”的事实。
     */
    private Boolean autoRollbackAvailable;
    private Long createdBy;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private List<CanaryBatchResponse> batches;

    public static CanaryPlanResponse fromEntity(ReleaseCanaryPlan plan, List<CanaryBatchResponse> batches) {
        return CanaryPlanResponse.builder()
                .id(plan.getId())
                .releaseOrderId(plan.getReleaseOrderId())
                .batchCount(plan.getBatchCount())
                .batchIntervalSeconds(plan.getBatchIntervalSeconds())
                .autoRollbackEnabled(plan.getAutoRollbackEnabled())
                .qualityGateEnabled(plan.getQualityGateEnabled())
                .maxFiringAlerts(plan.getMaxFiringAlerts())
                .gateFailureAction(plan.getGateFailureAction())
                .status(plan.getStatus())
                .rollbackOrderId(plan.getRollbackOrderId())
                .failureReason(plan.getFailureReason())
                .executionOutcome(outcomeCode(plan.getFailureReason()))
                .autoRollbackAvailable(CanaryAutoRollbackPolicy.isAvailable())
                .createdBy(plan.getCreatedBy())
                .createdAt(plan.getCreatedAt())
                .updatedAt(plan.getUpdatedAt())
                .batches(batches)
                .build();
    }

    private static String outcomeCode(String failureReason) {
        DeployExecutionOutcome outcome = DeployExecutionOutcome.fromMarker(failureReason);
        return outcome == null ? null : outcome.code();
    }
}
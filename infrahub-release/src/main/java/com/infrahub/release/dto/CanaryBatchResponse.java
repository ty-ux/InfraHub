package com.infrahub.release.dto;

import com.infrahub.release.entity.ReleaseCanaryBatch;
import com.infrahub.release.support.DeployExecutionOutcome;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;

@Data
@Builder
public class CanaryBatchResponse {

    private Long id;
    private Long planId;
    private Integer batchNo;
    private List<Long> instanceIds;
    private String status;
    private OffsetDateTime startedAt;
    private OffsetDateTime endedAt;
    private String failureReason;
    /**
     * 本次批次执行结果的类别（NOT_STARTED / SUCCEEDED / FAILED / PARTIAL / UNKNOWN）。
     *
     * <p>取自失败原因开头的固定标记，或批次已记录为 SUCCESS 这一确定事实；两者都没有时为
     * {@code null}，表示“没有可信的类别信息”——前端必须显示为未知，不能当成成功或失败。
     */
    private String executionOutcome;
    private String qualityGateStatus;
    private String qualityGateReason;
    private OffsetDateTime qualityGateEvaluatedAt;
    private Long monitorSnapshotId;
    private List<Long> monitorAlertEventIds;
    private Object qualityMetricsPayload;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    public static CanaryBatchResponse fromEntity(ReleaseCanaryBatch batch) {
        List<Long> instanceIdList = null;
        if (batch.getInstanceIds() != null && !batch.getInstanceIds().isBlank()) {
            try {
                instanceIdList = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readValue(batch.getInstanceIds(),
                                new com.fasterxml.jackson.core.type.TypeReference<List<Long>>() {});
            } catch (Exception ignored) {
                instanceIdList = List.of();
            }
        }

        List<Long> alertEventIds = null;
        if (batch.getMonitorAlertEventIds() != null && !batch.getMonitorAlertEventIds().isBlank()) {
            try {
                alertEventIds = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readValue(batch.getMonitorAlertEventIds(),
                                new com.fasterxml.jackson.core.type.TypeReference<List<Long>>() {});
            } catch (Exception ignored) {
                alertEventIds = List.of();
            }
        }

        Object metricsPayload = null;
        if (batch.getQualityMetricsPayload() != null && !batch.getQualityMetricsPayload().isBlank()) {
            try {
                metricsPayload = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readValue(batch.getQualityMetricsPayload(), Object.class);
            } catch (Exception ignored) {
                // keep null
            }
        }

        return CanaryBatchResponse.builder()
                .id(batch.getId())
                .planId(batch.getPlanId())
                .batchNo(batch.getBatchNo())
                .instanceIds(instanceIdList)
                .status(batch.getStatus())
                .startedAt(batch.getStartedAt())
                .endedAt(batch.getEndedAt())
                .failureReason(batch.getFailureReason())
                .executionOutcome(outcomeCode(batch))
                .qualityGateStatus(batch.getQualityGateStatus())
                .qualityGateReason(batch.getQualityGateReason())
                .qualityGateEvaluatedAt(batch.getQualityGateEvaluatedAt())
                .monitorSnapshotId(batch.getMonitorSnapshotId())
                .monitorAlertEventIds(alertEventIds)
                .qualityMetricsPayload(metricsPayload)
                .createdAt(batch.getCreatedAt())
                .updatedAt(batch.getUpdatedAt())
                .build();
    }

    private static String outcomeCode(ReleaseCanaryBatch batch) {
        DeployExecutionOutcome outcome = DeployExecutionOutcome.fromMarker(batch.getFailureReason());
        if (outcome != null) {
            return outcome.code();
        }
        if (ReleaseCanaryBatch.STATUS_SUCCESS.equals(batch.getStatus())) {
            return DeployExecutionOutcome.SUCCEEDED.code();
        }
        return null;
    }
}
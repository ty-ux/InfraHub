package com.infrahub.release.dto;

import com.infrahub.release.entity.ReleaseCanaryGateEvaluation;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 门禁评估历史响应 DTO
 * E3.1: 用于前端展示门禁历史记录
 */
@Data
@Builder
public class GateEvaluationHistoryResponse {

    private static final String TYPE_FIRST_EVALUATION = "FIRST_EVALUATION";
    private static final String TYPE_RE_EVALUATION = "RE_EVALUATION";
    private static final String RESULT_PASSED = "PASSED";
    private static final String RESULT_FAILED = "FAILED";
    private static final String RESULT_SKIPPED = "SKIPPED";

    private Long id;

    private Long batchId;

    private String evaluationType;

    private String evaluationTypeLabel;

    private String gateResult;

    private String gateResultLabel;

    private Integer hitCount;

    private Integer threshold;

    private Long monitorSnapshotId;

    private List<Long> monitorAlertEventIds;

    private Object metricsPayload;

    private String reason;

    private Long evaluatedBy;

    private String evaluatedByName;

    private OffsetDateTime evaluatedAt;

    public static GateEvaluationHistoryResponse fromEntity(ReleaseCanaryGateEvaluation entity) {
        List<Long> alertEventIds = null;
        if (entity.getMonitorAlertEventIds() != null && !entity.getMonitorAlertEventIds().isBlank()) {
            try {
                alertEventIds = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readValue(entity.getMonitorAlertEventIds(),
                                new com.fasterxml.jackson.core.type.TypeReference<List<Long>>() {});
            } catch (Exception ignored) {
                alertEventIds = List.of();
            }
        }

        Object metricsPayload = null;
        if (entity.getMetricsPayload() != null && !entity.getMetricsPayload().isBlank()) {
            try {
                metricsPayload = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readValue(entity.getMetricsPayload(), Object.class);
            } catch (Exception ignored) {
            }
        }

        return GateEvaluationHistoryResponse.builder()
                .id(entity.getId())
                .batchId(entity.getBatchId())
                .evaluationType(entity.getEvaluationType())
                .evaluationTypeLabel(getEvaluationTypeLabel(entity.getEvaluationType()))
                .gateResult(entity.getGateResult())
                .gateResultLabel(getGateResultLabel(entity.getGateResult()))
                .hitCount(entity.getHitCount())
                .threshold(entity.getThreshold())
                .monitorSnapshotId(entity.getMonitorSnapshotId())
                .monitorAlertEventIds(alertEventIds)
                .metricsPayload(metricsPayload)
                .reason(entity.getReason())
                .evaluatedBy(entity.getEvaluatedBy())
                .evaluatedAt(entity.getEvaluatedAt())
                .build();
    }

    private static String getEvaluationTypeLabel(String type) {
        if (TYPE_FIRST_EVALUATION.equals(type)) return "首次评估";
        if (TYPE_RE_EVALUATION.equals(type)) return "重评估";
        return type;
    }

    private static String getGateResultLabel(String result) {
        if (RESULT_PASSED.equals(result)) return "通过";
        if (RESULT_FAILED.equals(result)) return "未通过";
        if (RESULT_SKIPPED.equals(result)) return "已跳过";
        return result;
    }
}
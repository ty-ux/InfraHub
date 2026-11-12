package com.infrahub.release.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.infrahub.common.context.UserContextHolder;
import com.infrahub.common.error.ErrorCode;
import com.infrahub.common.exception.BizException;
import com.infrahub.release.dto.EvaluateMonitorRulesRequest;
import com.infrahub.release.dto.MonitorEvaluationResult;
import com.infrahub.release.entity.ReleaseCanaryBatch;
import com.infrahub.release.entity.ReleaseCanaryPlan;
import com.infrahub.release.entity.ReleaseOrder;
import com.infrahub.release.mapper.ReleaseCanaryBatchMapper;
import com.infrahub.release.mapper.SysAuditLogMapper;
import com.infrahub.release.service.ReleaseDeployExecutionResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReleaseCanaryQualityGateService {

    private static final String BIZ_TYPE_RELEASE_ORDER = "RELEASE_ORDER";
    private static final String ACTION_GATE_EVALUATED = "RELEASE_CANARY_GATE_EVALUATED";
    private static final String ACTION_GATE_PASSED = "RELEASE_CANARY_GATE_PASSED";
    private static final String ACTION_GATE_FAILED = "RELEASE_CANARY_GATE_FAILED";

    private static final String TARGET_TYPE_CANARY_BATCH = "RELEASE_CANARY_BATCH";

    private final MonitorSnapshotService monitorSnapshotService;
    private final MonitorEvaluationService monitorEvaluationService;
    private final ReleaseCanaryBatchMapper releaseCanaryBatchMapper;
    private final SysAuditLogMapper sysAuditLogMapper;
    private final ObjectMapper objectMapper;
    private final ReleaseCanaryGateEvaluationService gateEvaluationService;

    @Transactional
    public void evaluateBatch(ReleaseCanaryPlan plan, ReleaseCanaryBatch batch, ReleaseOrder order, ReleaseDeployExecutionResult deployResult) {
        if (!Boolean.TRUE.equals(plan.getQualityGateEnabled())) {
            batch.setQualityGateStatus(ReleaseCanaryBatch.GATE_STATUS_SKIPPED);
            batch.setQualityGateReason("质量门禁未启用");
            batch.setQualityGateEvaluatedAt(OffsetDateTime.now());
            releaseCanaryBatchMapper.updateById(batch);
            return;
        }

        // 1. 构造指标 payload：全部使用结果里的结构化计数，不解析失败消息文本
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("total_instances", deployResult.attemptedCount());
        metrics.put("success_instances", deployResult.succeededCount());
        metrics.put("failed_instances", deployResult.failedCount());
        metrics.put("unknown_instances", deployResult.unknownCount());
        metrics.put("not_started_instances", deployResult.notStartedCount());
        metrics.put("execution_outcome", deployResult.outcome().code());
        metrics.put("deploy_success_rate", deployResult.attemptedCount() == 0
                ? 0.0
                : (double) deployResult.succeededCount() / deployResult.attemptedCount());
        metrics.put("deploy_duration_seconds", 0); // 实际应从部署结果获取
        metrics.put("batch_no", batch.getBatchNo());
        metrics.put("release_order_id", order.getId());
        metrics.put("plan_id", plan.getId());
        metrics.put("service_id", order.getServiceId());
        metrics.put("env_id", order.getEnvId());
        metrics.put("version_id", order.getVersionId());

        String metricPayload;
        try {
            metricPayload = objectMapper.writeValueAsString(metrics);
        } catch (JsonProcessingException e) {
            batch.setQualityGateStatus(ReleaseCanaryBatch.GATE_STATUS_FAILED);
            batch.setQualityGateReason("指标序列化失败: " + e.getMessage());
            batch.setQualityGateEvaluatedAt(OffsetDateTime.now());
            releaseCanaryBatchMapper.updateById(batch);
            return;
        }

        // 2. 创建监控快照
        Long snapshotId = monitorSnapshotService.createManualSnapshot(
                new com.infrahub.release.dto.CreateManualSnapshotRequest(
                        TARGET_TYPE_CANARY_BATCH,
                        batch.getId(),
                        metricPayload,
                        "CANARY_GATE"
                )
        );
        batch.setMonitorSnapshotId(snapshotId);
        batch.setQualityMetricsPayload(metricPayload);

        // 3. 评估监控规则
        EvaluateMonitorRulesRequest evalRequest = new EvaluateMonitorRulesRequest();
        evalRequest.setTargetType(TARGET_TYPE_CANARY_BATCH);
        evalRequest.setTargetId(batch.getId());
        evalRequest.setMetricPayload(metricPayload);

        MonitorEvaluationResult evalResult;
        try {
            evalResult = monitorEvaluationService.evaluate(evalRequest);
        } catch (Exception e) {
            log.error("Quality gate evaluation failed for batch {}: {}", batch.getId(), e.getMessage(), e);
            batch.setQualityGateStatus(ReleaseCanaryBatch.GATE_STATUS_FAILED);
            batch.setQualityGateReason("监控评估异常: " + e.getMessage());
            batch.setQualityGateEvaluatedAt(OffsetDateTime.now());
            releaseCanaryBatchMapper.updateById(batch);
            recordAuditLog(order.getId(), ACTION_GATE_FAILED, batch.getId(), "evaluation_error", e.getMessage());
            return;
        }

        // 4. 判断结果
        int maxFiring = plan.getMaxFiringAlerts() != null ? plan.getMaxFiringAlerts() : 0;
        if (evalResult.getHitCount() <= maxFiring) {
            batch.setQualityGateStatus(ReleaseCanaryBatch.GATE_STATUS_PASSED);
            batch.setQualityGateReason(String.format("门禁通过，命中告警数=%d，阈值=%d", evalResult.getHitCount(), maxFiring));
            recordAuditLog(order.getId(), ACTION_GATE_PASSED, batch.getId(), "hitCount", evalResult.getHitCount());
        } else {
            batch.setQualityGateStatus(ReleaseCanaryBatch.GATE_STATUS_FAILED);
            batch.setQualityGateReason(String.format("门禁失败，命中告警数=%d 超过阈值 %d", evalResult.getHitCount(), maxFiring));
            if (!evalResult.getEventIds().isEmpty()) {
                try {
                    batch.setMonitorAlertEventIds(objectMapper.writeValueAsString(evalResult.getEventIds()));
                } catch (JsonProcessingException ignored) {}
            }
            recordAuditLog(order.getId(), ACTION_GATE_FAILED, batch.getId(), "hitCount", evalResult.getHitCount(), "threshold", maxFiring);
        }

        batch.setQualityGateEvaluatedAt(OffsetDateTime.now());
        releaseCanaryBatchMapper.updateById(batch);

        recordAuditLog(order.getId(), ACTION_GATE_EVALUATED, batch.getId(),
                "qualityGateStatus", batch.getQualityGateStatus(),
                "hitCount", evalResult.getHitCount(),
                "threshold", maxFiring);

        // E3.1: 保存门禁评估历史
        gateEvaluationService.saveEvaluationHistory(
                order.getId(),
                plan.getId(),
                batch.getId(),
                "FIRST_EVALUATION",
                batch.getQualityGateStatus(),
                evalResult.getHitCount(),
                maxFiring,
                snapshotId,
                batch.getMonitorAlertEventIds(),
                metricPayload,
                batch.getQualityGateReason(),
                UserContextHolder.getUserIdOrDefault(1L)
        );
    }

    // E2: 重评估使用已保存的指标
    @Transactional
    public void reEvaluateBatch(ReleaseCanaryPlan plan, ReleaseCanaryBatch batch, ReleaseOrder order) {
        // 使用已保存的指标 payload
        String metricPayload = batch.getQualityMetricsPayload();
        if (metricPayload == null || metricPayload.isBlank()) {
            batch.setQualityGateStatus(ReleaseCanaryBatch.GATE_STATUS_FAILED);
            batch.setQualityGateReason("无历史指标数据，无法重评估");
            batch.setQualityGateEvaluatedAt(OffsetDateTime.now());
            releaseCanaryBatchMapper.updateById(batch);
            return;
        }

        // 重新评估
        EvaluateMonitorRulesRequest evalRequest = new EvaluateMonitorRulesRequest();
        evalRequest.setTargetType(TARGET_TYPE_CANARY_BATCH);
        evalRequest.setTargetId(batch.getId());
        evalRequest.setMetricPayload(metricPayload);

        MonitorEvaluationResult evalResult;
        try {
            evalResult = monitorEvaluationService.evaluate(evalRequest);
        } catch (Exception e) {
            log.error("Quality gate re-evaluation failed for batch {}: {}", batch.getId(), e.getMessage(), e);
            batch.setQualityGateStatus(ReleaseCanaryBatch.GATE_STATUS_FAILED);
            batch.setQualityGateReason("监控重评估异常: " + e.getMessage());
            batch.setQualityGateEvaluatedAt(OffsetDateTime.now());
            releaseCanaryBatchMapper.updateById(batch);
            recordAuditLog(order.getId(), ACTION_GATE_FAILED, batch.getId(), "evaluation_error", e.getMessage());
            return;
        }

        // 判断结果
        int maxFiring = plan.getMaxFiringAlerts() != null ? plan.getMaxFiringAlerts() : 0;
        if (evalResult.getHitCount() <= maxFiring) {
            batch.setQualityGateStatus(ReleaseCanaryBatch.GATE_STATUS_PASSED);
            batch.setQualityGateReason(String.format("门禁通过，命中告警数=%d，阈值=%d", evalResult.getHitCount(), maxFiring));
            recordAuditLog(order.getId(), ACTION_GATE_PASSED, batch.getId(), "hitCount", evalResult.getHitCount());
        } else {
            batch.setQualityGateStatus(ReleaseCanaryBatch.GATE_STATUS_FAILED);
            batch.setQualityGateReason(String.format("门禁失败，命中告警数=%d 超过阈值 %d", evalResult.getHitCount(), maxFiring));
            if (!evalResult.getEventIds().isEmpty()) {
                try {
                    batch.setMonitorAlertEventIds(objectMapper.writeValueAsString(evalResult.getEventIds()));
                } catch (JsonProcessingException ignored) {}
            }
            recordAuditLog(order.getId(), ACTION_GATE_FAILED, batch.getId(), "hitCount", evalResult.getHitCount(), "threshold", maxFiring);
        }

        batch.setQualityGateEvaluatedAt(OffsetDateTime.now());
        releaseCanaryBatchMapper.updateById(batch);

        recordAuditLog(order.getId(), ACTION_GATE_EVALUATED, batch.getId(),
                "qualityGateStatus", batch.getQualityGateStatus(),
                "hitCount", evalResult.getHitCount(),
                "threshold", maxFiring);

        // E3.1: 保存门禁评估历史（重评估）
        gateEvaluationService.saveEvaluationHistory(
                order.getId(),
                plan.getId(),
                batch.getId(),
                "RE_EVALUATION",
                batch.getQualityGateStatus(),
                evalResult.getHitCount(),
                maxFiring,
                batch.getMonitorSnapshotId(),
                batch.getMonitorAlertEventIds(),
                metricPayload,
                batch.getQualityGateReason(),
                UserContextHolder.getUserIdOrDefault(1L)
        );
    }

    private void recordAuditLog(Long releaseOrderId, String action, Long batchId, Object... keyValues) {
        Map<String, Object> data = new HashMap<>();
        data.put("batchId", batchId);
        for (int i = 0; i < keyValues.length; i += 2) {
            if (i + 1 < keyValues.length) {
                data.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
            }
        }
        try {
            sysAuditLogMapper.insertAuditLog(
                    UserContextHolder.getUserIdOrDefault(1L),
                    BIZ_TYPE_RELEASE_ORDER,
                    releaseOrderId,
                    action,
                    null,
                    objectMapper.writeValueAsString(data),
                    "SUCCESS",
                    null
            );
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize audit log data: {}", e.getMessage());
        }
    }
}

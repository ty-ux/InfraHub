package com.infrahub.release.service;

import com.infrahub.common.error.ErrorCode;
import com.infrahub.common.exception.BizException;
import com.infrahub.release.dto.GateEvaluationHistoryResponse;
import com.infrahub.release.entity.ReleaseCanaryBatch;
import com.infrahub.release.entity.ReleaseCanaryGateEvaluation;
import com.infrahub.release.entity.ReleaseCanaryPlan;
import com.infrahub.release.mapper.ReleaseCanaryBatchMapper;
import com.infrahub.release.mapper.ReleaseCanaryGateEvaluationMapper;
import com.infrahub.release.mapper.ReleaseCanaryPlanMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 门禁评估历史服务
 * E3.1: 查询和管理门禁评估历史记录
 */
@Service
@RequiredArgsConstructor
public class ReleaseCanaryGateEvaluationService {

    private final ReleaseCanaryGateEvaluationMapper gateEvaluationMapper;
    private final ReleaseCanaryPlanMapper planMapper;
    private final ReleaseCanaryBatchMapper batchMapper;

    /**
     * 查询批次的门禁评估历史
     */
    public List<GateEvaluationHistoryResponse> getBatchEvaluations(Long releaseOrderId, Long batchId) {
        // 验证批次属于发布单
        ReleaseCanaryPlan plan = planMapper.findByReleaseOrderId(releaseOrderId);
        if (plan == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "灰度计划不存在");
        }

        ReleaseCanaryBatch batch = batchMapper.selectById(batchId);
        if (batch == null || !batch.getPlanId().equals(plan.getId())) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "批次不存在或不属于当前计划");
        }

        List<ReleaseCanaryGateEvaluation> evaluations = gateEvaluationMapper.findByBatchIdOrderByEvaluatedAtDesc(batchId);
        return evaluations.stream()
                .map(GateEvaluationHistoryResponse::fromEntity)
                .collect(Collectors.toList());
    }

    /**
     * 保存门禁评估历史记录
     */
    @Transactional
    public void saveEvaluationHistory(
            Long releaseOrderId,
            Long planId,
            Long batchId,
            String evaluationType,
            String gateResult,
            Integer hitCount,
            Integer threshold,
            Long snapshotId,
            String alertEventIds,
            String metricsPayload,
            String reason,
            Long evaluatedBy
    ) {
        ReleaseCanaryGateEvaluation history = new ReleaseCanaryGateEvaluation();
        history.setReleaseOrderId(releaseOrderId);
        history.setPlanId(planId);
        history.setBatchId(batchId);
        history.setEvaluationType(evaluationType);
        history.setGateResult(gateResult);
        history.setHitCount(hitCount != null ? hitCount : 0);
        history.setThreshold(threshold != null ? threshold : 0);
        history.setMonitorSnapshotId(snapshotId);
        history.setMonitorAlertEventIds(alertEventIds);
        history.setMetricsPayload(metricsPayload);
        history.setReason(reason);
        history.setEvaluatedBy(evaluatedBy);
        history.setEvaluatedAt(OffsetDateTime.now());
        history.setCreatedAt(OffsetDateTime.now());

        gateEvaluationMapper.insert(history);
    }
}
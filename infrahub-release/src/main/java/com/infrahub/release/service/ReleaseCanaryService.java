package com.infrahub.release.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.infrahub.base.entity.BaseEnv;
import com.infrahub.base.entity.BaseServiceEntity;
import com.infrahub.base.service.BaseEnvService;
import com.infrahub.base.service.BaseServiceEntityService;
import com.infrahub.common.context.UserContextHolder;
import com.infrahub.common.error.ErrorCode;
import com.infrahub.common.exception.BizException;
import com.infrahub.release.dto.CanaryBatchResponse;
import com.infrahub.release.dto.CanaryPlanResponse;
import com.infrahub.release.dto.CreateCanaryPlanRequest;
import com.infrahub.release.entity.ReleaseArtifact;
import com.infrahub.release.entity.ReleaseCanaryBatch;
import com.infrahub.release.entity.ReleaseCanaryPlan;
import com.infrahub.release.entity.ReleaseOrder;
import com.infrahub.release.entity.ReleaseRollbackOrder;
import com.infrahub.release.entity.ReleaseVersion;
import com.infrahub.release.entity.RuntimeServiceInstance;
import com.infrahub.release.mapper.ReleaseCanaryBatchMapper;
import com.infrahub.release.mapper.ReleaseCanaryPlanMapper;
import com.infrahub.release.mapper.SysAuditLogMapper;
import com.infrahub.release.service.ReleaseCanaryExecutionCoordinator.AutoRollbackOrder;
import com.infrahub.release.service.ReleaseCanaryExecutionCoordinator.BatchFinalization;
import com.infrahub.release.service.ReleaseCanaryExecutionCoordinator.ClaimedBatch;
import com.infrahub.release.service.ReleaseCanaryExecutionCoordinator.GateEvaluationContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 灰度发布编排。
 *
 * <p><b>事务边界</b>：本类只保留 {@code createPlan} 与 {@code evaluateGate} 两个纯数据库短事务。
 * {@code start} / {@code continueNext} 自身没有事务：先由
 * {@link ReleaseCanaryExecutionCoordinator} 在短事务里完成认领并提交，锁释放之后才调用外部执行器，
 * 最后再走另一个短事务落终态。因此在 SSH / 执行器等待期间不持有任何数据库锁，
 * 也不会出现“外层订单锁 + {@code REQUIRES_NEW} 部署记录外键写入”互相等待。
 *
 * <p>错误处理约定：
 * <ul>
 *   <li>外部执行抛出异常 → 批次、计划、发布单收敛为 FAILED，并标记结果为 UNKNOWN；不自动重试，
 *       也不在结果未知时自动回滚；</li>
 *   <li>外部执行返回失败 → 当前结果不能区分确定失败与超时等未知状态，不自动回滚；</li>
 *   <li>外部执行已经完成、但终态落库失败 → 抛错并明确说明“结果未知、禁止自动重试”，
 *       不把这次执行报告为成功。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReleaseCanaryService {

    private static final String BIZ_TYPE_RELEASE_ORDER = "RELEASE_ORDER";
    private static final String ACTION_CANARY_PLAN_CREATED = "RELEASE_CANARY_PLAN_CREATED";

    private final ReleaseCanaryPlanMapper releaseCanaryPlanMapper;
    private final ReleaseCanaryBatchMapper releaseCanaryBatchMapper;
    private final RuntimeServiceInstanceService runtimeServiceInstanceService;
    private final SysAuditLogMapper sysAuditLogMapper;
    private final ObjectMapper objectMapper;
    private final ReleaseDeployExecutionService releaseDeployExecutionService;
    private final BaseServiceEntityService baseServiceEntityService;
    private final BaseEnvService baseEnvService;
    private final ReleaseVersionService releaseVersionService;
    private final ReleaseArtifactService releaseArtifactService;
    private final DeployConfigService deployConfigService;
    private final ReleaseRollbackOrderService releaseRollbackOrderService;
    private final ReleaseCanaryQualityGateService qualityGateService;
    private final ResourceGovernanceRecordService resourceGovernanceRecordService;
    private final ReleaseCanaryExecutionCoordinator canaryExecutionCoordinator;

    @Transactional
    public CanaryPlanResponse createPlan(Long releaseOrderId, CreateCanaryPlanRequest request) {
        ReleaseOrder order = canaryExecutionCoordinator.preparePlanCreation(releaseOrderId);

        List<RuntimeServiceInstance> instances = runtimeServiceInstanceService
                .listByServiceAndEnv(order.getServiceId(), order.getEnvId());
        if (instances.isEmpty()) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "当前服务在目标环境下没有可发布的实例");
        }

        List<List<Long>> batchInstanceIds = splitInstancesIntoBatches(instances, request.getBatchCount());

        ReleaseCanaryPlan plan = createCanaryPlan(releaseOrderId, request, batchInstanceIds.size());
        releaseCanaryPlanMapper.insert(plan);

        createBatches(plan.getId(), releaseOrderId, batchInstanceIds);

        recordAuditLog(releaseOrderId, ACTION_CANARY_PLAN_CREATED, Map.of(
                "batchCount", plan.getBatchCount(),
                "autoRollbackEnabled", plan.getAutoRollbackEnabled()
        ));

        return getPlanResponse(plan);
    }

    public CanaryPlanResponse getPlan(Long releaseOrderId) {
        ReleaseCanaryPlan plan = releaseCanaryPlanMapper.findByReleaseOrderId(releaseOrderId);
        if (plan == null) {
            return null;
        }
        return getPlanResponse(plan);
    }

    /**
     * 启动或从暂停恢复：短事务认领 → 事务外执行 → 短事务终态。
     *
     * <p>没有待执行批次时，认领阶段直接把计划收口为 SUCCESS（从暂停恢复时允许 PAUSED → SUCCESS）。
     */
    public CanaryPlanResponse start(Long releaseOrderId) {
        requireNoExecutionTransaction();
        ClaimedBatch claim = canaryExecutionCoordinator.claimFirstBatch(releaseOrderId);
        if (!claim.completed()) {
            executeClaimedBatch(claim);
        }
        return getRequiredPlanResponse(releaseOrderId);
    }

    /**
     * 继续下一个批次。上一批次未成功、门禁未通过或仍有 RUNNING 批次时，认领阶段即拒绝，
     * 不会调用任何外部执行器。
     */
    public CanaryPlanResponse continueNext(Long releaseOrderId) {
        requireNoExecutionTransaction();
        ClaimedBatch claim = canaryExecutionCoordinator.claimNextBatch(releaseOrderId);
        if (!claim.completed()) {
            executeClaimedBatch(claim);
        }
        return getRequiredPlanResponse(releaseOrderId);
    }

    private static void requireNoExecutionTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "灰度执行入口不能在已有事务中调用");
        }
    }

    /**
     * 手动重评估质量门禁：纯数据库短事务，不触发外部执行。
     * 门禁通过且计划处于 BLOCKED 时才恢复 RUNNING。
     */
    @Transactional
    public CanaryPlanResponse evaluateGate(Long releaseOrderId, Long batchId) {
        GateEvaluationContext context = canaryExecutionCoordinator.lockForGateEvaluation(releaseOrderId, batchId);
        ReleaseCanaryPlan plan = context.plan();
        ReleaseCanaryBatch batch = context.batch();
        ReleaseOrder order = context.order();

        qualityGateService.reEvaluateBatch(plan, batch, order);

        if (ReleaseCanaryBatch.GATE_STATUS_PASSED.equals(batch.getQualityGateStatus())
                && ReleaseCanaryPlan.STATUS_BLOCKED.equals(plan.getStatus())) {
            // 只有确实把 BLOCKED 条件更新成 RUNNING 才算恢复成功；受影响行数为 0 说明状态已被并发改写
            if (canaryExecutionCoordinator.markPlanRunningIfBlocked(plan.getId()) != 1) {
                throw new BizException(ErrorCode.VALIDATION_ERROR,
                        "计划状态已变化，门禁恢复未生效，请刷新后重试");
            }
        }

        return getRequiredPlanResponse(releaseOrderId);
    }

    /** 暂停只阻止后续批次；已在执行的外部动作不会被中断。 */
    public CanaryPlanResponse pause(Long releaseOrderId) {
        canaryExecutionCoordinator.pause(releaseOrderId);
        return getRequiredPlanResponse(releaseOrderId);
    }

    // ------------------------------------------------------------------
    // 事务外执行
    // ------------------------------------------------------------------

    private void executeClaimedBatch(ClaimedBatch claim) {
        ReleaseOrder order = claim.order();
        int batchNo = claim.batch().getBatchNo();

        BatchInputs inputs;
        try {
            inputs = loadBatchInputs(claim);
        } catch (RuntimeException exception) {
            // 基础数据校验发生在任何远端调用之前：确定“未开始”
            markPreExecutionFailure(claim, exception);
            throw new BizException(ErrorCode.INTERNAL_ERROR,
                    "灰度批次 " + batchNo + " 执行前校验失败，未调用远端；发布单 " + order.getId()
                            + "；请修正后重试", exception);
        }

        ReleaseDeployExecutionResult result;
        try {
            result = releaseDeployExecutionService.executeReleaseDeployForBatch(
                    order, inputs.service(), inputs.env(), inputs.version(), inputs.artifact(),
                    claim.instances(), inputs.deployConfig(), batchNo
            );
        } catch (com.infrahub.release.support.DeployExecutionUnknownException unknownFailure) {
            markExecutionException(claim, unknownFailure);
            throw new BizException(ErrorCode.INTERNAL_ERROR,
                    "灰度批次 " + batchNo + " 外部执行中断，结果未知；禁止自动重试；发布单 " + order.getId()
                            + "；请人工核对部署记录与实例状态", unknownFailure);
        } catch (RuntimeException exception) {
            // 部署服务在开始任何实例之前抛出的错误（命令缺失、主机/凭据配置缺失等）：确定未开始
            markPreExecutionFailure(claim, exception);
            throw new BizException(ErrorCode.INTERNAL_ERROR,
                    "灰度批次 " + batchNo + " 执行前校验失败，未调用远端；发布单 " + order.getId()
                            + "；请修正后重试", exception);
        }

        BatchFinalization finalized;
        try {
            finalized = canaryExecutionCoordinator.finalizeBatch(claim, result);
        } catch (RuntimeException exception) {
            log.error("金丝雀发布单 {} 批次 {} 结果已返回，但终态落库失败",
                    order.getId(), batchNo, exception);
            throw new BizException(ErrorCode.INTERNAL_ERROR,
                    "灰度批次 " + batchNo + " 外部执行已完成，但终态落库失败，结果未知；禁止自动重试；发布单 "
                            + order.getId() + "；请人工核对部署记录与实例状态", exception);
        }

        if (finalized.autoRollbackRequired()) {
            triggerAutoRollback(claim, finalized.failureReason());
        }
    }

    /** 一次批次执行所需的基础数据；加载失败发生在任何远端动作之前。 */
    private record BatchInputs(BaseServiceEntity service, BaseEnv env, ReleaseVersion version,
                               ReleaseArtifact artifact, Map<String, String> deployConfig) {
    }

    private BatchInputs loadBatchInputs(ClaimedBatch claim) {
        ReleaseOrder order = claim.order();
        BaseServiceEntity service = baseServiceEntityService.getById(order.getServiceId());
        BaseEnv env = baseEnvService.getById(order.getEnvId());
        ReleaseVersion version = releaseVersionService.getById(order.getVersionId());
        if (service == null || env == null || version == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "发布单关联的服务、环境或版本不存在");
        }
        ReleaseArtifact artifact = releaseArtifactService.getById(version.getArtifactId());
        if (artifact == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "发布版本缺少制品信息");
        }
        Map<String, String> deployConfig = deployConfigService.loadDeployConfig(order.getAppId(), order.getEnvId());
        return new BatchInputs(service, env, version, artifact, deployConfig);
    }

    /**
     * 执行前校验失败：批次/计划/发布单收敛为 FAILED，并明确标记 {@code NOT_STARTED}。
     * 此时没有任何部署记录被创建，也没有调用远端。
     */
    private void markPreExecutionFailure(ClaimedBatch claim, RuntimeException exception) {
        log.error("金丝雀发布单 {} 批次 {} 执行前校验失败，未调用远端",
                claim.order().getId(), claim.batch().getBatchNo(), exception);
        try {
            canaryExecutionCoordinator.markExecutionException(claim,
                    com.infrahub.release.support.DeployExecutionOutcome.NOT_STARTED,
                    "执行前校验失败（PRE_EXECUTION_VALIDATION），未调用远端；禁止自动重试");
        } catch (RuntimeException recordFailure) {
            log.error("金丝雀发布单 {} 批次 {} 执行前失败终态落库失败",
                    claim.order().getId(), claim.batch().getBatchNo(), recordFailure);
        }
    }

    /**
     * 外部执行异常：落 FAILED 终态并标记结果未知。异常本身不重试、不自动回滚。
     *
     * <p>面向用户的摘要只说明“结果未知 + 关联 ID”；原始异常只写日志。该发布单下仍为 RUNNING
     * 的部署记录收敛为 UNKNOWN（不是 FAILED），避免把“可能仍在运行”显示成“确定失败”。
     */
    private void markExecutionException(ClaimedBatch claim, RuntimeException exception) {
        String category = Thread.currentThread().isInterrupted()
                ? "EXECUTION_INTERRUPTED" : "EXECUTION_EXCEPTION";
        String reason = "外部执行中断（" + category + "），远端结果未知；禁止自动重试";
        log.error("金丝雀发布单 {} 批次 {} 外部执行中断（{}），errorType={}",
                claim.order().getId(), claim.batch().getBatchNo(), category,
                exception.getClass().getSimpleName());
        try {
            // 部署记录收口与 UNKNOWN 登记已并入协调器的异常终态事务，不再在此处二次写入，
            // 避免出现“订单已 FAILED、记录尚未标记 UNKNOWN”的空窗。
            canaryExecutionCoordinator.markExecutionException(claim,
                    com.infrahub.release.support.DeployExecutionOutcome.UNKNOWN, reason);
        } catch (RuntimeException recordFailure) {
            log.error("金丝雀发布单 {} 批次 {} 异常终态落库失败，原始异常继续抛出，errorType={}",
                    claim.order().getId(), claim.batch().getBatchNo(),
                    recordFailure.getClass().getSimpleName());
        }
    }

    /**
     * 明确失败后的自动回滚入口。
     *
     * <p><b>当前恒不执行</b>：{@link ReleaseCanaryExecutionCoordinator#isAutoRollbackEnabled()} 为 false。
     * 恢复它需要先证明结果类别确定、目标版本确定、实例仍有效、失败到回滚的认领连续，并且回滚执行
     * 不再在事务内调用 SSH——这些条件见协调器常量说明与交付文档，本轮全部未满足。
     */
    private void triggerAutoRollback(ClaimedBatch claim, String failureReason) {
        if (!ReleaseCanaryExecutionCoordinator.isAutoRollbackEnabled()) {
            log.info("金丝雀发布单 {} 自动回滚已停用，批次失败原因：{}",
                    claim.order().getId(), failureReason);
            return;
        }
        ReleaseOrder order = claim.order();
        AutoRollbackOrder autoRollback;
        try {
            autoRollback = canaryExecutionCoordinator.createAutoRollbackOrder(
                    order.getId(), claim.plan().getId());
        } catch (RuntimeException exception) {
            log.error("金丝雀发布单 {} 自动回滚单创建失败：{}",
                    order.getId(), exception.getClass().getSimpleName());
            return;
        }
        if (!autoRollback.created() || autoRollback.rollbackOrderId() == null) {
            return;
        }

        try {
            boolean rolledBack = releaseRollbackOrderService.doExecuteRollbackOrder(autoRollback.rollbackOrderId());
            if (rolledBack) {
                canaryExecutionCoordinator.markPlanRolledBack(claim.plan().getId());
            } else {
                // 回滚执行返回失败但不抛异常时，计划不得标记 ROLLED_BACK
                canaryExecutionCoordinator.recordAutoRollbackFailure(
                        claim.plan().getId(), "回滚执行返回失败，请查看回滚单与部署记录");
            }

            ReleaseRollbackOrder rollbackOrder = releaseRollbackOrderService.getById(autoRollback.rollbackOrderId());
            if (rollbackOrder != null) {
                resourceGovernanceRecordService.record(
                        GovernanceEventType.AUTO_ROLLBACK_RESOURCE_RESTORED,
                        null,
                        null,
                        null,
                        order.getId(),
                        rollbackOrder.getId(),
                        order.getExecutorType() != null ? order.getExecutorType() : "SSH",
                        order.getEnvId(),
                        order.getServiceId(),
                        rollbackOrder.getToVersionId(),
                        "ROLLBACK_ORDER",
                        rollbackOrder.getId(),
                        Map.of("fromVersionId", String.valueOf(order.getVersionId())),
                        Map.of("toVersionId", String.valueOf(rollbackOrder.getToVersionId()),
                                "rollbackNo", String.valueOf(rollbackOrder.getRollbackNo())),
                        "SUCCESS".equals(rollbackOrder.getStatus()) ? "SUCCESS" : "FAILED",
                        null,
                        order.getCreatedBy()
                );
            }
        } catch (RuntimeException exception) {
            log.error("金丝雀发布单 {} 自动回滚执行失败：{}",
                    order.getId(), exception.getClass().getSimpleName());
            try {
                canaryExecutionCoordinator.recordAutoRollbackFailure(
                        claim.plan().getId(),
                        exception.getClass().getSimpleName()
                                + (exception.getMessage() == null ? "" : " - " + exception.getMessage()));
            } catch (RuntimeException recordFailure) {
                log.error("金丝雀发布单 {} 自动回滚失败原因落库失败", order.getId(), recordFailure);
            }
        }
    }

    // ------------------------------------------------------------------
    // 计划创建与响应
    // ------------------------------------------------------------------

    private CanaryPlanResponse getRequiredPlanResponse(Long releaseOrderId) {
        CanaryPlanResponse plan = getPlan(releaseOrderId);
        if (plan == null) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "灰度计划不存在");
        }
        return plan;
    }

    private List<List<Long>> splitInstancesIntoBatches(List<RuntimeServiceInstance> instances, int batchCount) {
        if (batchCount <= 0) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "批次数量必须大于 0");
        }
        List<RuntimeServiceInstance> sortedInstances = new ArrayList<>(instances);
        sortedInstances.sort((a, b) -> Long.compare(a.getId(), b.getId()));

        List<List<Long>> batches = new ArrayList<>();
        int totalInstances = sortedInstances.size();
        int baseSize = totalInstances / batchCount;
        int remainder = totalInstances % batchCount;

        int startIndex = 0;
        for (int i = 0; i < batchCount; i++) {
            int batchSize = baseSize + (i < remainder ? 1 : 0);
            List<Long> batchInstanceIds = new ArrayList<>();
            for (int j = 0; j < batchSize && startIndex < totalInstances; j++) {
                batchInstanceIds.add(sortedInstances.get(startIndex).getId());
                startIndex++;
            }
            if (!batchInstanceIds.isEmpty()) {
                batches.add(batchInstanceIds);
            }
        }

        return batches;
    }

    private ReleaseCanaryPlan createCanaryPlan(Long releaseOrderId, CreateCanaryPlanRequest request, int actualBatchCount) {
        ReleaseCanaryPlan plan = new ReleaseCanaryPlan();
        plan.setReleaseOrderId(releaseOrderId);
        plan.setBatchCount(actualBatchCount);
        plan.setBatchIntervalSeconds(request.getBatchIntervalSeconds());
        plan.setAutoRollbackEnabled(request.getAutoRollbackEnabled());
        plan.setQualityGateEnabled(request.getQualityGateEnabled() != null ? request.getQualityGateEnabled() : true);
        plan.setMaxFiringAlerts(request.getMaxFiringAlerts() != null ? request.getMaxFiringAlerts() : 0);
        plan.setGateFailureAction(request.getGateFailureAction() != null ? request.getGateFailureAction() : "BLOCK_AND_ROLLBACK");
        plan.setStatus(ReleaseCanaryPlan.STATUS_CREATED);
        plan.setCreatedBy(UserContextHolder.getUserIdOrDefault(1L));
        return plan;
    }

    private void createBatches(Long planId, Long releaseOrderId, List<List<Long>> batchInstanceIds) {
        for (int i = 0; i < batchInstanceIds.size(); i++) {
            ReleaseCanaryBatch batch = new ReleaseCanaryBatch();
            batch.setPlanId(planId);
            batch.setReleaseOrderId(releaseOrderId);
            batch.setBatchNo(i + 1);
            try {
                batch.setInstanceIds(objectMapper.writeValueAsString(batchInstanceIds.get(i)));
            } catch (JsonProcessingException e) {
                throw new BizException(ErrorCode.INTERNAL_ERROR, "批次实例序列化失败");
            }
            batch.setStatus(ReleaseCanaryBatch.STATUS_PENDING);
            batch.setQualityGateStatus(ReleaseCanaryBatch.GATE_STATUS_NOT_EVALUATED);
            releaseCanaryBatchMapper.insert(batch);
        }
    }

    private CanaryPlanResponse getPlanResponse(ReleaseCanaryPlan plan) {
        List<ReleaseCanaryBatch> batches = releaseCanaryBatchMapper.findByPlanId(plan.getId());
        List<CanaryBatchResponse> batchResponses = batches.stream()
                .map(CanaryBatchResponse::fromEntity)
                .toList();
        return CanaryPlanResponse.fromEntity(plan, batchResponses);
    }

    private void recordAuditLog(Long releaseOrderId, String action, Map<String, ?> afterData) {
        try {
            sysAuditLogMapper.insertAuditLog(
                    UserContextHolder.getUserIdOrDefault(1L),
                    BIZ_TYPE_RELEASE_ORDER,
                    releaseOrderId,
                    action,
                    null,
                    objectMapper.writeValueAsString(afterData),
                    "SUCCESS",
                    null
            );
        } catch (JsonProcessingException e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "审计日志序列化失败");
        }
    }
}

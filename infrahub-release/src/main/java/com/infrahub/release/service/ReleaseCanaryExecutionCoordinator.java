package com.infrahub.release.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.infrahub.common.context.UserContextHolder;
import com.infrahub.common.error.ErrorCode;
import com.infrahub.common.exception.BizException;
import com.infrahub.release.entity.ReleaseCanaryBatch;
import com.infrahub.release.entity.ReleaseCanaryPlan;
import com.infrahub.release.entity.ReleaseCurrentVersion;
import com.infrahub.release.entity.ReleaseOrder;
import com.infrahub.release.entity.ReleaseRollbackOrder;
import com.infrahub.release.entity.RuntimeExecutionUnknown;
import com.infrahub.release.entity.RuntimeServiceInstance;
import com.infrahub.release.mapper.ReleaseCanaryBatchMapper;
import com.infrahub.release.mapper.ReleaseCanaryPlanMapper;
import com.infrahub.release.mapper.ReleaseOrderMapper;
import com.infrahub.release.mapper.RuntimeServiceInstanceMapper;
import com.infrahub.release.mapper.SysAuditLogMapper;
import com.infrahub.release.support.CanaryAutoRollbackPolicy;
import com.infrahub.release.support.DeployExecutionOutcome;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 金丝雀批次执行的状态协调器：**所有数据库事务都在这里，外部执行一律不在事务内**。
 *
 * <p><b>为什么单独成类</b>：{@code ReleaseCanaryService} 的 start/continue 以前是
 * {@code @Transactional} 并且在同一个事务里调用 SSH。那样做有两个后果：
 * <ol>
 *   <li>外部执行期间一直持有 {@code base_service} / {@code release_order} 行锁，其他认领和移除登记被阻塞；</li>
 *   <li>部署记录的 RUNNING 写入走 {@code REQUIRES_NEW}，而 {@code runtime_deploy_record.release_order_id}
 *       对 {@code release_order} 有外键，内层新事务会等待外层订单锁，形成自锁。</li>
 * </ol>
 * 本类只做“认领 / 终态 / 暂停 / 计划完成”这些短事务；调用方在方法返回、事务提交之后才调用外部执行器。
 *
 * <p><b>锁顺序</b>（与发布认领、回滚执行、实例移除登记一致）：
 * {@code base_service → release_order → release_canary_plan → release_canary_batch
 * → runtime_service_instance(按 ID 升序)}。
 * 批次实例行锁只在认领事务内持有；提交后释放，随后由订单 RUNNING 状态阻止移除登记。
 *
 * <p><b>认领凭证不是“订单是 RUNNING”</b>：订单在整个计划期间保持 RUNNING，无法证明某次请求拥有批次。
 * 首个批次用发布单条件更新 {@code CREATED/APPROVED → RUNNING} 作为订单层凭证；每个批次再用
 * {@code release_canary_batch.PENDING → RUNNING} 的条件更新作为批次层凭证。两者都要求受影响行数为 1。
 *
 * <p><b>批次清单只来自数据库已保存的行</b>：实例 ID 从 {@code release_canary_batch.instance_ids} 解析，
 * 再逐行加锁校验存在性与服务/环境归属。绝不按服务+环境重新枚举实例来“扩张”计划已定的批次。
 */
@Service
@RequiredArgsConstructor
public class ReleaseCanaryExecutionCoordinator {

    private static final String BIZ_TYPE_RELEASE_ORDER = "RELEASE_ORDER";
    private static final String ACTION_CANARY_BATCH_STARTED = "RELEASE_CANARY_BATCH_STARTED";
    private static final String ACTION_CANARY_BATCH_SUCCESS = "RELEASE_CANARY_BATCH_SUCCESS";
    private static final String ACTION_CANARY_BATCH_FAILED = "RELEASE_CANARY_BATCH_FAILED";
    private static final String ACTION_CANARY_PLAN_PAUSED = "RELEASE_CANARY_PLAN_PAUSED";
    private static final String ACTION_CANARY_PLAN_COMPLETED = "RELEASE_CANARY_PLAN_COMPLETED";
    private static final String ACTION_CANARY_GATE_BLOCKED = "RELEASE_CANARY_GATE_BLOCKED";
    private static final String ACTION_CANARY_CONTINUE_BLOCKED = "RELEASE_CANARY_CONTINUE_BLOCKED";
    private static final String ACTION_CANARY_AUTO_ROLLBACK_TRIGGERED = "RELEASE_CANARY_AUTO_ROLLBACK_TRIGGERED";

    private static final String ORDER_STATUS_CREATED = "CREATED";
    private static final String ORDER_STATUS_APPROVED = "APPROVED";
    private static final String ORDER_STATUS_RUNNING = "RUNNING";
    private static final String ORDER_STATUS_SUCCESS = "SUCCESS";
    private static final String ORDER_STATUS_FAILED = "FAILED";

    /**
     * 金丝雀自动回滚保持关闭。启用条件（结果确定、目标版本确定、实例仍有效、失败到回滚的协调连续、
     * 回滚执行不在事务内调用 SSH）写在 {@link CanaryAutoRollbackPolicy} 里，缺一不可。
     *
     * <p>本类只做转发，让编排层与对外 DTO 看到的是同一份事实。
     */
    public static boolean isAutoRollbackEnabled() {
        return CanaryAutoRollbackPolicy.isAvailable();
    }

    private final ReleaseExecutionClaimService releaseExecutionClaimService;
    private final ReleaseOrderMapper releaseOrderMapper;
    private final ReleaseCanaryPlanMapper releaseCanaryPlanMapper;
    private final ReleaseCanaryBatchMapper releaseCanaryBatchMapper;
    private final RuntimeServiceInstanceMapper runtimeServiceInstanceMapper;
    private final RuntimeServiceInstanceService runtimeServiceInstanceService;
    private final ReleaseCurrentVersionService releaseCurrentVersionService;
    private final ReleaseCanaryQualityGateService qualityGateService;
    private final ReleaseRollbackOrderService releaseRollbackOrderService;
    private final SysAuditLogMapper sysAuditLogMapper;
    private final ObjectMapper objectMapper;
    private final RuntimeDeployRecordService runtimeDeployRecordService;
    private final RuntimeExecutionUnknownService runtimeExecutionUnknownService;

    /**
     * 一次成功认领的批次。
     *
     * @param instances 按批次清单顺序、已加锁校验过的实例整行；外部执行只能使用这份清单
     * @param completed 为 true 时没有批次需要执行（计划已完成），其余字段为空
     */
    public record ClaimedBatch(ReleaseOrder order,
                               ReleaseCanaryPlan plan,
                               ReleaseCanaryBatch batch,
                               List<RuntimeServiceInstance> instances,
                               boolean completed) {

        static ClaimedBatch completed(ReleaseOrder order, ReleaseCanaryPlan plan) {
            return new ClaimedBatch(order, plan, null, List.of(), true);
        }
    }

    /** 批次终态落库后的结果，供调用方决定是否触发自动回滚。 */
    public record BatchFinalization(boolean success,
                                    boolean planCompleted,
                                    String gateStatus,
                                    boolean autoRollbackRequired,
                                    String failureReason) {
    }

    /** 自动回滚单的创建结果；{@code rollbackOrderId} 为空表示缺少版本快照、无法回滚。 */
    public record AutoRollbackOrder(Long rollbackOrderId, boolean created, String reason) {
    }

    public record GateEvaluationContext(ReleaseOrder order, ReleaseCanaryPlan plan, ReleaseCanaryBatch batch) {
    }

    /**
     * 创建计划前的短事务协调：锁服务行 → 发布单行，再用锁后事实校验状态、发布类型与“无活动计划”。
     *
     * <p>调用方必须已经处于事务中（{@code ReleaseCanaryService.createPlan} 的 {@code @Transactional}），
     * 计划与批次的插入与这里的锁在同一个事务提交，因此并发创建最多只有一个成功。
     */
    @Transactional
    public ReleaseOrder preparePlanCreation(Long releaseOrderId) {
        assertTransactionActive("灰度计划创建");
        ReleaseOrder order = releaseExecutionClaimService.lockReleaseOrderForCoordination(releaseOrderId);
        if (!isExecutableStatus(order.getStatus())) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "当前发布单状态不允许创建灰度计划");
        }
        if (!"CANARY".equalsIgnoreCase(order.getReleaseType())) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "仅灰度发布单允许创建灰度计划");
        }
        if (releaseCanaryPlanMapper.countActiveByReleaseOrderId(releaseOrderId) > 0) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "该发布单已存在灰度计划");
        }
        return order;
    }

    /**
     * 启动（或从暂停恢复）并认领第一个待执行批次。
     *
     * <p>订单层：
     * <ul>
     *   <li>订单仍是 CREATED/APPROVED → 走统一认领，条件更新 RUNNING；</li>
     *   <li>订单已是 RUNNING（暂停后恢复）→ 只加锁并排除“同服务/环境另一个执行中任务”，
     *       不重复改订单状态。</li>
     * </ul>
     * 批次层：计划 CREATED/PAUSED → RUNNING、批次 PENDING → RUNNING 两次条件更新都必须拿到 1 行。
     *
     * <p>没有待执行批次时，只有“没有 RUNNING 批次且没有 FAILED 批次”才允许把计划收口为 SUCCESS；
     * 从暂停恢复时允许 PAUSED → SUCCESS。
     */
    @Transactional
    public ClaimedBatch claimFirstBatch(Long releaseOrderId) {
        assertTransactionActive("金丝雀首批认领");

        ReleaseOrder snapshot = releaseOrderMapper.selectById(releaseOrderId);
        if (snapshot == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "发布单不存在");
        }
        boolean orderWasExecutable = isExecutableStatus(snapshot.getStatus());
        ReleaseOrder order = orderWasExecutable
                ? releaseExecutionClaimService.claimForCanaryExecution(releaseOrderId).order()
                : releaseExecutionClaimService.lockRunningOrderForBatch(releaseOrderId);

        ReleaseCanaryPlan plan = releaseCanaryPlanMapper.selectByReleaseOrderIdForUpdate(releaseOrderId);
        if (plan == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "灰度计划不存在");
        }
        if (ReleaseCanaryPlan.STATUS_CREATED.equals(plan.getStatus())) {
            if (!orderWasExecutable) {
                throw new BizException(ErrorCode.VALIDATION_ERROR,
                        "灰度计划尚未启动，但发布单已在执行中；请先确认发布单由本计划持有");
            }
        } else if (!ReleaseCanaryPlan.STATUS_PAUSED.equals(plan.getStatus())) {
            throw new BizException(ErrorCode.VALIDATION_ERROR,
                    "当前计划状态不允许启动或恢复，当前状态：" + plan.getStatus());
        }

        List<ReleaseCanaryBatch> batches = releaseCanaryBatchMapper.findByPlanIdForUpdate(plan.getId());
        assertNoUnfinishedBatch(batches);
        // 恢复是另一条继续执行的入口，必须与 continueNext 使用相同的上一批次门禁。
        if (ReleaseCanaryPlan.STATUS_PAUSED.equals(plan.getStatus())) {
            ReleaseCanaryBatch previous = latestFinished(batches);
            if (previous != null) {
                if (!ReleaseCanaryBatch.STATUS_SUCCESS.equals(previous.getStatus())) {
                    throw new BizException(ErrorCode.VALIDATION_ERROR, "上一批次未成功完成，无法恢复");
                }
                assertQualityGateAllowsContinue(order, plan, previous);
            }
        }
        ReleaseCanaryBatch batch = firstPending(batches);
        if (batch == null) {
            if (!completePlanLocked(plan, order, true)) {
                throw new BizException(ErrorCode.VALIDATION_ERROR, "计划完成状态落库失败，请刷新后重试");
            }
            return ClaimedBatch.completed(order, plan);
        }

        if (releaseCanaryPlanMapper.markRunningIfStartable(plan.getId()) != 1) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "计划已被其他请求启动或状态已变化");
        }
        plan.setStatus(ReleaseCanaryPlan.STATUS_RUNNING);
        return claimBatchLocked(order, plan, batch);
    }

    /**
     * 继续下一个待执行批次。要求订单 RUNNING、计划 RUNNING、没有残留 RUNNING 批次，
     * 且最近一个已结束批次是 SUCCESS（质量门禁规则与旧实现一致）。
     *
     * <p>没有待执行批次时把计划收口为 SUCCESS；但计划若已被用户暂停（状态已不是 RUNNING），
     * 本方法在入口就拒绝，必须由 start/恢复入口完成收口。
     */
    @Transactional
    public ClaimedBatch claimNextBatch(Long releaseOrderId) {
        assertTransactionActive("金丝雀后续批次认领");

        ReleaseOrder order = releaseExecutionClaimService.lockRunningOrderForBatch(releaseOrderId);
        ReleaseCanaryPlan plan = releaseCanaryPlanMapper.selectByReleaseOrderIdForUpdate(releaseOrderId);
        if (plan == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "灰度计划不存在");
        }
        if (!ReleaseCanaryPlan.STATUS_RUNNING.equals(plan.getStatus())) {
            throw new BizException(ErrorCode.VALIDATION_ERROR,
                    "当前计划状态不允许继续，当前状态：" + plan.getStatus());
        }

        List<ReleaseCanaryBatch> batches = releaseCanaryBatchMapper.findByPlanIdForUpdate(plan.getId());
        assertNoUnfinishedBatch(batches);

        ReleaseCanaryBatch previous = latestFinished(batches);
        if (previous == null || !ReleaseCanaryBatch.STATUS_SUCCESS.equals(previous.getStatus())) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "上一批次未成功完成，无法继续");
        }
        assertQualityGateAllowsContinue(order, plan, previous);

        ReleaseCanaryBatch batch = firstPending(batches);
        if (batch == null) {
            if (!completePlanLocked(plan, order, false)) {
                throw new BizException(ErrorCode.VALIDATION_ERROR, "计划完成状态落库失败，请刷新后重试");
            }
            return ClaimedBatch.completed(order, plan);
        }
        return claimBatchLocked(order, plan, batch);
    }

    /**
     * 批次终态短事务：SUCCESS/FAILED 状态、实例版本、质量门禁与计划收口都在这里一次提交。
     *
     * <p>本方法在外部执行器返回之后调用，绝不包含外部动作。批次必须仍是 RUNNING
     * （本次认领未被其他路径收口），否则失败关闭，调用方不得把这次执行报告为成功。
     */
    @Transactional
    public BatchFinalization finalizeBatch(ClaimedBatch claim, ReleaseDeployExecutionResult result) {
        assertTransactionActive("金丝雀批次终态");
        ReleaseOrder order = lockAndRequireOrder(claim.order().getId());
        ReleaseCanaryPlan plan = lockPlan(claim.plan().getId());
        ReleaseCanaryBatch batch = lockBatch(claim.batch().getId());
        assertSameContext(order, plan, batch);

        if (!ReleaseCanaryBatch.STATUS_RUNNING.equals(batch.getStatus())) {
            throw new BizException(ErrorCode.INTERNAL_ERROR,
                    "批次 " + batch.getBatchNo() + " 的 RUNNING 状态已被其他请求改写，本次执行结果不能落库");
        }

        OffsetDateTime endedAt = OffsetDateTime.now();
        DeployExecutionOutcome outcome = result.outcome();
        // success 布尔值与结果类别必须一致：不一致说明调用方把“未知”包装成了“成功”或反之，
        // 这种结果不能落库，按失败关闭处理
        if (result.success() != outcome.isSuccess()) {
            throw new BizException(ErrorCode.INTERNAL_ERROR,
                    "批次执行结果标记不一致（success=" + result.success() + "，类别=" + outcome.code()
                            + "），结果不能落库");
        }

        if (outcome.isSuccess()) {
            if (!ORDER_STATUS_RUNNING.equalsIgnoreCase(order.getStatus())) {
                throw new BizException(ErrorCode.INTERNAL_ERROR,
                        "发布单状态已变为 " + order.getStatus() + "，批次成功终态不能落库");
            }
            assertSuccessIdsBelongToClaim(claim, result);
            if (releaseCanaryBatchMapper.finishIfRunning(batch.getId(),
                    ReleaseCanaryBatch.STATUS_SUCCESS, endedAt, null) != 1) {
                throw new BizException(ErrorCode.INTERNAL_ERROR, "批次成功终态落库失败，结果未知");
            }
            batch.setStatus(ReleaseCanaryBatch.STATUS_SUCCESS);
            batch.setEndedAt(endedAt);
            batch.setFailureReason(null);

            runtimeServiceInstanceService.updateVersion(result.successInstanceIds(), order.getVersionId());
            qualityGateService.evaluateBatch(plan, batch, order, result);

            if (!gateAllowsContinue(batch.getQualityGateStatus())) {
                blockPlanForGateFailureLocked(plan, batch, order);
                return new BatchFinalization(true, false, batch.getQualityGateStatus(),
                        false, batch.getQualityGateReason());
            }

            audit(order.getId(), ACTION_CANARY_BATCH_SUCCESS, Map.of(
                    "planId", plan.getId(),
                    "batchNo", batch.getBatchNo(),
                    "successInstanceCount", result.successInstanceIds().size(),
                    "qualityGateStatus", String.valueOf(batch.getQualityGateStatus())
            ));

            boolean planCompleted = completePlanIfNoPendingLocked(plan, order);
            return new BatchFinalization(true, planCompleted, batch.getQualityGateStatus(),
                    false, null);
        }

        String failureReason = describeOutcomeFailure(batch, result);
        if (!ORDER_STATUS_RUNNING.equalsIgnoreCase(order.getStatus())
                && !ORDER_STATUS_FAILED.equalsIgnoreCase(order.getStatus())) {
            throw new BizException(ErrorCode.INTERNAL_ERROR,
                    "发布单状态已变为 " + order.getStatus() + "，批次失败终态不能落库");
        }
        if (releaseCanaryBatchMapper.finishIfRunning(batch.getId(),
                ReleaseCanaryBatch.STATUS_FAILED, endedAt, failureReason) != 1) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "批次失败终态落库失败，结果未知");
        }
        batch.setStatus(ReleaseCanaryBatch.STATUS_FAILED);
        batch.setEndedAt(endedAt);
        batch.setFailureReason(failureReason);

        releaseCanaryPlanMapper.markFailedIfActive(plan.getId());
        plan.setStatus(ReleaseCanaryPlan.STATUS_FAILED);
        String planFailureReason = failureReason + "；自动回滚已停用，请人工核对部署记录与实例状态";
        plan.setFailureReason(planFailureReason);
        releaseCanaryPlanMapper.updateFailureReason(plan.getId(), planFailureReason);

        int orderUpdated = releaseOrderMapper.markFailedIfRunning(order.getId());
        if (orderUpdated != 1 && !ORDER_STATUS_FAILED.equalsIgnoreCase(order.getStatus())) {
            throw new BizException(ErrorCode.INTERNAL_ERROR,
                    "发布单状态已变为 " + order.getStatus() + "，批次失败终态不能落库");
        }
        order.setStatus(ORDER_STATUS_FAILED);

        // 批次终态与部署记录收口必须在同一事务：未知记录/登记先于订单 FAILED 对外可见，
        // 不允许出现“订单已终结、RUNNING 记录无人处理”的空窗。
        closeDeployRecordsForTerminal(order, plan, batch, outcome, failureReason);

        audit(order.getId(), ACTION_CANARY_BATCH_FAILED, auditFailureData(plan, batch, outcome, result));

        // 只有确定失败才具备“自动回滚决策”的最低前提；未知与部分成功都不满足。
        // 即便满足，自动回滚开关仍为关闭，本轮不会创建或执行回滚单。
        boolean autoRollbackRequired = isAutoRollbackEnabled() && outcome.allowsAutomaticRollbackDecision();
        return new BatchFinalization(false, false, batch.getQualityGateStatus(), autoRollbackRequired,
                failureReason);
    }

    /**
     * 终态收口批次下的部署记录：
     * <ul>
     *   <li>未知/部分成功：仍为 RUNNING 的记录改成 UNKNOWN，并保证该单至少有一条未解除的未知事实；
     *       即使异常发生在记录写入之前（没有记录），也补一条订单级登记，宁可先挡住也不能放行。</li>
     *   <li>确定失败/未开始：仍为 RUNNING 的记录补齐 FAILED 证据（未开始表示没有调用远端）。</li>
     * </ul>
     * 本方法在调用方事务内执行，UNKNOWN 写入与登记同时提交。
     */
    private void closeDeployRecordsForTerminal(ReleaseOrder order, ReleaseCanaryPlan plan,
                                               ReleaseCanaryBatch batch, DeployExecutionOutcome outcome,
                                               String reason) {
        if (outcome.requiresManualVerification()) {
            runtimeDeployRecordService.markRunningRecordsUnknownByReleaseOrderId(order.getId(), reason);
            runtimeExecutionUnknownService.registerOrderLevelIfNoUnknownRecords(
                    order.getId(), null, plan.getId(), batch.getId(),
                    RuntimeExecutionUnknown.DETECTION_TERMINAL_CLEANUP,
                    "灰度批次以未知结果终结，无法证明远端动作已结束");
        } else {
            runtimeDeployRecordService.failRunningRecordsByReleaseOrderId(order.getId(), reason);
        }
    }

    /**
     * 批次未成功的固定格式原因：以结果类别标记开头（可直接被 API 读取），随后是固定说明与计数。
     * 不含 stdout/stderr、命令或异常消息——这些只留在部署记录里。
     */
    private static String describeOutcomeFailure(ReleaseCanaryBatch batch, ReleaseDeployExecutionResult result) {
        String phrase = switch (result.outcome()) {
            case UNKNOWN -> "执行结果未知，远端动作可能仍在运行";
            case PARTIAL -> "部分实例成功，系统处于混合状态";
            case FAILED -> "已执行实例均确定失败";
            case NOT_STARTED -> "未开始执行，没有调用远端";
            case SUCCEEDED -> "执行成功";
        };
        String code = result.failureCode() == null ? "" : "，类别 " + result.failureCode();
        return result.outcome().marker() + " 批次 " + batch.getBatchNo() + " " + phrase + "（"
                + result.countSummary() + code + "）";
    }

    private static Map<String, Object> auditFailureData(ReleaseCanaryPlan plan, ReleaseCanaryBatch batch,
                                                        DeployExecutionOutcome outcome,
                                                        ReleaseDeployExecutionResult result) {
        Map<String, Object> data = new HashMap<>();
        data.put("planId", plan.getId());
        data.put("batchNo", batch.getBatchNo());
        data.put("executionOutcome", outcome.code());
        data.put("attemptedInstances", result.attemptedCount());
        data.put("succeededInstances", result.succeededCount());
        data.put("failedInstances", result.failedCount());
        data.put("unknownInstances", result.unknownCount());
        data.put("notStartedInstances", result.notStartedCount());
        if (result.failureCode() != null) {
            data.put("failureCode", result.failureCode());
        }
        data.put("autoRollbackEnabled", isAutoRollbackEnabled());
        return data;
    }

    /**
     * 外部执行异常时的失败终态：批次、计划、发布单一起收敛为 FAILED，并用结果类别持久说明
     * “远端可能已经执行”。{@code outcome} 由调用方按异常发生的位置给出：执行前校验失败为
     * {@link DeployExecutionOutcome#NOT_STARTED}，执行器抛出异常为 {@link DeployExecutionOutcome#UNKNOWN}。
     *
     * <p>不重试、不自动重新认领。若批次已不是 RUNNING（例如终态已由别的路径写入），
     * 不改写任何状态，只返回未触发回滚，由调用方继续抛出原始异常。
     */
    @Transactional
    public BatchFinalization markExecutionException(ClaimedBatch claim, DeployExecutionOutcome outcome,
                                                    String reason) {
        assertTransactionActive("金丝雀批次异常终态");
        ReleaseOrder order = lockAndRequireOrder(claim.order().getId());
        ReleaseCanaryPlan plan = lockPlan(claim.plan().getId());
        ReleaseCanaryBatch batch = lockBatch(claim.batch().getId());
        assertSameContext(order, plan, batch);

        if (!ReleaseCanaryBatch.STATUS_RUNNING.equals(batch.getStatus())) {
            return new BatchFinalization(false, false, batch.getQualityGateStatus(), false, reason);
        }
        if (!ORDER_STATUS_RUNNING.equalsIgnoreCase(order.getStatus())
                && !ORDER_STATUS_FAILED.equalsIgnoreCase(order.getStatus())) {
            // 发布单已被别的路径收口（例如并发回滚成功）：不改写任何状态，保留原异常
            return new BatchFinalization(false, false, batch.getQualityGateStatus(), false, reason);
        }

        String batchFailureReason = outcome.marker() + " 批次 " + batch.getBatchNo() + " " + reason;
        OffsetDateTime endedAt = OffsetDateTime.now();
        if (releaseCanaryBatchMapper.finishIfRunning(batch.getId(),
                ReleaseCanaryBatch.STATUS_FAILED, endedAt, batchFailureReason) != 1) {
            return new BatchFinalization(false, false, batch.getQualityGateStatus(), false, batchFailureReason);
        }
        batch.setStatus(ReleaseCanaryBatch.STATUS_FAILED);
        batch.setEndedAt(endedAt);
        batch.setFailureReason(batchFailureReason);

        releaseCanaryPlanMapper.markFailedIfActive(plan.getId());
        plan.setStatus(ReleaseCanaryPlan.STATUS_FAILED);
        String planFailureReason = batchFailureReason + "；自动回滚已停用，请人工核对部署记录与实例状态";
        plan.setFailureReason(planFailureReason);
        releaseCanaryPlanMapper.updateFailureReason(plan.getId(), planFailureReason);

        releaseOrderMapper.markFailedIfRunning(order.getId());
        order.setStatus(ORDER_STATUS_FAILED);

        // 与批次/计划/订单终态同事务收口部署记录；UNKNOWN 记录与未解除登记一起提交。
        closeDeployRecordsForTerminal(order, plan, batch, outcome, reason);

        Map<String, Object> auditData = new HashMap<>();
        auditData.put("planId", plan.getId());
        auditData.put("batchNo", batch.getBatchNo());
        auditData.put("executionOutcome", outcome.code());
        auditData.put("reason", reason);
        auditData.put("autoRollbackEnabled", isAutoRollbackEnabled());
        audit(order.getId(), ACTION_CANARY_BATCH_FAILED, auditData);

        return new BatchFinalization(false, false, batch.getQualityGateStatus(), false, batchFailureReason);
    }

    /**
     * 暂停计划。只停止“后续批次”，不会中断已经在执行的外部动作；执行中的批次终态仍会落库，
     * 但因为计划已不在 RUNNING，批次成功不会自动把计划收口为 SUCCESS。
     */
    @Transactional
    public ReleaseCanaryPlan pause(Long releaseOrderId) {
        assertTransactionActive("金丝雀暂停");
        ReleaseOrder order = releaseExecutionClaimService.lockReleaseOrderForCoordination(releaseOrderId);
        if (!ORDER_STATUS_RUNNING.equalsIgnoreCase(order.getStatus())) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "发布单不是执行中状态，不能暂停计划");
        }
        ReleaseCanaryPlan plan = releaseCanaryPlanMapper.selectByReleaseOrderIdForUpdate(releaseOrderId);
        if (plan == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "灰度计划不存在");
        }
        if (!ReleaseCanaryPlan.STATUS_RUNNING.equals(plan.getStatus())) {
            throw new BizException(ErrorCode.VALIDATION_ERROR,
                    "当前计划状态不允许暂停，当前状态：" + plan.getStatus());
        }
        if (releaseCanaryPlanMapper.markPausedIfRunning(plan.getId()) != 1) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "计划状态已变化，暂停未生效");
        }
        plan.setStatus(ReleaseCanaryPlan.STATUS_PAUSED);
        audit(releaseOrderId, ACTION_CANARY_PLAN_PAUSED, Map.of(
                "planId", plan.getId(),
                "status", ReleaseCanaryPlan.STATUS_PAUSED
        ));
        return plan;
    }

    /** 质量门禁重评估的锁定上下文：计划与批次都必须在锁后读取。 */
    @Transactional
    public GateEvaluationContext lockForGateEvaluation(Long releaseOrderId, Long batchId) {
        assertTransactionActive("灰度门禁重评估");
        ReleaseOrder order = releaseExecutionClaimService.lockReleaseOrderForCoordination(releaseOrderId);
        // 重评估只能让“仍在执行”的计划恢复后续批次。发布单已经 FAILED/SUCCESS 时，
        // 把计划改回 RUNNING 会造出“计划执行中、订单已终结”的矛盾状态（尤其 BLOCK_AND_ROLLBACK）。
        if (!ORDER_STATUS_RUNNING.equalsIgnoreCase(order.getStatus())) {
            throw new BizException(ErrorCode.VALIDATION_ERROR,
                    "发布单不是执行中状态（当前 " + order.getStatus() + "），不能重新评估门禁");
        }
        ReleaseCanaryPlan plan = releaseCanaryPlanMapper.selectByReleaseOrderIdForUpdate(releaseOrderId);
        if (plan == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "灰度计划不存在");
        }
        if (!ReleaseCanaryPlan.STATUS_RUNNING.equals(plan.getStatus())
                && !ReleaseCanaryPlan.STATUS_BLOCKED.equals(plan.getStatus())) {
            throw new BizException(ErrorCode.VALIDATION_ERROR,
                    "当前计划状态不允许重新评估门禁，当前状态：" + plan.getStatus());
        }
        ReleaseCanaryBatch batch = releaseCanaryBatchMapper.selectByIdForUpdate(batchId);
        if (batch == null || !Objects.equals(batch.getPlanId(), plan.getId())) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "批次不存在或不属于当前计划");
        }
        if (!ReleaseCanaryBatch.STATUS_SUCCESS.equals(batch.getStatus())) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "只有成功批次才能重新评估门禁");
        }
        return new GateEvaluationContext(order, plan, batch);
    }

    /** 门禁重评估通过后，只允许把 BLOCKED 恢复为 RUNNING；返回受影响行数。 */
    @Transactional
    public int markPlanRunningIfBlocked(Long planId) {
        return releaseCanaryPlanMapper.markRunningIfBlocked(planId);
    }

    /**
     * 创建自动回滚单（短事务），不执行回滚。
     *
     * <p>回滚单绑定在计划的 {@code rollback_order_id} 上，且只允许绑定一次，避免并发失败处理建出多张回滚单。
     * 回滚执行由调用方在事务外单独发起。缺少当前版本快照时不创建回滚单，只记录失败原因。
     */
    @Transactional
    public AutoRollbackOrder createAutoRollbackOrder(Long releaseOrderId, Long planId) {
        assertTransactionActive("自动回滚单创建");
        if (!isAutoRollbackEnabled()) {
            // 硬闸门：即使调用方误以为结果确定，也不能在自动回滚条件未满足时建出回滚单
            throw new BizException(ErrorCode.VALIDATION_ERROR,
                    "自动回滚已停用：执行结果分类与回滚交接条件尚未满足，请人工核对后手动回滚");
        }
        ReleaseOrder order = releaseExecutionClaimService.lockReleaseOrderForCoordination(releaseOrderId);
        ReleaseCanaryPlan plan = lockPlan(planId);
        if (!Objects.equals(plan.getReleaseOrderId(), order.getId())) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "灰度计划与发布单不匹配，已拒绝自动回滚");
        }
        if (!ORDER_STATUS_FAILED.equalsIgnoreCase(order.getStatus())) {
            throw new BizException(ErrorCode.VALIDATION_ERROR,
                    "发布单不是失败状态(" + order.getStatus() + ")，已拒绝自动回滚");
        }
        // 结果未知时不允许自动回滚：远端动作可能仍在运行，回滚会与它冲突。
        // 当前自动回滚总开关为关闭，这条检查是为将来恢复开关时保留的硬性前提。
        runtimeExecutionUnknownService.assertNoUnresolvedUnknown(order.getServiceId(), order.getEnvId());
        if (plan.getRollbackOrderId() != null) {
            return new AutoRollbackOrder(plan.getRollbackOrderId(), false, "已存在自动回滚单");
        }

        ReleaseCurrentVersion currentVersion = releaseCurrentVersionService.lambdaQuery()
                .eq(ReleaseCurrentVersion::getEnvId, order.getEnvId())
                .eq(ReleaseCurrentVersion::getServiceId, order.getServiceId())
                .one();
        if (currentVersion == null) {
            String reason = appendReason(plan.getFailureReason(), "缺少当前版本快照，无法自动回滚");
            plan.setFailureReason(reason);
            releaseCanaryPlanMapper.updateFailureReason(plan.getId(), reason);
            audit(order.getId(), ACTION_CANARY_AUTO_ROLLBACK_TRIGGERED, Map.of(
                    "planId", plan.getId(),
                    "rollbackTriggered", false,
                    "reason", "缺少当前版本快照，无法自动回滚"
            ));
            return new AutoRollbackOrder(null, false, "缺少当前版本快照，无法自动回滚");
        }

        ReleaseRollbackOrder rollbackOrder = new ReleaseRollbackOrder();
        rollbackOrder.setRollbackNo(generateAutoRollbackNo(order.getOrderNo()));
        rollbackOrder.setReleaseOrderId(order.getId());
        rollbackOrder.setFromVersionId(order.getVersionId());
        rollbackOrder.setToVersionId(currentVersion.getVersionId());
        rollbackOrder.setReason("灰度发布批次失败，自动触发回滚");
        rollbackOrder.setStatus("CREATED");
        rollbackOrder.setCreatedBy(UserContextHolder.getUserIdOrDefault(1L));
        releaseRollbackOrderService.save(rollbackOrder);

        if (releaseCanaryPlanMapper.setRollbackOrderIdIfNull(plan.getId(), rollbackOrder.getId()) != 1) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "自动回滚单绑定失败，请人工核对");
        }
        plan.setRollbackOrderId(rollbackOrder.getId());

        audit(order.getId(), ACTION_CANARY_AUTO_ROLLBACK_TRIGGERED, Map.of(
                "planId", plan.getId(),
                "rollbackOrderId", rollbackOrder.getId(),
                "rollbackNo", rollbackOrder.getRollbackNo(),
                "targetVersionId", currentVersion.getVersionId()
        ));
        return new AutoRollbackOrder(rollbackOrder.getId(), true, null);
    }

    /** 自动回滚执行成功后的计划状态收口；只允许活动/失败状态转 ROLLED_BACK。 */
    @Transactional
    public void markPlanRolledBack(Long planId) {
        assertTransactionActive("自动回滚后计划状态");
        lockPlan(planId);
        releaseCanaryPlanMapper.markRolledBackIfActive(planId);
    }

    /** 自动回滚执行失败时追加原因，不改写计划状态（回滚单自身已记录 FAILED）。 */
    @Transactional
    public void recordAutoRollbackFailure(Long planId, String reason) {
        assertTransactionActive("自动回滚失败原因");
        ReleaseCanaryPlan plan = lockPlan(planId);
        String updated = appendReason(plan.getFailureReason(), "自动回滚执行失败：" + reason);
        plan.setFailureReason(updated);
        releaseCanaryPlanMapper.updateFailureReason(planId, updated);
    }

    // ------------------------------------------------------------------
    // 认领与锁内校验
    // ------------------------------------------------------------------

    private ClaimedBatch claimBatchLocked(ReleaseOrder order, ReleaseCanaryPlan plan, ReleaseCanaryBatch batch) {
        if (!Objects.equals(batch.getPlanId(), plan.getId())
                || !Objects.equals(batch.getReleaseOrderId(), order.getId())) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "灰度批次与计划/发布单不匹配，已拒绝执行");
        }

        List<RuntimeServiceInstance> instances = lockAndValidateBatchInstances(order, batch);
        OffsetDateTime startedAt = OffsetDateTime.now();
        if (releaseCanaryBatchMapper.markRunningIfPending(batch.getId(), startedAt) != 1) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "批次已被其他请求认领，已拒绝重复执行");
        }
        batch.setStatus(ReleaseCanaryBatch.STATUS_RUNNING);
        batch.setStartedAt(startedAt);
        batch.setEndedAt(null);
        batch.setFailureReason(null);

        audit(order.getId(), ACTION_CANARY_BATCH_STARTED, Map.of(
                "planId", plan.getId(),
                "batchNo", batch.getBatchNo()
        ));
        return new ClaimedBatch(order, plan, batch, instances, false);
    }

    /**
     * 按保存的批次清单逐行加锁并校验。任何一项不满足都拒绝整个批次，不做部分执行：
     * ID 为空/重复、实例已被移除、服务或环境归属变化、实例未绑定主机。
     *
     * <p>实例行锁按 ID 升序获取，与采集写回、移除登记、监控配置写入保持同一顺序。
     */
    private List<RuntimeServiceInstance> lockAndValidateBatchInstances(ReleaseOrder order, ReleaseCanaryBatch batch) {
        List<Long> instanceIds = parseInstanceIds(batch.getInstanceIds());
        if (instanceIds.isEmpty()
                || instanceIds.stream().anyMatch(Objects::isNull)
                || instanceIds.stream().distinct().count() != instanceIds.size()) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "灰度批次的实例清单无效");
        }

        List<Long> sortedIds = new ArrayList<>(instanceIds);
        sortedIds.sort(Comparator.naturalOrder());
        Map<Long, RuntimeServiceInstance> locked = new HashMap<>();
        for (Long instanceId : sortedIds) {
            RuntimeServiceInstance instance = runtimeServiceInstanceMapper.selectFullForUpdate(instanceId);
            if (instance == null) {
                throw new BizException(ErrorCode.VALIDATION_ERROR,
                        "灰度批次实例(id=" + instanceId + ")已被移除登记，已拒绝执行");
            }
            if (!Objects.equals(instance.getServiceId(), order.getServiceId())
                    || !Objects.equals(instance.getEnvId(), order.getEnvId())) {
                throw new BizException(ErrorCode.VALIDATION_ERROR,
                        "灰度批次实例(id=" + instanceId + ")的服务/环境归属已变化，已拒绝执行");
            }
            if (instance.getHostId() == null) {
                throw new BizException(ErrorCode.VALIDATION_ERROR,
                        "灰度批次实例(id=" + instanceId + ")未绑定主机，无法执行");
            }
            locked.put(instanceId, instance);
        }

        List<RuntimeServiceInstance> ordered = new ArrayList<>(instanceIds.size());
        for (Long instanceId : instanceIds) {
            ordered.add(locked.get(instanceId));
        }
        return ordered;
    }

    private List<Long> parseInstanceIds(String instanceIdsJson) {
        try {
            return objectMapper.readValue(instanceIdsJson, new TypeReference<List<Long>>() {
            });
        } catch (JsonProcessingException e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "批次实例 ID 解析失败");
        }
    }

    /**
     * 成功实例集合必须恰好等于本次认领的批次清单：执行器返回的 ID 不允许越出认领范围，
     * 也不允许在“整批成功”的结果里漏掉已认领实例。越界或缺失都按终态不可落库处理。
     */
    private void assertSuccessIdsBelongToClaim(ClaimedBatch claim, ReleaseDeployExecutionResult result) {
        Set<Long> claimedIds = new HashSet<>();
        for (RuntimeServiceInstance instance : claim.instances()) {
            claimedIds.add(instance.getId());
        }
        if (result.successInstanceIds() == null
                || result.successInstanceIds().size() != claimedIds.size()
                || !claimedIds.containsAll(result.successInstanceIds())) {
            throw new BizException(ErrorCode.INTERNAL_ERROR,
                    "执行器返回的成功实例集合与本次批次认领清单不一致，结果不能落库");
        }
    }

    private void assertNoUnfinishedBatch(List<ReleaseCanaryBatch> batches) {
        ReleaseCanaryBatch running = batches.stream()
                .filter(b -> ReleaseCanaryBatch.STATUS_RUNNING.equals(b.getStatus()))
                .findFirst()
                .orElse(null);
        if (running != null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR,
                    "批次 " + running.getBatchNo() + " 仍处于 RUNNING，不能启动或继续其他批次；"
                            + "若为异常中断，请人工核对部署记录后再处理");
        }
    }

    private static ReleaseCanaryBatch firstPending(List<ReleaseCanaryBatch> batches) {
        return batches.stream()
                .filter(b -> ReleaseCanaryBatch.STATUS_PENDING.equals(b.getStatus()))
                .min(Comparator.comparing(ReleaseCanaryBatch::getBatchNo))
                .orElse(null);
    }

    private static ReleaseCanaryBatch latestFinished(List<ReleaseCanaryBatch> batches) {
        return batches.stream()
                .filter(b -> !ReleaseCanaryBatch.STATUS_PENDING.equals(b.getStatus()))
                .max(Comparator.comparing(ReleaseCanaryBatch::getBatchNo))
                .orElse(null);
    }

    private void assertQualityGateAllowsContinue(ReleaseOrder order, ReleaseCanaryPlan plan,
                                                 ReleaseCanaryBatch previous) {
        if (!Boolean.TRUE.equals(plan.getQualityGateEnabled())) {
            return;
        }
        String gateStatus = previous.getQualityGateStatus();
        if (gateAllowsContinue(gateStatus)) {
            return;
        }
        if (ReleaseCanaryBatch.GATE_STATUS_FAILED.equals(gateStatus)) {
            audit(order.getId(), ACTION_CANARY_CONTINUE_BLOCKED, Map.of(
                    "planId", plan.getId(),
                    "batchNo", previous.getBatchNo(),
                    "qualityGateStatus", gateStatus,
                    "qualityGateReason", String.valueOf(previous.getQualityGateReason())
            ));
            throw new BizException(ErrorCode.VALIDATION_ERROR,
                    "上一批次质量门禁未通过，无法继续。原因：" + previous.getQualityGateReason());
        }
        // NOT_EVALUATED、null 或任何未知状态都按“未通过”处理，失败关闭
        audit(order.getId(), ACTION_CANARY_CONTINUE_BLOCKED, Map.of(
                "planId", plan.getId(),
                "batchNo", previous.getBatchNo(),
                "reason", "门禁尚未通过",
                "qualityGateStatus", String.valueOf(gateStatus)
        ));
        throw new BizException(ErrorCode.VALIDATION_ERROR, "上一批次质量门禁尚未通过，无法继续");
    }

    private static boolean gateAllowsContinue(String gateStatus) {
        return ReleaseCanaryBatch.GATE_STATUS_PASSED.equals(gateStatus)
                || ReleaseCanaryBatch.GATE_STATUS_SKIPPED.equals(gateStatus);
    }

    private void blockPlanForGateFailureLocked(ReleaseCanaryPlan plan, ReleaseCanaryBatch batch,
                                               ReleaseOrder order) {
        releaseCanaryPlanMapper.markBlockedIfActive(plan.getId());
        plan.setStatus(ReleaseCanaryPlan.STATUS_BLOCKED);
        String reason = "批次 " + batch.getBatchNo() + " 质量门禁未通过或结果无法确认：" + batch.getQualityGateReason()
                + "；自动回滚已停用，请人工核对部署记录与实例状态后手动回滚";
        plan.setFailureReason(reason);
        releaseCanaryPlanMapper.updateFailureReason(plan.getId(), reason);

        audit(order.getId(), ACTION_CANARY_GATE_BLOCKED, Map.of(
                "planId", plan.getId(),
                "batchNo", batch.getBatchNo(),
                "qualityGateStatus", String.valueOf(batch.getQualityGateStatus()),
                "qualityGateReason", String.valueOf(batch.getQualityGateReason())
        ));

        // 门禁失败且配置为“阻断并回滚”时，发布单必须先离开 RUNNING：
        // 回滚执行入口会检查“同服务/环境没有执行中的发布”，若继续保留 RUNNING，
        // 回滚会被自己的发布单挡住。BLOCK（只阻断不回滚）保持 RUNNING 不变。
        // 自动回滚停用期间这里同样置 FAILED：本次发布已经不会继续推进，而操作者的手动回滚
        // 也需要这层互斥先释放；原因里已写明“自动回滚已停用”，不会被读成“已自动回滚”。
        if (ReleaseCanaryPlan.GATE_ACTION_BLOCK_AND_ROLLBACK.equals(plan.getGateFailureAction())) {
            releaseOrderMapper.markFailedIfRunning(order.getId());
            order.setStatus(ORDER_STATUS_FAILED);
        }
    }

    /**
     * 批次成功后的计划收口。只有没有待执行批次、且计划仍处于 RUNNING 时才自动完成；
     * 用户在批次执行期间暂停的计划保持 PAUSED，等待显式恢复。
     */
    private boolean completePlanIfNoPendingLocked(ReleaseCanaryPlan plan, ReleaseOrder order) {
        ReleaseCanaryBatch nextPending = releaseCanaryBatchMapper.findFirstPendingForUpdate(plan.getId());
        if (nextPending != null) {
            return false;
        }
        if (releaseCanaryPlanMapper.markSuccessIfRunning(plan.getId()) != 1) {
            return false;
        }
        plan.setStatus(ReleaseCanaryPlan.STATUS_SUCCESS);
        return finishOrderSuccessLocked(plan.getId(), order);
    }

    /**
     * 显式启动/恢复且已无待执行批次时的计划收口。{@code allowPaused} 为 true 时允许 PAUSED → SUCCESS；
     * 自动收口（批次成功后）走 {@link #completePlanIfNoPendingLocked}，不覆盖暂停。
     */
    private boolean completePlanLocked(ReleaseCanaryPlan plan, ReleaseOrder order, boolean allowPaused) {
        int updated = allowPaused
                ? releaseCanaryPlanMapper.markSuccessIfRunningOrPaused(plan.getId())
                : releaseCanaryPlanMapper.markSuccessIfRunning(plan.getId());
        if (updated != 1) {
            return false;
        }
        plan.setStatus(ReleaseCanaryPlan.STATUS_SUCCESS);
        return finishOrderSuccessLocked(plan.getId(), order);
    }

    private boolean finishOrderSuccessLocked(Long planId, ReleaseOrder order) {
        if (releaseOrderMapper.markSuccessIfRunning(order.getId()) != 1
                && !ORDER_STATUS_SUCCESS.equalsIgnoreCase(order.getStatus())) {
            throw new BizException(ErrorCode.INTERNAL_ERROR,
                    "发布单状态已变为 " + order.getStatus() + "，计划完成不能落库");
        }
        order.setStatus(ORDER_STATUS_SUCCESS);
        releaseCurrentVersionService.markReleaseVersion(
                order.getEnvId(), order.getServiceId(), order.getVersionId(), order.getId());
        audit(order.getId(), ACTION_CANARY_PLAN_COMPLETED, Map.of(
                "planId", planId,
                "status", ReleaseCanaryPlan.STATUS_SUCCESS,
                "versionId", order.getVersionId()
        ));
        return true;
    }

    private ReleaseOrder lockAndRequireOrder(Long releaseOrderId) {
        return releaseExecutionClaimService.lockReleaseOrderForCoordination(releaseOrderId);
    }

    private ReleaseCanaryPlan lockPlan(Long planId) {
        ReleaseCanaryPlan plan = releaseCanaryPlanMapper.selectByIdForUpdate(planId);
        if (plan == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "灰度计划不存在");
        }
        return plan;
    }

    private ReleaseCanaryBatch lockBatch(Long batchId) {
        ReleaseCanaryBatch batch = releaseCanaryBatchMapper.selectByIdForUpdate(batchId);
        if (batch == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "灰度批次不存在");
        }
        return batch;
    }

    private void assertSameContext(ReleaseOrder order, ReleaseCanaryPlan plan, ReleaseCanaryBatch batch) {
        if (!Objects.equals(plan.getReleaseOrderId(), order.getId())
                || !Objects.equals(batch.getPlanId(), plan.getId())
                || !Objects.equals(batch.getReleaseOrderId(), order.getId())) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "灰度批次、计划与发布单归属不一致，已拒绝");
        }
    }

    private static boolean isExecutableStatus(String status) {
        return ORDER_STATUS_CREATED.equalsIgnoreCase(status) || ORDER_STATUS_APPROVED.equalsIgnoreCase(status);
    }

    private static String appendReason(String existing, String addition) {
        if (existing == null || existing.isBlank()) {
            return addition;
        }
        if (existing.contains(addition)) {
            return existing;
        }
        return existing + "；" + addition;
    }

    private static String generateAutoRollbackNo(String releaseOrderNo) {
        String timestamp = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").format(java.time.LocalDateTime.now());
        return "AUTO-RB-" + releaseOrderNo + "-" + timestamp;
    }

    private void audit(Long releaseOrderId, String action, Map<String, ?> afterData) {
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

    private static void assertTransactionActive(String scene) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new BizException(ErrorCode.INTERNAL_ERROR,
                    scene + "未处于事务中，行锁与条件更新不会生效，已拒绝执行");
        }
    }
}

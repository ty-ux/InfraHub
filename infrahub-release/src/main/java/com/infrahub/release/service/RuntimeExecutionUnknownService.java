package com.infrahub.release.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.infrahub.base.mapper.BaseServiceMapper;
import com.infrahub.common.context.UserContext;
import com.infrahub.common.context.UserContextHolder;
import com.infrahub.common.error.ErrorCode;
import com.infrahub.common.exception.BizException;
import com.infrahub.release.dto.RecoverStuckExecutionResponse;
import com.infrahub.release.dto.RuntimeExecutionUnknownResponse;
import com.infrahub.release.entity.ReleaseCanaryBatch;
import com.infrahub.release.entity.ReleaseCanaryPlan;
import com.infrahub.release.entity.ReleaseOrder;
import com.infrahub.release.entity.ReleaseRollbackOrder;
import com.infrahub.release.entity.RuntimeDeployRecord;
import com.infrahub.release.entity.RuntimeExecutionUnknown;
import com.infrahub.release.entity.RuntimeExecutionUnknownMerge;
import com.infrahub.release.mapper.ReleaseCanaryBatchMapper;
import com.infrahub.release.mapper.ReleaseCanaryPlanMapper;
import com.infrahub.release.mapper.ReleaseOrderMapper;
import com.infrahub.release.mapper.ReleaseRollbackOrderMapper;
import com.infrahub.release.mapper.RuntimeDeployRecordMapper;
import com.infrahub.release.mapper.RuntimeExecutionUnknownMapper;
import com.infrahub.release.mapper.SysAuditLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * “结果未知”执行的持久化事实、阻断核对与人工解除。
 *
 * <p><b>为什么需要这个类</b>：{@code release_order} / {@code release_rollback_order} 只有粗粒度终态，
 * 结果未知时订单仍会写成 FAILED；如果所有入口只检查“是否存在 RUNNING 任务”，那么从
 * RUNNING 收敛为 FAILED 的那一刻起，同一服务/环境就可以启动新的发布、回滚或实例移除，
 * 而远端动作可能仍在进行。这里把“未知且未解除”变成一个可查询、可审计、可阻断的事实。
 *
 * <p><b>唯一权威事实</b>：{@code runtime_execution_unknown.status = 'UNRESOLVED'}。
 * 历史 UNKNOWN 部署记录（没有登记行）同样按未解除处理，保证升级后已有的未知不会漏掉。
 * 解除只写登记行的解除字段与审计，不修改部署状态、发布单状态或回滚单状态，也不删除任何记录。
 *
 * <p><b>锁序</b>：阻断核对要求调用方已经持有 {@code base_service(serviceId)} 行锁，并且处于真实事务中。
 * 解除/恢复按 {@code base_service → 发布单/回滚单 → 计划 → 批次} 的固定顺序加锁，与发布认领、
 * 回滚执行、实例移除登记一致。
 *
 * <p><b>不连接任何目标主机</b>：本类只读写数据库，不调用 SSH、Docker、kubectl，也不猜测远端状态。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RuntimeExecutionUnknownService {

    public static final String MESSAGE_ALREADY_UNRESOLVED =
            "该服务在当前环境存在未解除的结果未知执行，已拒绝本次操作。";

    private static final String BIZ_TYPE_UNKNOWN = "RUNTIME_EXECUTION_UNKNOWN";
    private static final String BIZ_TYPE_RELEASE_ORDER = "RELEASE_ORDER";
    private static final String BIZ_TYPE_ROLLBACK_ORDER = "ROLLBACK_ORDER";

    private static final String ACTION_RESOLVE = "RESOLVE_RUNTIME_EXECUTION_UNKNOWN";
    private static final String ACTION_RESOLVE_LEGACY = "RESOLVE_LEGACY_RUNTIME_EXECUTION_UNKNOWN";
    private static final String ACTION_RECOVER_STUCK = "RECOVER_STUCK_EXECUTION";
    /** 首次登记未解除事实。与解除、恢复一起构成“产生—核对—放行”的完整审计链。 */
    private static final String ACTION_DETECT = "DETECT_RUNTIME_EXECUTION_UNKNOWN";
    /** 订单级登记：没有任何 UNKNOWN 部署记录时的兜底事实。 */
    private static final String ACTION_DETECT_ORDER_LEVEL = "DETECT_RUNTIME_EXECUTION_UNKNOWN_ORDER_LEVEL";
    /** 订单级登记被 V31 去重索引合并到另一条事实（灰度批次归属丢失）：必须留痕，不能静默丢弃。 */
    private static final String ACTION_DETECT_ORDER_LEVEL_MERGED = "DETECT_RUNTIME_EXECUTION_UNKNOWN_MERGED";

    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_FAILED = "FAILED";
    private static final String STATUS_UNKNOWN = "UNKNOWN";

    private static final Set<String> ALLOWED_OUTCOMES = Set.of(
            RuntimeExecutionUnknown.OUTCOME_APPLIED,
            RuntimeExecutionUnknown.OUTCOME_NOT_APPLIED,
            RuntimeExecutionUnknown.OUTCOME_PARTIAL);

    /**
     * 人工恢复只允许处理“已经过了执行时间窗”的 RUNNING 记录。它不能证明远端动作已经结束，
     * 但能避免把一个正在正常执行的命令在几秒钟内误判为卡住。
     */
    static final Duration RECOVERY_STALE_AFTER = Duration.ofMinutes(30);

    /** 已解除历史列表一次最多返回的条数，与未解除列表一致。 */
    private static final int RESOLVED_LIMIT = 500;

    private final RuntimeExecutionUnknownMapper unknownMapper;
    private final RuntimeDeployRecordMapper deployRecordMapper;
    private final ReleaseOrderMapper releaseOrderMapper;
    private final ReleaseRollbackOrderMapper releaseRollbackOrderMapper;
    private final ReleaseCanaryPlanMapper releaseCanaryPlanMapper;
    private final ReleaseCanaryBatchMapper releaseCanaryBatchMapper;
    private final BaseServiceMapper baseServiceMapper;
    private final SysAuditLogMapper sysAuditLogMapper;
    private final ObjectMapper objectMapper;
    /**
     * 部署认领：人工恢复前必须确认没有 HELD 认领，避免把一次仍在进行的执行改写成“结果未知”。
     */
    private final ReleaseExecutionRunClaimService releaseExecutionRunClaimService;
    /**
     * 被合并登记台账：去重粒度导致的批次合并必须可见，并且解除前要求操作者显式确认。
     */
    private final RuntimeExecutionUnknownMergeService runtimeExecutionUnknownMergeService;

    /** 迁移是否已经应用；只在第一次查询时探测并缓存。 */
    private volatile Boolean markerTableAvailable;

    // ------------------------------------------------------------------
    // 阻断核对
    // ------------------------------------------------------------------

    /**
     * 同一服务/环境存在未收口的执行事实时拒绝。
     *
     * <p>事实有三类：未解除的登记行、没有登记行的历史 UNKNOWN 部署记录、仍为 HELD 的一次性执行认领。
     * 第三类不依赖订单状态，因此“订单已被收敛为 FAILED”或“认领已释放”都不会成为放行新冲突动作的理由。
     *
     * <p><b>调用约定</b>：必须在真实事务中、且已经持有 {@code base_service(serviceId)} 行锁之后调用。
     * 本方法用 {@link Propagation#MANDATORY} 拒绝无事务调用；行锁本身无法在这里验证，由调用方保证，
     * 所有调用点都位于既有认领/协调事务的锁内。
     *
     * <p><b>调用顺序</b>：任何新执行都必须先完成本核对、再写下自己的认领。反过来做会读到自己刚写的
     * HELD 行并被自己的认领挡住。
     *
     * @throws BizException 存在未收口的执行事实，或登记表未就绪且已有历史 UNKNOWN 记录
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void assertNoUnresolvedUnknown(Long serviceId, Long envId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new BizException(ErrorCode.INTERNAL_ERROR,
                    "结果未知阻断核对未处于事务中，行锁不会生效，已拒绝执行");
        }
        if (serviceId == null || envId == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR,
                    "结果未知阻断核对缺少服务或环境，已按失败关闭拒绝执行");
        }

        BlockingFact blocking = findBlocking(serviceId, envId);
        if (blocking != null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, buildBlockMessage(blocking));
        }
    }

    /** 流水线部署步骤没有自己的认领事务：用短事务取服务行锁后核对，提交即释放。 */
    @Transactional
    public void assertNoUnresolvedUnknownWithServiceLock(Long serviceId, Long envId) {
        if (serviceId == null || baseServiceMapper.lockById(serviceId) == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR,
                    "结果未知阻断核对找不到服务(id=" + serviceId + ")，已拒绝执行");
        }
        assertNoUnresolvedUnknown(serviceId, envId);
    }

    /**
     * 只读判定：同一服务/环境是否存在**未收口的执行事实**。
     *
     * <p>事实口径与 {@link #assertNoUnresolvedUnknown} 完全一致，共三类：
     * 未解除的登记行、没有登记行的历史 UNKNOWN 部署记录（迁移未应用时只看这一类），
     * 以及仍为 HELD 的一次性执行认领。第三类是本轮补上的：未知事实登记失败时，
     * 文档里不会出现 UNKNOWN 记录，订单也可能已经被流水线回调收敛为 FAILED，
     * 此时只剩“平台开了这次执行却没释放”这一行持久事实可用。
     *
     * <p>与断言版一样，**不抛异常**，供需要区分“这个终态能不能被采纳”而不是“这个操作能不能开始”
     * 的路径使用：流水线成功回调就是这种路径，它必须能在存在这类事实时如实不采纳成功，
     * 而不是把异常吞掉或把它当成成功。
     *
     * <p>不需要事务或行锁：这里只把结果用于“采纳 / 不采纳”的判断，不对事实做任何写入。
     */
    public boolean hasUnresolvedUnknown(Long serviceId, Long envId) {
        if (serviceId == null || envId == null) {
            // 身份不全时按“存在未知事实”处理：宁可拒绝收敛终态，也不在不知道服务/环境的情况下写成成功。
            return true;
        }
        return findBlocking(serviceId, envId) != null;
    }

    private BlockingFact findBlocking(Long serviceId, Long envId) {
        // HELD 认领本身就是阻断事实：它表示平台开了一次执行而没有正常收口。把它与未知事实一起核对，
        // 是为了堵住“未知事实登记失败、订单又被流水线回调收敛为 FAILED”这条窗口——那时既没有 UNKNOWN
        // 记录也没有登记行，订单状态已经不能作为放行依据，唯一还站得住的事实就是这行 HELD 认领。
        List<Long> heldClaimIds = releaseExecutionRunClaimService.listHeldClaimIdsForServiceAndEnv(serviceId, envId);

        if (markerTableAvailable()) {
            List<RuntimeExecutionUnknown> unresolved = unknownMapper.listUnresolved(serviceId, envId);
            if (!unresolved.isEmpty()) {
                return BlockingFact.fromMarkers(unresolved, heldClaimIds);
            }
            List<RuntimeDeployRecord> legacy = unknownMapper.listLegacyUnknownWithoutMarker(serviceId, envId);
            if (!legacy.isEmpty()) {
                return BlockingFact.fromLegacy(legacy, heldClaimIds);
            }
        } else {
            long legacyCount = unknownMapper.countUnknownDeployRecordsByServiceAndEnv(serviceId, envId);
            if (legacyCount > 0) {
                return BlockingFact.legacyWithoutMarkerTable(legacyCount, heldClaimIds);
            }
        }

        if (!heldClaimIds.isEmpty()) {
            return BlockingFact.fromHeldClaims(heldClaimIds);
        }
        return null;
    }

    private String buildBlockMessage(BlockingFact fact) {
        StringBuilder message = new StringBuilder();
        if (fact.count() > 0) {
            message.append(MESSAGE_ALREADY_UNRESOLVED)
                    .append("未知事实 ").append(fact.count()).append(" 条");
            if (!fact.releaseOrderIds().isEmpty()) {
                message.append("；关联发布单 ").append(joinIds(fact.releaseOrderIds()));
            }
            if (!fact.rollbackOrderIds().isEmpty()) {
                message.append("；关联回滚单 ").append(joinIds(fact.rollbackOrderIds()));
            }
            if (!fact.deployRecordIds().isEmpty()) {
                message.append("；部署记录 ").append(joinIds(fact.deployRecordIds()));
            }
            if (!fact.unknownIds().isEmpty()) {
                message.append("；未知记录 ").append(joinIds(fact.unknownIds()));
            }
            message.append("。请由 SYSTEM_ADMIN 在“结果未知执行”页面人工核对远端状态并解除；")
                    .append("平台不会自动连接目标主机验证。");
            if (!fact.markerTableAvailable()) {
                message.append(" 当前结果未知登记表未就绪（V31 迁移未应用），只能按已有 UNKNOWN 部署记录阻断，")
                        .append("解除操作在迁移应用并重启前不可用。");
            }
        }
        if (!fact.heldClaimIds().isEmpty()) {
            if (message.length() > 0) {
                message.append(" ");
            }
            message.append("本次操作所属服务/环境还存在未收口的执行认领 ")
                    .append(joinIds(fact.heldClaimIds()))
                    .append("（status=HELD）：平台开了一次执行而没有正常释放，它本身就是阻断事实，")
                    .append("不因发布单已经收敛为 FAILED 而消失。请由 SYSTEM_ADMIN 先在执行认领页面核对")
                    .append("该次执行，确认不再由平台控制后显式放弃认领；放弃认领不声明远端动作已经停止，")
                    .append("远端状态仍需人工核对。");
        }
        return message.toString();
    }

    private static String joinIds(List<Long> ids) {
        List<Long> limited = ids.stream().filter(Objects::nonNull).distinct().limit(5).toList();
        String joined = limited.stream().map(String::valueOf).reduce((a, b) -> a + ", " + b).orElse("");
        return ids.size() > limited.size() ? joined + " 等" : joined;
    }

    // ------------------------------------------------------------------
    // 登记：必须与 UNKNOWN 状态写入同一事务
    // ------------------------------------------------------------------

    /**
     * 把一条已经写成 UNKNOWN 的部署记录登记为未解除事实。
     *
     * <p>必须在写入 UNKNOWN 的同一事务内调用（{@link Propagation#MANDATORY}）。登记失败会随事务一起
     * 回滚，订单/记录保持 RUNNING，仍然被“执行中”检查挡住，不会出现“订单已终结、未知没有登记”的空窗。
     * 迁移未应用时只记日志不抛错：UNKNOWN 部署记录本身仍然是一份可检测的事实，不能因为缺登记表
     * 而把已经写下的 UNKNOWN 证据回滚掉。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean registerFromDeployRecord(RuntimeDeployRecord record, String detectionSource, String reason) {
        if (record == null || record.getId() == null
                || !STATUS_UNKNOWN.equalsIgnoreCase(record.getDeployStatus())) {
            return false;
        }
        if (!markerTableAvailable()) {
            log.warn("结果未知登记表未就绪，部署记录 {} 仅以 UNKNOWN 状态留证，未生成登记行", record.getId());
            return false;
        }
        RuntimeExecutionUnknown row = new RuntimeExecutionUnknown();
        row.setServiceId(record.getServiceId());
        row.setEnvId(record.getEnvId());
        row.setSourceType(record.getRollbackOrderId() == null
                ? RuntimeExecutionUnknown.SOURCE_RELEASE
                : RuntimeExecutionUnknown.SOURCE_ROLLBACK);
        row.setReleaseOrderId(record.getReleaseOrderId());
        row.setRollbackOrderId(record.getRollbackOrderId());
        row.setDeployRecordId(record.getId());
        row.setStatus(RuntimeExecutionUnknown.STATUS_UNRESOLVED);
        row.setDetectionSource(detectionSource);
        row.setDetectionReason(truncate(reason, 512));
        row.setDetectedAt(OffsetDateTime.now());
        row.setDetectedBy(currentUserIdOrNull());
        boolean inserted = unknownMapper.insertIfAbsent(row) == 1;
        if (inserted) {
            // 登记未解除事实本身必须留痕：否则事后无法区分“远端动作确实可能已发出”与“只是代码走到这里”。
            auditUnknownDetected(
                    record.getRollbackOrderId() == null ? BIZ_TYPE_RELEASE_ORDER : BIZ_TYPE_ROLLBACK_ORDER,
                    record.getRollbackOrderId() == null ? record.getReleaseOrderId() : record.getRollbackOrderId(),
                    ACTION_DETECT,
                    auditData("sourceType", row.getSourceType(),
                            "detectionSource", detectionSource,
                            "detectionReason", row.getDetectionReason(),
                            "deployRecordId", record.getId(),
                            "releaseOrderId", record.getReleaseOrderId(),
                            "rollbackOrderId", record.getRollbackOrderId(),
                            "blocking", "UNRESOLVED"));
        }
        return inserted;
    }

    /**
     * 订单级未知登记：为本次未知终态补充没有 deploy_record_id 的登记行。
     * 历史 UNKNOWN 可能已经解除，不能凭记录存在就跳过当前未知事实。
     * 它可能与记录级事实同时存在，需要分别核对和解除。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void registerOrderLevelIfNoUnknownRecords(Long releaseOrderId, Long rollbackOrderId,
                                                     Long planId, Long batchId,
                                                     String detectionSource, String reason) {
        if (releaseOrderId == null && rollbackOrderId == null) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "结果未知缺少订单身份，不能安全结束执行");
        }
        if (!markerTableAvailable()) {
            long existingUnknown = rollbackOrderId != null
                    ? unknownMapper.countUnknownDeployRecordsByRollbackOrder(rollbackOrderId)
                    : unknownMapper.countUnknownDeployRecordsByReleaseOrder(releaseOrderId);
            if (existingUnknown > 0) {
                return; // 无登记表时，历史 UNKNOWN 部署记录仍由阻断查询覆盖。
            }
            throw new BizException(ErrorCode.INTERNAL_ERROR,
                    "结果未知登记表未就绪且没有 UNKNOWN 部署记录，不能安全结束执行；请人工核对");
        }

        Long serviceId;
        Long envId;
        if (rollbackOrderId == null) {
            ReleaseOrder order = releaseOrderMapper.selectById(releaseOrderId);
            if (order == null) {
                throw new BizException(ErrorCode.INTERNAL_ERROR, "结果未知关联的发布单不存在");
            }
            serviceId = order.getServiceId();
            envId = order.getEnvId();
        } else {
            ReleaseRollbackOrder rollback = releaseRollbackOrderMapper.selectById(rollbackOrderId);
            if (rollback == null) {
                throw new BizException(ErrorCode.INTERNAL_ERROR, "结果未知关联的回滚单不存在");
            }
            if (rollback.getReleaseOrderId() == null) {
                serviceId = rollback.getServiceId();
                envId = rollback.getEnvId();
            } else {
                ReleaseOrder order = releaseOrderMapper.selectById(rollback.getReleaseOrderId());
                if (order == null) {
                    throw new BizException(ErrorCode.INTERNAL_ERROR, "结果未知关联的发布单不存在");
                }
                serviceId = order.getServiceId();
                envId = order.getEnvId();
            }
        }
        if (serviceId == null || envId == null) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "结果未知缺少服务或环境身份");
        }

        RuntimeExecutionUnknown row = new RuntimeExecutionUnknown();
        row.setServiceId(serviceId);
        row.setEnvId(envId);
        row.setSourceType(rollbackOrderId != null
                ? RuntimeExecutionUnknown.SOURCE_ROLLBACK
                : RuntimeExecutionUnknown.SOURCE_RELEASE);
        row.setReleaseOrderId(releaseOrderId);
        row.setRollbackOrderId(rollbackOrderId);
        row.setPlanId(planId);
        row.setBatchId(batchId);
        row.setStatus(RuntimeExecutionUnknown.STATUS_UNRESOLVED);
        row.setDetectionSource(detectionSource);
        row.setDetectionReason(truncate(reason, 512));
        row.setDetectedAt(OffsetDateTime.now());
        row.setDetectedBy(currentUserIdOrNull());
        if (unknownMapper.insertIfAbsent(row) == 1) {
            auditUnknownDetected(
                    releaseOrderId != null ? BIZ_TYPE_RELEASE_ORDER : BIZ_TYPE_ROLLBACK_ORDER,
                    releaseOrderId != null ? releaseOrderId : rollbackOrderId,
                    ACTION_DETECT_ORDER_LEVEL,
                    auditData("sourceType", row.getSourceType(),
                            "detectionSource", detectionSource,
                            "detectionReason", row.getDetectionReason(),
                            "planId", planId,
                            "batchId", batchId,
                            "releaseOrderId", releaseOrderId,
                            "rollbackOrderId", rollbackOrderId,
                            "blocking", "UNRESOLVED"));
            return;
        }

        // 插入被去重挡下。是哪一行挡下的，由唯一索引的粒度决定，不由行序决定：
        //   * V31 的索引只到 (source_type, release/rollback order, deploy_record)，不含 plan/batch，
        //     因此同一发布单下不同灰度批次的订单级事实会命中同一行：阻断不会因此消失（同一服务/环境
        //     仍被这条行挡住），但归属会丢失，而且解除这一行会把另一个批次的阻断一并解除；
        //   * V33 把 plan/batch 纳入键，只有 (plan, batch) 相同才互相冲突，命中的必是同一次登记。
        // 所以这里回读**全部候选**并逐行比对，而不是“取 id 最小的一行”：
        // “重复登记”与“被合并到别的批次”是完全不同的结论 —— 前者无需人工介入，后者必须留证并要求显式确认。
        List<RuntimeExecutionUnknown> candidates = unknownMapper.listUnresolvedOrderLevelCandidates(
                row.getSourceType(), releaseOrderId, rollbackOrderId);
        RuntimeExecutionUnknown duplicate = candidates.stream()
                .filter(candidate -> sameFactKey(candidate, planId, batchId))
                .findFirst()
                .orElse(null);
        if (duplicate != null) {
            // 同一次登记重复进入（重放/并发）：既有事实的粒度与本次一致，没有信息丢失，不需要留证。
            log.info("结果未知登记被同一事实去重：既有事实 {} 与本次 planId={}、batchId={} 粒度一致，无需重复登记",
                    duplicate.getId(), planId, batchId);
            return;
        }
        if (candidates.size() != 1) {
            // 既没有粒度一致的既有事实，候选又不唯一 —— 既无法判断是哪一行挡下了插入，
            // 也无法判断本次批次有没有被并入别的事实。事实不可信，失败关闭。
            throw new BizException(ErrorCode.INTERNAL_ERROR,
                    "结果未知登记被去重挡下，但无法确定与之冲突的既有事实（候选 " + candidates.size()
                            + " 条，且没有 planId=" + planId + "、batchId=" + batchId
                            + " 的粒度一致事实），登记事实不可信，已按失败关闭拒绝继续");
        }
        RuntimeExecutionUnknown existing = candidates.get(0);
        // 结论唯一：本次登记被一条粒度不同的既有订单级事实吸收（V31 索引下的批次合并，
        // 或旧数据以 0 表示 NULL 造成的同键不同值）。先把合并留成持久台账：台账写不进去就整笔回滚，
        // 绝不留下“阻断还在、合并不可见”的状态。
        runtimeExecutionUnknownMergeService.recordMergedRegistration(existing, row);
        log.warn("结果未知订单级登记被合并到既有事实 {}（原 planId={}, batchId={}；本次 planId={}, batchId={}）："
                        + "本次灰度批次的归属没有独立留存，解除既有事实会同时放行本次未知，需人工核对",
                existing.getId(), existing.getPlanId(), existing.getBatchId(), planId, batchId);
        auditUnknownDetected(
                releaseOrderId != null ? BIZ_TYPE_RELEASE_ORDER : BIZ_TYPE_ROLLBACK_ORDER,
                releaseOrderId != null ? releaseOrderId : rollbackOrderId,
                ACTION_DETECT_ORDER_LEVEL_MERGED,
                auditData("sourceType", row.getSourceType(),
                        "detectionSource", detectionSource,
                        "detectionReason", row.getDetectionReason(),
                        "mergedIntoUnknownId", existing.getId(),
                        "existingPlanId", existing.getPlanId(),
                        "existingBatchId", existing.getBatchId(),
                        "planId", planId,
                        "batchId", batchId,
                        "releaseOrderId", releaseOrderId,
                        "rollbackOrderId", rollbackOrderId,
                        "blocking", "UNRESOLVED",
                        "mergeLedger", "runtime_execution_unknown_merge"));
    }

    /**
     * 两条订单级登记是否指向同一份事实：plan/batch 按唯一索引的 {@code COALESCE(...,0)} 语义比较
     * （旧数据里可能用 0 表示“无计划/无批次”）。
     */
    private static boolean sameFactKey(RuntimeExecutionUnknown candidate, Long planId, Long batchId) {
        long candidatePlan = candidate.getPlanId() == null ? 0L : candidate.getPlanId();
        long candidateBatch = candidate.getBatchId() == null ? 0L : candidate.getBatchId();
        return candidatePlan == (planId == null ? 0L : planId)
                && candidateBatch == (batchId == null ? 0L : batchId);
    }

    // ------------------------------------------------------------------
    // 收口：把“可能已经开始、但结果无法证明”的一次认领执行登记成持久化事实
    // ------------------------------------------------------------------

    /**
     * 收口一次**由执行认领标识的**中断执行：远端动作可能已经开始，但结果无法证明。
     *
     * <p>与 {@link #registerFromDeployRecord} 的区别是入口不是“已经写好的 UNKNOWN 记录”，而是
     * “这次认领产出、还停在 RUNNING 的记录”。做法分两步：
     * <ol>
     *   <li>把这些 RUNNING 记录逐条条件更新为 UNKNOWN（{@code markUnknownIfRunning}，不覆盖已有终态），
     *       并在同一事务里登记记录级未解除事实；</li>
     *   <li>如果这次认领没有任何 UNKNOWN 记录，再补一条订单级登记——不含 deploy_record_id 的兜底事实，
     *       否则会出现“执行无法证明结果、库里却一条未知事实都没有”的空档。</li>
     * </ol>
     *
     * <p>返回值是给调用方的**释放前提**：true 表示这次执行的未知结果已经落成持久化事实
     * （记录级 UNKNOWN 或登记行），可以释放认领；false 表示身份不足，调用方必须保持认领 HELD。
     * 登记表未就绪且订单没有历史 UNKNOWN 记录时 {@link #registerOrderLevelIfNoUnknownRecords}
     * 会抛错并回滚整个短事务，异常向上传播，调用方同样不得释放认领。
     *
     * <p>独立新事务（{@link Propagation#REQUIRES_NEW}）：调用方是流水线步骤的失败处理，
     * 不能把这份证据挂在任何残留事务上。锁序 {@code base_service → runtime_execution_unknown}
     * 由登记路径内部的读取代替，本方法不额外取锁，因此不会与部署认领形成新的锁环。
     *
     * <p>本方法不判断、也不声明远端是否已经停止，不做任何自动重试或自动回滚。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean closeUnresolvedClaimExecution(Long executionClaimId, Long releaseOrderId,
                                                 Long planId, Long batchId, String reason) {
        if (executionClaimId == null || releaseOrderId == null) {
            return false;
        }
        String normalizedReason = StringUtils.hasText(reason) ? truncate(reason.trim(), 512) : "执行中断，结果无法证明";

        for (RuntimeDeployRecord record : deployRecordMapper.listRunningByExecutionClaimId(executionClaimId)) {
            String append = "执行中断（认领 " + executionClaimId + "）：远端动作可能已经开始，结果无法证明；"
                    + normalizedReason;
            if (deployRecordMapper.markUnknownIfRunning(record.getId(), append) == 1) {
                record.setDeployStatus(STATUS_UNKNOWN);
                registerFromDeployRecord(record, RuntimeExecutionUnknown.DETECTION_EXCEPTION, append);
            }
        }

        // 记录级事实（含登记表未就绪时的历史 UNKNOWN 记录）本身就覆盖阻断查询，可以释放认领。
        if (deployRecordMapper.countByExecutionClaimIdAndStatus(executionClaimId, STATUS_UNKNOWN) > 0) {
            return true;
        }

        registerOrderLevelIfNoUnknownRecords(releaseOrderId, null, planId, batchId,
                RuntimeExecutionUnknown.DETECTION_EXCEPTION, normalizedReason);
        return true;
    }

    // ------------------------------------------------------------------
    // 查询与解除
    // ------------------------------------------------------------------

    public List<RuntimeExecutionUnknownResponse> listUnresolved(Long serviceId, Long envId) {
        List<RuntimeExecutionUnknownResponse> responses = new ArrayList<>();
        if (markerTableAvailable()) {
            for (RuntimeExecutionUnknown row : unknownMapper.listUnresolved(serviceId, envId)) {
                responses.add(RuntimeExecutionUnknownResponse.fromEntity(row));
            }
            for (RuntimeDeployRecord record : unknownMapper.listLegacyUnknownWithoutMarker(serviceId, envId)) {
                responses.add(RuntimeExecutionUnknownResponse.fromLegacyRecord(record, legacyReason()));
            }
        } else {
            // 登记表不可用时仍要能看到已有的 UNKNOWN 部署记录，否则操作者不知道被什么挡住。
            List<RuntimeDeployRecord> legacy = listLegacyUnknownRecords(serviceId, envId);
            for (RuntimeDeployRecord record : legacy) {
                responses.add(RuntimeExecutionUnknownResponse.fromLegacyRecord(record, legacyReason()));
            }
        }
        enrichWithMergedFacts(responses);
        responses.sort(Comparator
                .comparing(RuntimeExecutionUnknownResponse::getDetectedAt,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .reversed());
        return responses;
    }

    /**
     * 给订单级事实补上“吸收了哪些别的登记”。
     *
     * <p>没有任何合并痕迹时保持 0/空；台账不可用时 {@code mergeLedgerAvailable=false}，
     * 页面据此把“0 条”表述成“无法核实”。历史 UNKNOWN 部署记录（{@code legacy}、没有登记行）
     * 不参与合并，也就不会去查台账。
     */
    private void enrichWithMergedFacts(List<RuntimeExecutionUnknownResponse> responses) {
        boolean ledgerAvailable = runtimeExecutionUnknownMergeService.isTableAvailable();
        List<Long> orderLevelIds = responses.stream()
                .filter(response -> !response.isLegacy())
                .filter(response -> RuntimeExecutionUnknownResponse.FACT_LEVEL_ORDER.equals(response.getFactLevel()))
                .map(RuntimeExecutionUnknownResponse::getId)
                .filter(Objects::nonNull)
                .toList();
        Map<Long, List<RuntimeExecutionUnknownResponse.MergedFact>> mergedByUnknown = new HashMap<>();
        for (RuntimeExecutionUnknownMerge merge : runtimeExecutionUnknownMergeService.listByUnknownIds(orderLevelIds)) {
            mergedByUnknown
                    .computeIfAbsent(merge.getUnknownId(), key -> new ArrayList<>())
                    .add(RuntimeExecutionUnknownResponse.MergedFact.fromMerge(merge));
        }
        for (RuntimeExecutionUnknownResponse response : responses) {
            response.setMergeLedgerAvailable(ledgerAvailable);
            List<RuntimeExecutionUnknownResponse.MergedFact> merged = response.getId() == null
                    ? null
                    : mergedByUnknown.get(response.getId());
            if (merged != null && !merged.isEmpty()) {
                response.setMergedFacts(merged);
                response.setMergedFactCount(merged.size());
            }
        }
    }

    /**
     * 已解除的结果未知执行（历史存档）。
     *
     * <p>与未解除列表分开：RESOLVED 只作历史与审计用，不参与任何阻断判断。
     * 这些行不是“远端已停止”的证据，只是当时操作人给出的结论，页面必须照此表述。
     *
     * <p>登记表未应用时直接拒绝：没有登记表就没有解除记录，返回空列表会被误读成“从未发生过未知”。
     */
    public List<RuntimeExecutionUnknownResponse> listResolved(Long serviceId, Long envId) {
        requireMarkerTable("查询已解除的结果未知执行");
        List<RuntimeExecutionUnknownResponse> responses = new ArrayList<>();
        for (RuntimeExecutionUnknown row : unknownMapper.listResolved(serviceId, envId, RESOLVED_LIMIT)) {
            responses.add(RuntimeExecutionUnknownResponse.fromEntity(row));
        }
        return responses;
    }

    public RuntimeExecutionUnknownResponse getById(Long id) {
        requireMarkerTable("查询结果未知执行");
        RuntimeExecutionUnknown row = unknownMapper.selectById(id);
        if (row == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "结果未知执行记录不存在: " + id);
        }
        RuntimeExecutionUnknownResponse response = RuntimeExecutionUnknownResponse.fromEntity(row);
        enrichWithMergedFacts(List.of(response));
        return response;
    }

    /**
     * 人工解除一条未解除记录。只写解除字段与审计，不改部署状态、不改订单状态、不重试、不调用远端。
     *
     * <p>锁序：先取 {@code base_service} 行锁，再锁登记行；与阻断核对共用同一把服务锁，因此
     * “核对时看到的未解除集合”和“解除提交”不会互相穿插。
     *
     * <p><b>合并守卫</b>：这条订单级事实可能吸收了别的批次登记（去重粒度导致，见
     * {@link RuntimeExecutionUnknownMergeService}）。解除它会**同时**放行那些登记，因此
     * 有已留证的合并、或合并台账不可核实（V34 未应用）时，必须由操作者显式确认后才会解除；
     * 确认这件事本身也会写进审计。
     */
    @Transactional
    public RuntimeExecutionUnknownResponse resolve(Long id, String outcome, String note,
                                                   boolean acknowledgeMergedFacts) {
        requireMarkerTable("解除结果未知执行");
        Long userId = requireUserId("解除结果未知执行");
        String normalizedOutcome = requireOutcome(outcome);
        String normalizedNote = requireNote(note);

        RuntimeExecutionUnknown snapshot = unknownMapper.selectById(id);
        if (snapshot == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "结果未知执行记录不存在: " + id);
        }
        if (!RuntimeExecutionUnknown.STATUS_UNRESOLVED.equals(snapshot.getStatus())) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "结果未知执行记录已经解除，不能重复解除: " + id);
        }
        if (snapshot.getServiceId() == null
                || baseServiceMapper.lockById(snapshot.getServiceId()) == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR,
                    "结果未知执行记录关联的服务不存在，已拒绝解除: " + id);
        }

        RuntimeExecutionUnknown locked = unknownMapper.selectByIdForUpdate(id);
        if (locked == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "结果未知执行记录不存在: " + id);
        }
        if (!RuntimeExecutionUnknown.STATUS_UNRESOLVED.equals(locked.getStatus())) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "结果未知执行记录已经解除，不能重复解除: " + id);
        }

        List<RuntimeExecutionUnknownMerge> mergedFacts =
                runtimeExecutionUnknownMergeService.listByUnknownId(locked.getId());
        boolean mergedFactsVerifiable = runtimeExecutionUnknownMergeService.isTableAvailable();
        requireMergedFactsAcknowledged(locked, mergedFacts, mergedFactsVerifiable, acknowledgeMergedFacts);

        OffsetDateTime resolvedAt = OffsetDateTime.now();
        if (unknownMapper.markResolved(id, normalizedOutcome, normalizedNote, userId, resolvedAt) != 1) {
            throw new BizException(ErrorCode.INTERNAL_ERROR,
                    "结果未知执行记录状态已变化，解除未生效，请刷新后重试: " + id);
        }

        Map<String, Object> before = new LinkedHashMap<>();
        before.put("status", locked.getStatus());
        before.put("releaseOrderId", locked.getReleaseOrderId());
        before.put("rollbackOrderId", locked.getRollbackOrderId());
        before.put("deployRecordId", locked.getDeployRecordId());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", RuntimeExecutionUnknown.STATUS_RESOLVED);
        after.put("outcome", normalizedOutcome);
        after.put("note", normalizedNote);
        after.put("mergedFactCount", mergedFacts.size());
        after.put("mergedFacts", describeMergedFacts(mergedFacts));
        after.put("mergedFactsVerifiable", mergedFactsVerifiable);
        after.put("mergedFactsAcknowledged", acknowledgeMergedFacts);
        after.put("scope", "仅解除本条事实及其名下已留证的合并登记；不声明远端已停止，不解除其他事实");
        auditWithOperator(BIZ_TYPE_UNKNOWN, id, ACTION_RESOLVE, userId, before, after);

        locked.setStatus(RuntimeExecutionUnknown.STATUS_RESOLVED);
        locked.setResolutionOutcome(normalizedOutcome);
        locked.setResolutionNote(normalizedNote);
        locked.setResolvedBy(userId);
        locked.setResolvedAt(resolvedAt);
        RuntimeExecutionUnknownResponse response = RuntimeExecutionUnknownResponse.fromEntity(locked);
        response.setMergedFacts(mergedFacts.stream()
                .map(RuntimeExecutionUnknownResponse.MergedFact::fromMerge)
                .toList());
        response.setMergedFactCount(mergedFacts.size());
        response.setMergeLedgerAvailable(mergedFactsVerifiable);
        return response;
    }

    /**
     * 解除前要求操作者确认“这条事实名下还有其他批次的登记”。
     *
     * <p>两种必须确认的情形：一是台账里有已留证的合并（解除会一并放行它们）；
     * 二是台账不可核实（V34 未应用，无法知道有没有合并）—— 后者不得被当成“没有合并”。
     */
    private void requireMergedFactsAcknowledged(RuntimeExecutionUnknown locked,
                                               List<RuntimeExecutionUnknownMerge> mergedFacts,
                                               boolean mergedFactsVerifiable,
                                               boolean acknowledged) {
        if (acknowledged) {
            return;
        }
        if (!mergedFacts.isEmpty()) {
            throw new BizException(ErrorCode.VALIDATION_ERROR,
                    "该事实还吸收了 " + mergedFacts.size() + " 条其他批次的登记（"
                            + String.join("；", describeMergedFacts(mergedFacts))
                            + "），解除会同时放行这些未独立登记的未知事实；"
                            + "请先逐条人工核对这些批次，再请求显式确认后重试");
        }
        if (!mergedFactsVerifiable) {
            throw new BizException(ErrorCode.VALIDATION_ERROR,
                    "无法核实该事实名下是否存在被合并的其他批次（合并台账未应用，V34 缺失）；"
                            + "灰度发布下解除可能同时放行未独立登记的未知批次，"
                            + "请人工核对全部批次后再请求显式确认后重试");
        }
    }

    private static List<String> describeMergedFacts(List<RuntimeExecutionUnknownMerge> mergedFacts) {
        List<String> descriptions = new ArrayList<>();
        for (RuntimeExecutionUnknownMerge merged : mergedFacts) {
            descriptions.add("合并登记(id=" + merged.getId()
                    + ", planId=" + merged.getPlanId()
                    + ", batchId=" + merged.getBatchId()
                    + ", detectionSource=" + merged.getDetectionSource()
                    + ", mergedAt=" + merged.getMergedAt() + ")");
        }
        return descriptions;
    }

    /**
     * 解除一条历史 UNKNOWN 部署记录：它还没有登记行，解除时补一条已解除的登记行。
     * 同样的规则：不改部署状态、不删除记录、不调用远端。
     */
    @Transactional
    public RuntimeExecutionUnknownResponse resolveLegacy(Long deployRecordId, String outcome, String note) {
        requireMarkerTable("解除历史结果未知执行");
        Long userId = requireUserId("解除历史结果未知执行");
        String normalizedOutcome = requireOutcome(outcome);
        String normalizedNote = requireNote(note);

        RuntimeDeployRecord snapshot = deployRecordMapper.selectById(deployRecordId);
        if (snapshot == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "部署记录不存在: " + deployRecordId);
        }
        if (!STATUS_UNKNOWN.equalsIgnoreCase(snapshot.getDeployStatus())) {
            throw new BizException(ErrorCode.VALIDATION_ERROR,
                    "部署记录当前状态不是 UNKNOWN，不能按历史未知解除: " + deployRecordId);
        }
        if (snapshot.getServiceId() == null
                || baseServiceMapper.lockById(snapshot.getServiceId()) == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR,
                    "部署记录关联的服务不存在，已拒绝解除: " + deployRecordId);
        }

        RuntimeDeployRecord locked = deployRecordMapper.selectByIdForUpdate(deployRecordId);
        if (locked == null || !STATUS_UNKNOWN.equalsIgnoreCase(locked.getDeployStatus())) {
            throw new BizException(ErrorCode.VALIDATION_ERROR,
                    "部署记录状态在核对期间发生变化，已拒绝解除: " + deployRecordId);
        }
        if (unknownMapper.countMarkersByDeployRecord(deployRecordId) > 0) {
            throw new BizException(ErrorCode.VALIDATION_ERROR,
                    "该部署记录已有结果未知登记行，请按未知记录 ID 解除: " + deployRecordId);
        }

        OffsetDateTime now = OffsetDateTime.now();
        RuntimeExecutionUnknown row = new RuntimeExecutionUnknown();
        row.setServiceId(locked.getServiceId());
        row.setEnvId(locked.getEnvId());
        row.setSourceType(locked.getRollbackOrderId() == null
                ? RuntimeExecutionUnknown.SOURCE_RELEASE
                : RuntimeExecutionUnknown.SOURCE_ROLLBACK);
        row.setReleaseOrderId(locked.getReleaseOrderId());
        row.setRollbackOrderId(locked.getRollbackOrderId());
        row.setDeployRecordId(locked.getId());
        row.setStatus(RuntimeExecutionUnknown.STATUS_RESOLVED);
        row.setDetectionSource("LEGACY");
        row.setDetectionReason(legacyReason());
        row.setDetectedAt(locked.getEndedAt() != null ? locked.getEndedAt() : locked.getCreatedAt());
        row.setResolutionOutcome(normalizedOutcome);
        row.setResolutionNote(normalizedNote);
        row.setResolvedBy(userId);
        row.setResolvedAt(now);
        unknownMapper.insertResolved(row);

        RuntimeExecutionUnknown latest = unknownMapper.selectLatestByDeployRecordId(deployRecordId);

        Map<String, Object> before = new LinkedHashMap<>();
        before.put("deployRecordId", deployRecordId);
        before.put("deployStatus", locked.getDeployStatus());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", RuntimeExecutionUnknown.STATUS_RESOLVED);
        after.put("outcome", normalizedOutcome);
        after.put("note", normalizedNote);
        auditWithOperator(BIZ_TYPE_UNKNOWN, deployRecordId, ACTION_RESOLVE_LEGACY, userId, before, after);

        return latest != null
                ? RuntimeExecutionUnknownResponse.fromEntity(latest)
                : RuntimeExecutionUnknownResponse.fromLegacyRecord(locked, legacyReason());
    }

    // ------------------------------------------------------------------
    // 卡住执行的人工恢复（RUNNING -> UNKNOWN）
    // ------------------------------------------------------------------

    /**
     * 把卡在 RUNNING 的发布单恢复为“结果未知”：只把仍为 RUNNING 的部署记录改成 UNKNOWN 并登记，
     * 再把订单收敛为 FAILED。不发送任何信号、不重试、不回滚。
     *
     * <p>条件：
     * <ul>
     *   <li>发布单必须是 RUNNING；</li>
     *   <li>存在 RUNNING 部署记录时，每条的 started_at 必须早于 {@link #RECOVERY_STALE_AFTER}；</li>
     *   <li>没有任何部署记录时，订单 updated_at 也必须早于该时间窗（防止与刚提交的认领竞争）；</li>
     *   <li>记录都已终止且没有 UNKNOWN 时拒绝：这不是结果未知，不能借恢复改状态。</li>
     * </ul>
     */
    @Transactional
    public RecoverStuckExecutionResponse recoverStuckRelease(Long releaseOrderId, String note) {
        requireMarkerTable("恢复卡住的发布执行");
        Long userId = requireUserId("恢复卡住的发布执行");
        String normalizedNote = requireNote(note);
        if (releaseOrderId == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "发布单 ID 不能为空");
        }

        ReleaseOrder snapshot = releaseOrderMapper.selectById(releaseOrderId);
        if (snapshot == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "发布单不存在: " + releaseOrderId);
        }
        if (snapshot.getServiceId() == null
                || baseServiceMapper.lockById(snapshot.getServiceId()) == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "发布单关联的服务不存在，已拒绝恢复");
        }
        ReleaseOrder order = releaseOrderMapper.selectForUpdate(releaseOrderId);
        if (order == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "发布单不存在: " + releaseOrderId);
        }
        if (!STATUS_RUNNING.equalsIgnoreCase(order.getStatus())) {
            throw new BizException(ErrorCode.VALIDATION_ERROR,
                    "只有 RUNNING 发布单允许人工恢复为结果未知；当前状态: " + order.getStatus());
        }
        // 认领仍是 HELD 说明平台这次执行没有正常收口，而远端动作可能仍在进行。
        // 此时把记录改写成 UNKNOWN 会与事后真正的执行结果并存，因此先要求操作人显式放弃该认领：
        // 放弃表达“平台不再认为这次执行在飞”，它本身不声明远端已经停止。
        if (releaseExecutionRunClaimService.hasHeldDeployClaim(releaseOrderId)) {
            throw new BizException(ErrorCode.VALIDATION_ERROR,
                    "发布单 " + releaseOrderId + " 仍有一行正在执行（HELD）的部署认领，不能直接登记结果未知；"
                            + "请先在执行认领页面确认该次执行已不再由平台控制并显式放弃认领，再执行人工恢复");
        }

        OffsetDateTime now = OffsetDateTime.now();
        List<RuntimeDeployRecord> running = deployRecordMapper.listRunningByReleaseOrderId(releaseOrderId);
        int registered = 0;
        int recovered = 0;
        int alreadyUnknown = 0;

        if (running.isEmpty()) {
            long unknownCount = deployRecordMapper.countByReleaseOrderIdAndStatus(releaseOrderId, STATUS_UNKNOWN);
            if (unknownCount > 0) {
                // 终态写入已产生 UNKNOWN，但订单没有收敛：补齐登记，不改部署记录。
                for (RuntimeDeployRecord record : deployRecordMapper.listByReleaseOrderId(releaseOrderId)) {
                    if (STATUS_UNKNOWN.equalsIgnoreCase(record.getDeployStatus())) {
                        if (registerFromDeployRecord(record, RuntimeExecutionUnknown.DETECTION_RECOVERY,
                                "人工恢复：订单仍为 RUNNING，部署记录已为 UNKNOWN；" + normalizedNote)) {
                            registered++;
                        }
                    }
                }
            } else if (deployRecordMapper.listByReleaseOrderId(releaseOrderId).isEmpty()) {
                requireStale(order.getUpdatedAt(), now, "发布单");
                // 旧执行路径和并发中的工作线程不能仅由记录缺失排除。
                throw new BizException(ErrorCode.VALIDATION_ERROR,
                        "发布单没有部署记录，不能证明远端操作未开始；请人工核对，订单保持 RUNNING");
            } else {
                throw new BizException(ErrorCode.VALIDATION_ERROR,
                        "发布单仍有部署记录，但没有 RUNNING/UNKNOWN 记录；这不是结果未知，"
                                + "请人工核对该单的实际状态，不能借恢复改状态");
            }
        } else {
            requireStaleRecords(running, now);
            List<String> endedRecords = new ArrayList<>();
            for (RuntimeDeployRecord record : running) {
                String append = "人工恢复：执行超过时间窗仍为 RUNNING，结果未知；" + normalizedNote;
                if (deployRecordMapper.markUnknownIfRunning(record.getId(), append) == 1) {
                    record.setDeployStatus(STATUS_UNKNOWN);
                    recovered++;
                    if (registerFromDeployRecord(record, RuntimeExecutionUnknown.DETECTION_RECOVERY, append)) {
                        registered++;
                    }
                    continue;
                }
                // 与回滚恢复同理：部署记录终态写在独立短事务里，订单终态写在另一个事务里，
                // 两者之间崩溃会留下“订单 RUNNING、记录已有终态”的中间态。
                // 只有“当前确实是 UNKNOWN”才继续；已是成功/失败终态说明执行有确定结果，
                // 恢复的前提不成立，必须如实失败而不是把终态覆盖成 UNKNOWN。
                String currentStatus = deployRecordMapper.selectStatusById(record.getId());
                if (STATUS_UNKNOWN.equalsIgnoreCase(currentStatus)) {
                    alreadyUnknown++;
                    continue;
                }
                endedRecords.add(record.getId() + "(" + (currentStatus == null ? "已不存在" : currentStatus) + ")");
            }
            if (!endedRecords.isEmpty()) {
                throw new BizException(ErrorCode.VALIDATION_ERROR,
                        "以下部署记录已有确定结果，人工恢复的前提不成立，本次未做任何修改；"
                                + "请刷新后按实际状态处理。记录: " + String.join(", ", endedRecords));
            }
        }

        markActiveCanaryPlanFailedLocked(order, normalizedNote, now);
        int updated = releaseOrderMapper.markFailedIfRunning(releaseOrderId);
        if (updated != 1 && !STATUS_FAILED.equalsIgnoreCase(order.getStatus())) {
            throw new BizException(ErrorCode.INTERNAL_ERROR,
                    "发布单状态已变化，人工恢复未生效: " + releaseOrderId);
        }
        order.setStatus(STATUS_FAILED);

        auditReleaseRecovery(order, userId, ACTION_RECOVER_STUCK,
                Map.of("status", STATUS_RUNNING), Map.of(
                        "status", STATUS_FAILED,
                        "note", normalizedNote,
                        "runningRecordsRecovered", recovered + alreadyUnknown,
                        "unknownRecordsRegistered", registered));

        return new RecoverStuckExecutionResponse("RELEASE", releaseOrderId, recovered + alreadyUnknown, registered,
                "已把仍为 RUNNING 的部署记录收敛为 UNKNOWN（新登记 " + registered + " 条，恢复前已是 UNKNOWN "
                        + alreadyUnknown + " 条），并把发布单收敛为 FAILED；"
                        + "这只表示系统记录了未知，不代表远端进程已停止；请人工核对远端状态并在结果未知页面给出结论");
    }

    /**
     * 回滚执行的同类恢复：只处理仍为 RUNNING 的回滚单与部署记录。
     * 没有部署记录时拒绝（回滚单没有独立的执行开始时间，无法排除刚认领的并发窗口）。
     */
    @Transactional
    public RecoverStuckExecutionResponse recoverStuckRollback(Long rollbackOrderId, String note) {
        requireMarkerTable("恢复卡住的回滚执行");
        Long userId = requireUserId("恢复卡住的回滚执行");
        String normalizedNote = requireNote(note);
        if (rollbackOrderId == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "回滚单 ID 不能为空");
        }

        ReleaseRollbackOrder snapshot = releaseRollbackOrderMapper.selectById(rollbackOrderId);
        if (snapshot == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "回滚单不存在: " + rollbackOrderId);
        }
        ReleaseOrder releaseOrderSnapshot = snapshot.getReleaseOrderId() == null ? null : releaseOrderMapper.selectById(snapshot.getReleaseOrderId());
        Long serviceId = releaseOrderSnapshot == null ? snapshot.getServiceId() : releaseOrderSnapshot.getServiceId();
        if (serviceId == null || baseServiceMapper.lockById(serviceId) == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "回滚单关联的服务不存在，已拒绝恢复");
        }
        ReleaseRollbackOrder rollback = releaseRollbackOrderMapper.selectFullForUpdate(rollbackOrderId);
        if (rollback == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "回滚单不存在: " + rollbackOrderId);
        }
        if (!STATUS_RUNNING.equalsIgnoreCase(rollback.getStatus())) {
            throw new BizException(ErrorCode.VALIDATION_ERROR,
                    "只有 RUNNING 回滚单允许人工恢复为结果未知；当前状态: " + rollback.getStatus());
        }

        OffsetDateTime now = OffsetDateTime.now();
        List<RuntimeDeployRecord> running = deployRecordMapper.listRunningByRollbackOrderId(rollbackOrderId);
        if (running.isEmpty()) {
            throw new BizException(ErrorCode.VALIDATION_ERROR,
                    "回滚单没有 RUNNING 部署记录，无法证明执行已经卡住；请人工核对该回滚单");
        }
        requireStaleRecords(running, now);

        int registered = 0;
        int recovered = 0;
        int alreadyUnknown = 0;
        List<String> endedRecords = new ArrayList<>();
        for (RuntimeDeployRecord record : running) {
            String append = "人工恢复：回滚执行超过时间窗仍为 RUNNING，结果未知；" + normalizedNote;
            if (deployRecordMapper.markUnknownIfRunning(record.getId(), append) == 1) {
                record.setDeployStatus(STATUS_UNKNOWN);
                recovered++;
                if (registerFromDeployRecord(record, RuntimeExecutionUnknown.DETECTION_RECOVERY, append)) {
                    registered++;
                }
                continue;
            }
            // 条件更新没有生效：记录已经被其它流程改写（例如部署记录的终态写在独立短事务里，
            // 而回滚单的终态写在长事务里，两者之间崩溃就会留下这种中间态）。
            // 只有“当前确实已经是 UNKNOWN”才可以继续；已经是成功/失败终态说明执行有确定结果，
            // 人工恢复的前提（结果未知）不成立，必须如实失败而不是把终态覆盖成 UNKNOWN。
            String currentStatus = deployRecordMapper.selectStatusById(record.getId());
            if (STATUS_UNKNOWN.equalsIgnoreCase(currentStatus)) {
                alreadyUnknown++;
                continue;
            }
            endedRecords.add(record.getId() + "(" + (currentStatus == null ? "已不存在" : currentStatus) + ")");
        }
        if (!endedRecords.isEmpty()) {
            throw new BizException(ErrorCode.VALIDATION_ERROR,
                    "以下部署记录已有确定结果，人工恢复的前提不成立，本次未做任何修改；"
                            + "请刷新后按实际状态处理。记录: " + String.join(", ", endedRecords));
        }

        // 只改状态列，且只在仍是 RUNNING 时生效：整行 updateById 会把并发写下的其它列一起写回旧快照。
        if (releaseRollbackOrderMapper.markFailedIfRunning(rollbackOrderId) != 1) {
            throw new BizException(ErrorCode.INTERNAL_ERROR,
                    "回滚单状态在恢复过程中被改写，未收敛为 FAILED；请刷新后重试: " + rollbackOrderId);
        }
        rollback.setStatus(STATUS_FAILED);
        // 发布单同样只改回滚状态一列；不是 ROLLING_BACK 时不覆盖，也不当作恢复失败
        // （未知事实与回滚单终态已经落下，发布单那一列只是账目）。
        if (rollback.getReleaseOrderId() != null && releaseOrderMapper.markRollbackStatusIfExpected(
                rollback.getReleaseOrderId(), "ROLLBACK_FAILED", "ROLLING_BACK") != 1) {
            log.warn("发布单 {} 的回滚状态不是 ROLLING_BACK，本次恢复未改写该列", rollback.getReleaseOrderId());
        }

        auditRollbackRecovery(rollback, userId, ACTION_RECOVER_STUCK,
                Map.of("status", STATUS_RUNNING), Map.of(
                        "status", STATUS_FAILED,
                        "note", normalizedNote,
                        "runningRecordsRecovered", recovered + alreadyUnknown,
                        "unknownRecordsRegistered", registered));

        return new RecoverStuckExecutionResponse("ROLLBACK", rollbackOrderId, recovered + alreadyUnknown, registered,
                "已把仍为 RUNNING 的部署记录收敛为 UNKNOWN（新登记 " + registered + " 条，恢复前已是 UNKNOWN "
                        + alreadyUnknown + " 条），并把回滚单收敛为 FAILED；"
                        + "这只表示系统记录了未知，不代表远端进程已停止；请人工核对远端状态并在结果未知页面给出结论");
    }

    private void markActiveCanaryPlanFailedLocked(ReleaseOrder order, String note, OffsetDateTime now) {
        ReleaseCanaryPlan plan = releaseCanaryPlanMapper.selectByReleaseOrderIdForUpdate(order.getId());
        if (plan == null) {
            return;
        }
        if (ReleaseCanaryPlan.STATUS_SUCCESS.equals(plan.getStatus())
                || ReleaseCanaryPlan.STATUS_ROLLED_BACK.equals(plan.getStatus())) {
            return;
        }
        String reason = "[UNKNOWN] 人工恢复：灰度执行中断，结果未知；" + note;
        for (ReleaseCanaryBatch batch : releaseCanaryBatchMapper.findByPlanIdForUpdate(plan.getId())) {
            if (ReleaseCanaryBatch.STATUS_RUNNING.equals(batch.getStatus())) {
                releaseCanaryBatchMapper.finishIfRunning(batch.getId(),
                        ReleaseCanaryBatch.STATUS_FAILED, now, reason);
            }
        }
        releaseCanaryPlanMapper.markFailedIfActive(plan.getId());
        releaseCanaryPlanMapper.updateFailureReason(plan.getId(), truncate(reason, 512));
    }

    private void requireStaleRecords(List<RuntimeDeployRecord> records, OffsetDateTime now) {
        for (RuntimeDeployRecord record : records) {
            if (record.getStartedAt() == null) {
                throw new BizException(ErrorCode.VALIDATION_ERROR,
                        "部署记录 " + record.getId() + " 没有开始时间，无法证明执行已经卡住；已拒绝恢复");
            }
            requireStale(record.getStartedAt(), now, "部署记录 " + record.getId());
        }
    }

    private static void requireStale(OffsetDateTime since, OffsetDateTime now, String label) {
        if (since == null || since.isAfter(now.minus(RECOVERY_STALE_AFTER))) {
            throw new BizException(ErrorCode.VALIDATION_ERROR,
                    label + " 仍在 " + RECOVERY_STALE_AFTER.toMinutes()
                            + " 分钟执行时间窗内，不能判定为卡住；请等待后再试");
        }
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    /**
     * 只读判定：结果未知登记表是否可用（V31 是否已应用）。
     *
     * <p>不可用时“登记”“恢复”“解除”三个入口都会按失败关闭拒绝，但**阅读**仍然有效：
     * {@link #listUnresolved} 会退回按已有 UNKNOWN 部署记录展示阻断。对账页面据此把
     * “有事实挡着”与“平台没有解除手段”分开陈述，而不是让操作者去点一个必然失败的入口。
     */
    public boolean isMarkerTableAvailable() {
        return markerTableAvailable();
    }

    private boolean markerTableAvailable() {
        Boolean cached = markerTableAvailable;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (markerTableAvailable != null) {
                return markerTableAvailable;
            }
            // to_regclass 在表不存在时返回 null 而不报错，因此可以在已有事务内安全探测。
            // 迁移在运行期间应用时需要重启服务才能解除负缓存，提示文案已说明这一点。
            markerTableAvailable = unknownMapper.probe() != null;
            if (!markerTableAvailable) {
                log.warn("数据库表 runtime_execution_unknown 不存在（V31 迁移未应用）；"
                        + "当前只能按已有 UNKNOWN 部署记录阻断，解除/恢复操作不可用");
            }
            return markerTableAvailable;
        }
    }

    private void requireMarkerTable(String scene) {
        if (!markerTableAvailable()) {
            throw new BizException(ErrorCode.INTERNAL_ERROR,
                    scene + "需要数据库表 runtime_execution_unknown（V31 迁移未应用）；"
                            + "已按失败关闭拒绝。请由数据库负责人应用 V31 并重启服务后再操作");
        }
    }

    private static Long requireUserId(String scene) {
        UserContext context = UserContextHolder.get();
        if (context == null || context.getUserId() == null) {
            throw new BizException(ErrorCode.FORBIDDEN,
                    scene + "必须由已登录用户操作；未记录到操作人时拒绝执行");
        }
        return context.getUserId();
    }

    /**
     * 当前登录用户，没有登录上下文时返回 null。
     *
     * <p>登记未解除事实可能发生在后台兜底、流水线回调里，此时没有操作人。
     * 这里不用 {@code getUserIdOrDefault(1L)}：登记表的 detected_by 与审计的 operator_id 都有
     * {@code REFERENCES sys_user(id)}，凭空写一个“默认用户”既可能违反外键，也会把来源记成别人。
     * 两列都可空，如实留空更准确。
     */
    private static Long currentUserIdOrNull() {
        UserContext context = UserContextHolder.get();
        return context == null ? null : context.getUserId();
    }

    private static String requireOutcome(String outcome) {
        String normalized = StringUtils.hasText(outcome)
                ? outcome.trim().toUpperCase(Locale.ROOT) : "";
        if (!ALLOWED_OUTCOMES.contains(normalized)) {
            throw new BizException(ErrorCode.VALIDATION_ERROR,
                    "解除结果必须是 " + String.join(" / ", ALLOWED_OUTCOMES) + " 之一");
        }
        return normalized;
    }

    private static String requireNote(String note) {
        if (!StringUtils.hasText(note)) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "人工核对原因不能为空");
        }
        String trimmed = note.trim();
        if (trimmed.length() > 1000) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "人工核对原因不能超过 1000 个字符");
        }
        return trimmed;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String legacyReason() {
        return "历史 UNKNOWN 部署记录：没有对应的结果未知登记行，按未解除处理";
    }

    private List<RuntimeDeployRecord> listLegacyUnknownRecords(Long serviceId, Long envId) {
        // 登记表不可用时不能执行带 NOT EXISTS 子查询的语句；直接按服务/环境列出 UNKNOWN 记录。
        return deployRecordMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<RuntimeDeployRecord>()
                        .eq(serviceId != null, RuntimeDeployRecord::getServiceId, serviceId)
                        .eq(envId != null, RuntimeDeployRecord::getEnvId, envId)
                        .eq(RuntimeDeployRecord::getDeployStatus, STATUS_UNKNOWN)
                        .orderByDesc(RuntimeDeployRecord::getCreatedAt));
    }

    private void auditReleaseRecovery(ReleaseOrder order, Long userId, String action,
                                      Map<String, Object> before, Map<String, Object> after) {
        auditWithOperator(BIZ_TYPE_RELEASE_ORDER, order.getId(), action, userId, before, after);
    }

    private void auditRollbackRecovery(ReleaseRollbackOrder rollback, Long userId, String action,
                                       Map<String, Object> before, Map<String, Object> after) {
        auditWithOperator(BIZ_TYPE_ROLLBACK_ORDER, rollback.getId(), action, userId, before, after);
    }

    private void auditWithOperator(String bizType, Long bizId, String action, Long operatorId,
                                   Map<String, Object> before, Map<String, Object> after) {
        sysAuditLogMapper.insertAuditLog(
                operatorId,
                bizType,
                bizId,
                action,
                toJson(before),
                toJson(after),
                "SUCCESS",
                null);
    }

    /**
     * 登记未解除事实的审计条目。
     *
     * <p>只记录来源、来源类型、原因与涉及的单号/记录号，不记录命令文本、远端输出或凭据。
     * 操作人可能为空（流水线回调、后台兜底），此时按历史约定记到默认操作人。
     */
    private void auditUnknownDetected(String bizType, Long bizId, String action, Map<String, Object> after) {
        auditWithOperator(bizType, bizId, action, currentUserIdOrNull(), null, after);
    }

    /** 允许 null 值的有序 map；{@code Map.of} 在值为 null 时抛 NPE。 */
    private static Map<String, Object> auditData(Object... entries) {
        Map<String, Object> value = new LinkedHashMap<>();
        for (int index = 0; index < entries.length; index += 2) {
            value.put((String) entries[index], entries[index + 1]);
        }
        return value;
    }

    private String toJson(Map<String, ?> value) {
        try {
            return value == null ? null : objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "审计日志序列化失败");
        }
    }

    /** 一次阻断核对看到的未解除事实摘要；只含 ID 与计数，不含命令、输出或凭据。 */
    private record BlockingFact(int count,
                                List<Long> releaseOrderIds,
                                List<Long> rollbackOrderIds,
                                List<Long> deployRecordIds,
                                List<Long> unknownIds,
                                List<Long> heldClaimIds,
                                boolean markerTableAvailable) {

        static BlockingFact fromMarkers(List<RuntimeExecutionUnknown> rows, List<Long> heldClaimIds) {
            return new BlockingFact(
                    rows.size(),
                    rows.stream().map(RuntimeExecutionUnknown::getReleaseOrderId).filter(Objects::nonNull).toList(),
                    rows.stream().map(RuntimeExecutionUnknown::getRollbackOrderId).filter(Objects::nonNull).toList(),
                    rows.stream().map(RuntimeExecutionUnknown::getDeployRecordId).filter(Objects::nonNull).toList(),
                    rows.stream().map(RuntimeExecutionUnknown::getId).filter(Objects::nonNull).toList(),
                    heldClaimIds,
                    true);
        }

        static BlockingFact fromLegacy(List<RuntimeDeployRecord> rows, List<Long> heldClaimIds) {
            return new BlockingFact(
                    rows.size(),
                    rows.stream().map(RuntimeDeployRecord::getReleaseOrderId).filter(Objects::nonNull).toList(),
                    rows.stream().map(RuntimeDeployRecord::getRollbackOrderId).filter(Objects::nonNull).toList(),
                    rows.stream().map(RuntimeDeployRecord::getId).filter(Objects::nonNull).toList(),
                    List.of(),
                    heldClaimIds,
                    true);
        }

        static BlockingFact legacyWithoutMarkerTable(long count, List<Long> heldClaimIds) {
            return new BlockingFact((int) Math.min(count, Integer.MAX_VALUE),
                    List.of(), List.of(), List.of(), List.of(), heldClaimIds, false);
        }

        /** 没有未知记录也没有登记行，只剩未收口的 HELD 认领：它自己就是阻断事实。 */
        static BlockingFact fromHeldClaims(List<Long> heldClaimIds) {
            return new BlockingFact(0, List.of(), List.of(), List.of(), List.of(), heldClaimIds, true);
        }
    }
}

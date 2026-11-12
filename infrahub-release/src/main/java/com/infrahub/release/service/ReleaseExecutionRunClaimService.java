package com.infrahub.release.service;

import com.infrahub.base.mapper.BaseServiceMapper;
import com.infrahub.common.context.UserContext;
import com.infrahub.common.context.UserContextHolder;
import com.infrahub.common.error.ErrorCode;
import com.infrahub.common.exception.BizException;
import com.infrahub.release.entity.ReleaseExecutionRunClaim;
import com.infrahub.release.entity.ReleaseOrder;
import com.infrahub.release.mapper.ReleaseExecutionRunClaimMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 发布执行的一次性认领凭证：执行期间保持 HELD，执行结束才释放。
 *
 * <p><b>为什么必须持久化</b>：{@code pipeline_run.run_status='RUNNING'} 与
 * {@code release_order.status='RUNNING'} 都是可观察状态，不是凭证。核对在短事务里完成、
 * 提交后锁就释放，另一条流水线仍可能在核对之后启动；同一条流水线里出现第二个 RELEASE_DEPLOY
 * 步骤时，第一个步骤的“核对”也早已失效。只有一行在远端调用之前写入、调用结束后才释放的记录，
 * 才能在**不跨 SSH 持有数据库事务**的前提下把这段窗口关掉。
 *
 * <p><b>一次性</b>：每个执行身份（发布单 + 用途 + 回滚单）只允许一行，唯一索引
 * {@code uk_release_execution_run_claim_identity} 是守卫，{@code ON CONFLICT DO NOTHING}
 * 返回 0 即拒绝。发布单只能从 CREATED/APPROVED 变成一次 RUNNING，因此同一发布单的
 * 部署执行天然只能发生一次：重复投递、同一运行的重复步骤、并发运行全部被这张表挡住。
 *
 * <p><b>锁序</b>：与发布认领、未知登记、人工解除完全一致 ——
 * {@code base_service → release_order → release_execution_run_claim}。
 * {@link #acquireDeployClaim} 要求调用方已经持有前面两级锁并在同一事务内（{@link Propagation#MANDATORY}）；
 * 释放与放弃认领各自开短事务，不持有其它锁。
 *
 * <p><b>不连接任何目标主机</b>：本类只读写数据库，不调用 SSH、Docker、kubectl。
 * 释放认领只说明“平台这次执行已经收口”，不说明远端动作已经结束或已经停止。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReleaseExecutionRunClaimService {

    private static final String MESSAGE_TABLE_MISSING =
            "一次性执行认领需要数据库表 release_execution_run_claim（V32 迁移未应用）；"
                    + "已按失败关闭拒绝执行。请由数据库负责人应用 V32 并重启服务后再操作";

    /**
     * 成功认领的凭证。只能由 {@link #acquireDeployClaim} 产出，内部执行入口只接受这一类型，
     * 从接口上排除“凭订单当前是 RUNNING 就认为这次执行是自己的”。
     */
    public record ExecutionClaimHandle(Long claimId,
                                       Long releaseOrderId,
                                       Long serviceId,
                                       Long envId,
                                       String claimPurpose,
                                       String holderToken) {
    }

    private final ReleaseExecutionRunClaimMapper claimMapper;
    private final BaseServiceMapper baseServiceMapper;
    /** 放弃认领是管理员动作，除认领行本身外还要在审计里留一条可检索的记录。 */
    private final ReleaseAuditLogService releaseAuditLogService;

    /** 迁移是否已经应用；只在第一次查询时探测并缓存。 */
    private volatile Boolean claimTableAvailable;

    // ------------------------------------------------------------------
    // 取得认领
    // ------------------------------------------------------------------

    /**
     * 为发布单取得一次性部署认领。**不是**自己开事务：调用方
     * （{@code ReleaseExecutionClaimService.claimDirectExecution}）已经在同一短事务内持有
     * {@code base_service} 与 {@code release_order} 两级行锁，并已完成状态、独占性、灰度计划与
     * 未知事实核对。把写入放在那一把锁下，才能保证“核对通过”与“写下认领”之间没有插入点。
     *
     * <p>迁移未应用时失败关闭：没有这张表就无法提供一次性认领，也就无法排除并发执行，
     * 此时不提供降级放行。
     *
     * @param lockedOrder 加锁后读回的发布单整行
     * @return 本次成功认领的凭证
     * @throws BizException 无活动事务、迁移未应用、发布单身份缺失，或该执行身份已被占用/已消费
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public ExecutionClaimHandle acquireDeployClaim(ReleaseOrder lockedOrder,
                                                   Long pipelineRunId,
                                                   Long pipelineStepRunId) {
        assertTransactionActive("一次性执行认领");
        requireClaimTable("取得一次性执行认领");
        if (lockedOrder == null || lockedOrder.getId() == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "一次性执行认领缺少发布单身份，已拒绝执行");
        }
        if (pipelineRunId == null || pipelineStepRunId == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR,
                    "一次性执行认领缺少流水线运行或步骤身份，无法绑定归属，已拒绝执行");
        }

        ReleaseExecutionRunClaim row = new ReleaseExecutionRunClaim();
        row.setReleaseOrderId(lockedOrder.getId());
        row.setServiceId(lockedOrder.getServiceId());
        row.setEnvId(lockedOrder.getEnvId());
        row.setClaimPurpose(ReleaseExecutionRunClaim.PURPOSE_DEPLOY);
        row.setHolderKind(ReleaseExecutionRunClaim.HOLDER_PIPELINE_STEP);
        row.setHolderToken(UUID.randomUUID().toString());
        row.setPipelineRunId(pipelineRunId);
        row.setPipelineStepRunId(pipelineStepRunId);
        row.setRollbackOrderId(null);
        row.setClaimedAt(OffsetDateTime.now());

        if (claimMapper.insertHeld(row) == 1) {
            if (row.getId() == null) {
                throw new BizException(ErrorCode.INTERNAL_ERROR, "执行认领未返回身份，已拒绝执行");
            }
            return new ExecutionClaimHandle(row.getId(), row.getReleaseOrderId(), row.getServiceId(),
                    row.getEnvId(), row.getClaimPurpose(), row.getHolderToken());
        }

        // 写入没生效：唯一索引上已经有一行同身份记录。区分两种事实，给出可执行的原因，
        // 但不因此放行——两种情形都意味着这次调用不是该执行的所有者。
        ReleaseExecutionRunClaim existing = claimMapper.selectByIdentity(
                lockedOrder.getId(), ReleaseExecutionRunClaim.PURPOSE_DEPLOY, null);
        if (existing == null) {
            throw new BizException(ErrorCode.INTERNAL_ERROR,
                    "一次性执行认领写入未生效且读不到已有认领行，状态不一致，已拒绝执行: 发布单 "
                            + lockedOrder.getId());
        }
        if (ReleaseExecutionRunClaim.STATUS_HELD.equals(existing.getStatus())) {
            throw new BizException(ErrorCode.VALIDATION_ERROR,
                    "该发布单已有执行中的一次性认领（认领 " + existing.getId()
                            + "，流水线运行 " + existing.getPipelineRunId()
                            + "，步骤运行 " + existing.getPipelineStepRunId()
                            + "），本次不是所有者，拒绝重复执行");
        }
        throw new BizException(ErrorCode.VALIDATION_ERROR,
                "该发布单的部署执行已经被消费过（认领 " + existing.getId()
                        + "，释放结果 " + existing.getReleaseOutcome()
                        + "）；同一发布单只能部署一次，拒绝重复步骤再次执行");
    }

    // ------------------------------------------------------------------
    // 释放认领
    // ------------------------------------------------------------------

    /**
     * 持有者释放自己的认领。部署记录在**创建时**按记录 ID 绑定认领
     * （{@code RuntimeDeployRecordService.startRunning} → {@code bindRecordToExecutionClaim}），
     * 释放动作本身不再写任何部署记录归属：按发布单批量补写会把历史记录、回滚记录或其它执行者的
     * 记录错误地归给本次认领。
     *
     * <p>释放的前提由调用方保证：远端结果无法证明时，必须先确认未知事实已经落成持久化事实
     * （{@code RuntimeExecutionUnknownService.closeUnresolvedClaimExecution}）再释放；
     * 登记不成功就保持 HELD，让 HELD 本身继续阻断同一服务/环境的后续冲突动作。
     *
     * <p>独立新事务提交：外部动作已经发生，认领的释放不能因为调用方残留的事务而丢失。
     * 释放语句要求持有者令牌匹配，因此重复释放返回 0、也不会改到别人的认领。
     *
     * @return true 表示本次调用确实释放了认领；false 表示认领已不是 HELD（重复释放或已被操作人放弃）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean releaseClaim(ExecutionClaimHandle handle, String reason) {
        if (handle == null || handle.claimId() == null || handle.holderToken() == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "释放一次性执行认领缺少凭证，已拒绝");
        }
        requireClaimTable("释放一次性执行认领");

        String normalizedReason = StringUtils.hasText(reason) ? truncate(reason.trim(), 512) : "执行收口";
        int released = claimMapper.markReleasedIfHeld(handle.claimId(), handle.holderToken(),
                ReleaseExecutionRunClaim.OUTCOME_COMPLETED, normalizedReason,
                currentUserIdOrNull(), OffsetDateTime.now());
        if (released != 1) {
            // 不是 HELD：可能是重复释放，也可能是操作人已经放弃这条认领。
            // 都不是错误，但必须让调用方知道事实，不能静默当成“已释放”。
            log.warn("一次性执行认领 {} 不是 HELD，本次释放未生效（重复释放或已被操作人放弃）", handle.claimId());
            return false;
        }

        // 不能按发布单批量绑定：同单可能有历史或回滚记录，无法证明由此认领产生。
        return true;
    }

    /**
     * 操作人放弃一条 HELD 认领。用于进程崩溃后没有任何线程会再释放它的情形。
     *
     * <p>只说明“平台不再认为这次执行在飞”，**不**声明远端动作已经结束或已经停止；
     * 如果该执行留下了 UNKNOWN 部署记录，未知事实仍然独立阻断，不因放弃认领而解除。
     *
     * <p>锁序 {@code base_service → release_execution_run_claim}：与服务行锁一致，不与发布认领、
     * 未知解除形成环路。
     */
    @Transactional
    public ReleaseExecutionRunClaim abandonClaim(Long claimId, String note) {
        requireClaimTable("放弃一次性执行认领");
        Long userId = requireUserId("放弃一次性执行认领");
        String normalizedNote = requireNote(note);

        ReleaseExecutionRunClaim snapshot = claimMapper.selectClaimById(claimId);
        if (snapshot == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "一次性执行认领不存在: " + claimId);
        }
        if (snapshot.getServiceId() == null
                || baseServiceMapper.lockById(snapshot.getServiceId()) == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR,
                    "一次性执行认领关联的服务不存在，已拒绝放弃: " + claimId);
        }
        ReleaseExecutionRunClaim locked = claimMapper.selectByIdForUpdate(claimId);
        if (locked == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "一次性执行认领不存在: " + claimId);
        }
        if (!ReleaseExecutionRunClaim.STATUS_HELD.equals(locked.getStatus())) {
            throw new BizException(ErrorCode.VALIDATION_ERROR,
                    "一次性执行认领已经释放，不能重复放弃: " + claimId);
        }

        OffsetDateTime releasedAt = OffsetDateTime.now();
        if (claimMapper.markAbandonedIfHeld(claimId, truncate(normalizedNote, 512), userId, releasedAt) != 1) {
            throw new BizException(ErrorCode.INTERNAL_ERROR,
                    "一次性执行认领状态已变化，放弃未生效，请刷新后重试: " + claimId);
        }
        // 放弃认领同样不能把同单历史记录归属于这个执行者。

        locked.setStatus(ReleaseExecutionRunClaim.STATUS_RELEASED);
        locked.setReleaseOutcome(ReleaseExecutionRunClaim.OUTCOME_ABANDONED);
        locked.setReleaseReason(truncate(normalizedNote, 512));
        locked.setReleasedBy(userId);
        locked.setReleasedAt(releasedAt);
        releaseAuditLogService.recordExecutionClaimAbandoned(locked.getReleaseOrderId(), claimId, normalizedNote);
        return locked;
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    /** 只读：某发布单的认领行（最多一行部署认领 + 每个回滚单一行）。 */
    public List<ReleaseExecutionRunClaim> listByReleaseOrderId(Long releaseOrderId) {
        requireClaimTable("查询一次性执行认领");
        if (releaseOrderId == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "发布单 ID 不能为空");
        }
        return claimMapper.listByReleaseOrderId(releaseOrderId);
    }

    /**
     * 只读：该发布单的部署认领行，不存在返回 null。用于核对“这次回调所属的流水线运行是不是
     * 真正执行过部署的那一次”。迁移未应用时返回 null（此时不可能有任何认领行）。
     */
    public ReleaseExecutionRunClaim findDeployClaim(Long releaseOrderId) {
        if (releaseOrderId == null || !claimTableAvailable()) {
            return null;
        }
        return claimMapper.selectByIdentity(releaseOrderId, ReleaseExecutionRunClaim.PURPOSE_DEPLOY, null);
    }

    /**
     * 只读判定：该发布单是否还有一行**正在执行**（HELD）的部署认领。
     *
     * <p>人工恢复用它把“平台这次执行仍在飞”与“结果未知需要人工核对”分开：认领仍是 HELD 说明
     * 持有者没有正常收口，此时把部署记录改写成 UNKNOWN 会与事后真正的执行结果并存，
     * 因此恢复必须先由操作人显式放弃该认领，再登记未知事实。
     *
     * <p>迁移未应用时返回 false：没有这张表就不可能有认领行，不能因此挡住人工恢复这条唯一出口。
     *
     * <p>调用方必须已经持有 {@code base_service} 行锁并在真实事务中（与发布认领、未知登记同一把锁）。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean hasHeldDeployClaim(Long releaseOrderId) {
        if (releaseOrderId == null || !claimTableAvailable()) {
            return false;
        }
        ReleaseExecutionRunClaim claim = claimMapper.selectByIdentity(
                releaseOrderId, ReleaseExecutionRunClaim.PURPOSE_DEPLOY, null);
        return claim != null && ReleaseExecutionRunClaim.STATUS_HELD.equals(claim.getStatus());
    }

    /**
     * 只读：同一服务/环境下仍处于 HELD 的认领 ID（升序）。
     *
     * <p>HELD 是**持久化**的阻断事实，用来补上“未知事实登记失败”这一条窗口：一次执行只要没有被
     * 持有者正常释放、也没有被操作人显式放弃，同一服务/环境就不允许开始新的冲突动作，
     * 即使它所属的订单已经被流水线回调收敛为 FAILED 也一样。判定不依赖订单状态、也不依赖文案。
     *
     * <p>不限定认领用途：任何 HELD 认领都表示“平台开了一次执行而没有收口”，按失败关闭一律阻断。
     * 因此**新执行必须先完成阻断核对、再写下自己的认领**，否则会读到自己刚写下的那一行。
     *
     * <p>迁移未应用时返回空列表：没有这张表就不可能有认领行。
     */
    public List<Long> listHeldClaimIdsForServiceAndEnv(Long serviceId, Long envId) {
        if (serviceId == null || envId == null || !claimTableAvailable()) {
            return List.of();
        }
        return claimMapper.listHeldIdsByServiceAndEnv(serviceId, envId);
    }

    /**
     * 只读判定：一次性执行认领表是否可用（V32 是否已应用）。
     *
     * <p>没有这张表时不存在任何认领行，因此“认领缺失”与“V32 未应用”是两件事：
     * 前者可能是历史数据，后者说明“放弃认领”这个动作在平台上根本不存在。
     * 收口对账需要把两者分开陈述，不能让操作者去找一个不可能存在的入口。
     *
     * <p>采用与 {@link #findDeployClaim} 相同的负缓存：只在第一次查询时探测，
     * 迁移在运行期间应用时需要重启服务才能解除。
     */
    public boolean isClaimTableAvailable() {
        return claimTableAvailable();
    }

    /**
     * 与 {@code RuntimeExecutionUnknownService} 同构的负缓存：只在第一次查询时探测，
     * 迁移在运行期间应用时需要重启服务才能解除。
     */
    private boolean claimTableAvailable() {
        Boolean cached = claimTableAvailable;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (claimTableAvailable != null) {
                return claimTableAvailable;
            }
            claimTableAvailable = claimMapper.probe() != null;
            if (!claimTableAvailable) {
                log.warn("数据库表 release_execution_run_claim 不存在（V32 迁移未应用）；"
                        + "一次性执行认领不可用，流水线部署步骤会失败关闭");
            }
            return claimTableAvailable;
        }
    }

    private void requireClaimTable(String scene) {
        if (!claimTableAvailable()) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, scene + "：" + MESSAGE_TABLE_MISSING);
        }
    }

    private static void assertTransactionActive(String scene) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new BizException(ErrorCode.VALIDATION_ERROR,
                    scene + "未处于事务中，行锁与唯一索引不会按预期生效，已拒绝执行");
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
     * 释放认领可能发生在流水线工作线程里，没有登录上下文，此时如实留空
     * （{@code released_by} 可空且外键指向 {@code sys_user(id)}，不写默认用户顶替）。
     */
    private static Long currentUserIdOrNull() {
        UserContext context = UserContextHolder.get();
        return context == null ? null : context.getUserId();
    }

    private static String requireNote(String note) {
        if (!StringUtils.hasText(note)) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "放弃一次性执行认领的原因不能为空");
        }
        String trimmed = note.trim();
        if (trimmed.length() > 512) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "放弃一次性执行认领的原因不能超过 512 个字符");
        }
        return trimmed;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}

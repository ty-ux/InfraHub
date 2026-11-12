package com.infrahub.release.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.infrahub.base.mapper.BaseServiceMapper;
import com.infrahub.common.error.ErrorCode;
import com.infrahub.common.exception.BizException;
import com.infrahub.pipeline.entity.PipelineRun;
import com.infrahub.pipeline.entity.PipelineStep;
import com.infrahub.pipeline.entity.PipelineStepRun;
import com.infrahub.pipeline.mapper.PipelineRunMapper;
import com.infrahub.pipeline.mapper.PipelineStepMapper;
import com.infrahub.pipeline.mapper.PipelineStepRunMapper;
import com.infrahub.release.entity.ApprovalRequest;
import com.infrahub.release.entity.ReleaseOrder;
import com.infrahub.release.mapper.ApprovalRequestMapper;
import com.infrahub.release.mapper.ReleaseOrderMapper;
import com.infrahub.release.mapper.ReleaseRollbackOrderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 发布执行认领：把“直接发布 / 审批回调 / 流水线触发 / 执行审批创建”四个入口收敛到同一套认领规则。
 *
 * <p>协调机制只有数据库行锁与条件更新，不使用仅当前 JVM 有效的本地锁：
 * <ol>
 *   <li>锁顺序固定为 base_service(serviceId) → release_order(id)。服务行锁把“同一服务+环境”
 *       的认领串行化，同时不影响其他服务的发布；发布单行锁把同一发布单的并发执行串行化。</li>
 *   <li>所有判定都以“加锁后从数据库读回的当前整行订单”为准，不用加锁前的快照做决定（快照只用于
 *       确定锁对象，serviceId 是发布单的不可变归属字段）。加锁后的读取用独立的 FOR UPDATE 语句
 *       并显式绕过一级缓存，避免“再次 selectById 命中 SESSION 缓存、拿回旧状态”这一缺陷。</li>
 *   <li>认领成功靠条件更新 {@code status IN ('CREATED','APPROVED') → RUNNING}：并发请求中最多一个
 *       能拿到 1 行受影响，其余全部拒绝，因此外部执行器最多被调用一次。</li>
 *   <li>执行审批的批准（PENDING → APPROVED）与认领（订单 → RUNNING）在同一个短事务内完成：
 *       认领失败或审批竞争失败时两者一起回滚，审批不会被“消费掉却不执行”。</li>
 *   <li>锁与事务只覆盖认领检查和状态落库这一段短事务。调用外部执行器（kubectl / SSH / Docker）
 *       之前这些锁必须已经释放，等待期间不得持有数据库事务。</li>
 * </ol>
 *
 * <p>内部执行入口只接受本服务产出的 {@link ClaimedExecution}，不允许用“订单当前是 RUNNING”
 * 这种观察式判定启动执行：否则另一条路径的认领会被当成自己的认领。
 *
 * <p>本类不注入审批服务或发布服务，只依赖 Mapper 与既有查询/写入服务，因此不会形成循环依赖。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReleaseExecutionClaimService {

    /** 发布单/流水线状态字面量，与既有实现保持一致。 */
    private static final String STATUS_CREATED = "CREATED";
    private static final String STATUS_APPROVED = "APPROVED";
    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_FAILED = "FAILED";

    private static final String MESSAGE_NOT_EXECUTABLE = "当前发布单状态不允许执行";
    private static final String MESSAGE_RUNNING_CONFLICT = "当前服务在该环境下已有执行中的发布或回滚任务";
    private static final String MESSAGE_NOT_RUNNING = "发布单不是执行中状态，不能认领后续批次";
    private static final String MESSAGE_CANARY_PLAN_ACTIVE =
            "该发布单存在活动灰度计划，禁止直接整批发布；请通过灰度批次入口执行";
    private static final String RELEASE_TYPE_CANARY = "CANARY";
    /** 取得一次性执行认领只允许来自真实部署步骤，与 {@code ReleaseDeployPipelineStepExecutor} 的注册类型一致。 */
    private static final String DEPLOY_EXECUTOR_TYPE = "RELEASE_DEPLOY";
    private static final String MESSAGE_DUPLICATE_APPROVAL = "该发布单已有待处理执行审批，不能重复申请执行审批";
    private static final String MESSAGE_APPROVAL_ALREADY_PROCESSED =
            "审批单状态不是PENDING，已被其他请求处理";

    /**
     * 成功认领的结果：只能由本服务的 {@link #claimForExecution} 或
     * {@link #claimForApprovedExecution} 产出。
     *
     * <p>{@code order} 是加锁后读到的整行订单，状态已被本次条件更新写成 RUNNING。
     * 内部执行入口只接受这一类型，从接口上排除“仅凭订单当前是 RUNNING 就执行”的做法：
     * 那种判定无法区分“本次认领”与“别人已经认领过”。
     */
    public record ClaimedExecution(ReleaseOrder order) {
    }

    private final BaseServiceMapper baseServiceMapper;
    private final ReleaseOrderMapper releaseOrderMapper;
    private final ReleaseRollbackOrderMapper releaseRollbackOrderMapper;
    /**
     * 直接用 Mapper 读审批表，不经 ApprovalRequestService：审批服务本身要复用本服务做创建前协调，
     * 走 Mapper 可避免两个服务互相构造注入形成循环依赖（与 RollbackTargetCoordinator 同一做法）。
     */
    private final ApprovalRequestMapper approvalRequestMapper;
    private final RuntimeDeployRecordService runtimeDeployRecordService;
    private final RuntimeServiceInstanceService runtimeServiceInstanceService;
    private final ReleaseCurrentVersionService releaseCurrentVersionService;
    private final RuntimeExecutionUnknownService runtimeExecutionUnknownService;
    private final PipelineRunMapper pipelineRunMapper;
    /**
     * 步骤尝试与步骤定义：取得流水线部署认领之前，必须按库里的真实关系核对
     * “这次尝试确实属于传入的运行与当前部署步骤”，不能只信调用方传入的 ID。
     */
    private final PipelineStepRunMapper pipelineStepRunMapper;
    private final PipelineStepMapper pipelineStepMapper;
    /**
     * 一次性执行认领（{@code release_execution_run_claim}）。流水线部署步骤的凭证写在这里，
     * 使“核对通过”与“取得执行权”落在同一把锁下的同一个短事务里。
     */
    private final ReleaseExecutionRunClaimService releaseExecutionRunClaimService;

    /**
     * 执行审批创建前的重复申请防线，必须在审批创建的同一短事务内调用。
     *
     * <p>三个创建入口（{@code createRequest} / {@code createRequest(bizType,...)} /
     * {@code createRequestUnchecked}）都要经过这里，否则可以绕过旧发布单状态直接建出第二张待处理审批。
     * 检查与写入同事务，因此“检查到没有待处理审批”到“插入待处理审批”之间不会有其他事务穿插。
     *
     * <p>发布单已在执行中（RUNNING）或已终结（SUCCESS/FAILED/CANCELLED 等）时同样拒绝：
     * 前者的 RUNNING 状态由执行认领提交，正是“外部连接也能看到运行状态”的那份事实。
     *
     * @throws BizException 无活动事务、发布单不存在、状态不可执行或已有待处理执行审批
     */
    public void assertApprovalCreationAllowed(Long releaseOrderId) {
        assertTransactionActive("发布执行审批创建协调");
        ReleaseOrder order = lockReleaseTarget(releaseOrderId);
        ensureExecutableStatus(order.getStatus());
        runtimeExecutionUnknownService.assertNoUnresolvedUnknown(order.getServiceId(), order.getEnvId());
        assertNoActiveCanaryPlan(order);
        if (countPendingExecuteApprovals(releaseOrderId) > 0) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, MESSAGE_DUPLICATE_APPROVAL);
        }
    }

    /**
     * 统一执行认领（短事务）：服务行锁 → 发布单行锁并读回当前整行 → 服务/环境互斥检查 → 条件更新 RUNNING。
     *
     * <p>调用方必须处于真实事务中（本方法自带 {@code @Transactional}，但同类内部自调用不会经过
     * Spring 代理、注解会失效，因此这里再补一层事务守卫）。事务在方法返回时提交，
     * 认领结果（RUNNING）随即对外部连接与查询 API 可见，锁同时释放，之后才允许调用外部执行器。
     *
     * @return 本次成功认领的结果，供调用方做后续执行使用
     * @throws BizException 无活动事务、发布单不存在、状态不可执行或服务/环境已有执行中任务
     */
    @Transactional
    public ClaimedExecution claimForExecution(Long releaseOrderId) {
        assertTransactionActive("发布执行认领");
        ReleaseOrder order = claimLockedExecutableOrder(releaseOrderId);
        // 金丝雀订单已有活动批次计划时必须走 canary/start，不能整单直接发布。
        // 这里在认领同一事务内、条件更新之后判定：抛出即整体回滚，订单不会停在 RUNNING。
        assertNoActiveCanaryPlan(order);
        return new ClaimedExecution(order);
    }

    /**
     * 灰度计划启动/恢复专用的订单层认领：与 {@link #claimForExecution} 的锁与条件更新完全相同，
     * 但**不**执行“存在活动灰度计划就拒绝”的检查——那张计划正是本次要启动的计划。
     *
     * <p>批次凭证仍是调用方随后的 {@code release_canary_batch.PENDING → RUNNING} 条件更新。
     */
    @Transactional
    public ClaimedExecution claimForCanaryExecution(Long releaseOrderId) {
        assertTransactionActive("灰度计划发布认领");
        return new ClaimedExecution(claimLockedExecutableOrder(releaseOrderId));
    }

    /**
     * 执行审批的批准 + 认领（短事务）：审批 PENDING → APPROVED 与发布单 → RUNNING 在同一事务内完成。
     *
     * <p>先把审批与订单在同一把锁下重新确认，再落两处状态：
     * <ol>
     *   <li>锁 base_service(serviceId) → release_order(id)，用加锁后读回的整行订单判定
     *       “订单状态可执行”与“服务/环境无执行中任务”；</li>
     *   <li>条件更新订单 {@code status IN ('CREATED','APPROVED') → RUNNING}；</li>
     *   <li>锁后重新检查审批：条件更新 {@code PENDING → APPROVED}，受影响行数为 0 说明审批
     *       已被并发批准、拒绝或取消，直接拒绝。</li>
     * </ol>
     *
     * <p>任一步失败都抛出异常，由调用方事务回滚：审批退回 PENDING、订单状态不变，
     * 因此不会出现“审批被消费却没有执行”的结果。事务提交后调用方才允许调用外部执行器。
     *
     * @param approvalRequestId 本次要消费的审批单 ID
     * @param releaseOrderId    审批指向的发布单 ID
     * @return 本次成功认领的结果
     * @throws BizException 无活动事务、发布单不存在、状态不可执行、服务/环境已有执行中任务或审批已被处理
     */
    @Transactional
    public ClaimedExecution claimForApprovedExecution(Long approvalRequestId, Long releaseOrderId,
                                                     Long approverId, String approveNote) {
        assertTransactionActive("发布执行审批批准与执行认领");
        ReleaseOrder order = claimLockedExecutableOrder(releaseOrderId);
        assertNoActiveCanaryPlan(order);

        if (approvalRequestMapper.markApprovedIfPending(approvalRequestId, approverId, approveNote,
                OffsetDateTime.now()) != 1) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, MESSAGE_APPROVAL_ALREADY_PROCESSED);
        }
        return new ClaimedExecution(order);
    }

    /**
     * 加锁并读回发布单当前行，供同一服务/环境下的其他协调入口使用（金丝雀暂停、批次终态、
     * 自动回滚单创建）。本方法只取锁、不改变订单状态，调用方必须自己判定状态并做条件更新。
     *
     * <p>锁顺序与 {@link #claimForExecution} 完全一致：{@code base_service → release_order}。
     * 调用方必须已经处于真实事务中，否则行锁会随自动提交立即释放。
     */
    @Transactional
    public ReleaseOrder lockReleaseOrderForCoordination(Long releaseOrderId) {
        assertTransactionActive("发布协调");
        return lockReleaseTarget(releaseOrderId);
    }

    /**
     * 金丝雀后续批次的订单锁：要求订单已经处于 RUNNING，并且同一服务/环境下没有**别的**
     * 执行中发布单或回滚任务。
     *
     * <p>本方法只负责把订单和互斥条件锁住，**不是**批次执行的认领凭证：订单保持 RUNNING
     * 不能证明这次请求拥有批次。真正的凭证是调用方随后对
     * {@code release_canary_batch} 执行的 PENDING → RUNNING 条件更新。
     *
     * <p>排除自身是因为金丝雀订单在整个计划期间保持 RUNNING；若沿用
     * {@link #ensureNoRunningOperation} 会把当前订单本身误判成冲突。
     */
    @Transactional
    public ReleaseOrder lockRunningOrderForBatch(Long releaseOrderId) {
        assertTransactionActive("金丝雀批次认领");
        ReleaseOrder order = lockReleaseTarget(releaseOrderId);
        if (!STATUS_RUNNING.equalsIgnoreCase(order.getStatus())) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, MESSAGE_NOT_RUNNING);
        }
        runtimeExecutionUnknownService.assertNoUnresolvedUnknown(order.getServiceId(), order.getEnvId());
        ensureNoOtherRunningOperation(order.getServiceId(), order.getEnvId(), order.getId());
        return order;
    }

    /**
     * 认领的公共步骤：加锁 → 用锁后事实判定可执行性 → 条件更新 RUNNING。
     *
     * <p>判定对象一律是 {@link #lockReleaseTarget} 返回的锁后整行订单，绝不使用加锁前的快照。
     */
    private ReleaseOrder claimLockedExecutableOrder(Long releaseOrderId) {
        ReleaseOrder order = lockReleaseTarget(releaseOrderId);
        ensureExecutableStatus(order.getStatus());
        // 同一服务/环境存在未解除的结果未知执行时，任何新发布/回滚都不能开始。
        // 这里持有服务行锁，与“未知登记写入”的事务互斥，检查与后续 RUNNING 条件更新同事务。
        runtimeExecutionUnknownService.assertNoUnresolvedUnknown(order.getServiceId(), order.getEnvId());
        ensureNoRunningOperation(order.getServiceId(), order.getEnvId());

        if (releaseOrderMapper.markRunningIfExecutable(releaseOrderId) != 1) {
            // 行锁已保证串行化，这里再以受影响行数兜底：拿不到 1 行说明状态已被改掉，拒绝执行
            throw new BizException(ErrorCode.VALIDATION_ERROR, MESSAGE_NOT_EXECUTABLE);
        }
        order.setStatus(STATUS_RUNNING);
        return order;
    }

    /**
     * 成功终态（短事务）：记录 SUCCESS，并按既有规则更新实例版本与当前版本。
     *
     * <p>使用条件更新而不是无条件覆盖：如果订单已被人工恢复为 FAILED（结果未知），
     * 迟到的“成功”回调不能把未知事实抹成成功。
     */
    @Transactional
    public void markSucceeded(Long releaseOrderId, Long envId, Long serviceId,
                              List<Long> successInstanceIds, Long versionId) {
        assertTransactionActive("发布成功终态记录");
        if (releaseOrderMapper.markSuccessIfRunning(releaseOrderId) != 1) {
            throw new BizException(ErrorCode.INTERNAL_ERROR,
                    "发布单已不是 RUNNING（可能已被人工恢复为 FAILED），拒绝覆盖成功终态: " + releaseOrderId);
        }
        if (successInstanceIds != null && !successInstanceIds.isEmpty()) {
            runtimeServiceInstanceService.updateVersion(successInstanceIds, versionId);
        }
        releaseCurrentVersionService.markReleaseVersion(envId, serviceId, versionId, releaseOrderId);
    }

    /**
     * 失败终态（短事务）：记录 FAILED，并把该发布单下仍停在 RUNNING 的部署记录收敛成带日志的失败证据。
     *
     * <p>本方法只做终态落库，不做重试、不做自动回滚、不修改任何成功版本。
     */
    @Transactional
    public void markFailed(Long releaseOrderId, String failureReason) {
        assertTransactionActive("发布失败终态记录");
        ReleaseOrder order = lockReleaseTarget(releaseOrderId);
        if (!STATUS_RUNNING.equalsIgnoreCase(order.getStatus())
                && !STATUS_FAILED.equalsIgnoreCase(order.getStatus())) {
            // 已成功/已取消的订单不能再被失败终态覆盖；仍为 RUNNING 的记录继续挡住后续操作。
            log.warn("发布单 {} 当前状态为 {}，拒绝写入失败终态", releaseOrderId, order.getStatus());
            return;
        }
        releaseOrderMapper.markFailedIfRunning(releaseOrderId);
        int patched = runtimeDeployRecordService.failRunningRecordsByReleaseOrderId(releaseOrderId, failureReason);
        if (patched > 0) {
            log.warn("发布单 {} 失败终态兜底：{} 条仍处于 RUNNING 的部署记录已补齐失败证据", releaseOrderId, patched);
        }
    }

    /**
     * 结果未知的失败终态（短事务）：发布单收敛为 FAILED（订单状态只有粗粒度终态），
     * 但该单下仍停在 RUNNING 的部署记录写入 **UNKNOWN** 而不是 FAILED。
     *
     * <p>“发布单 FAILED”只表示这次发布没有成功收口，不代表远端动作已确定失败或已结束；
     * 操作者必须以部署记录中的 UNKNOWN 与人工核对为准。
     *
     * <p>先取服务行锁再写终态：与阻断核对、人工恢复共用同一把锁，避免“订单已经 FAILED、
     * 未知登记尚未提交”的中间态被新操作穿过。UNKNOWN 记录与登记行在同一事务内提交。
     */
    @Transactional
    public void markFailedUnknown(Long releaseOrderId, String failureReason) {
        assertTransactionActive("发布未知结果终态记录");
        ReleaseOrder order = lockReleaseTarget(releaseOrderId);
        if (!STATUS_RUNNING.equalsIgnoreCase(order.getStatus())
                && !STATUS_FAILED.equalsIgnoreCase(order.getStatus())) {
            log.warn("发布单 {} 当前状态为 {}，拒绝写入未知终态", releaseOrderId, order.getStatus());
            return;
        }
        releaseOrderMapper.markFailedIfRunning(releaseOrderId);
        int patched = runtimeDeployRecordService.markRunningRecordsUnknownByReleaseOrderId(
                releaseOrderId, failureReason);
        runtimeExecutionUnknownService.registerOrderLevelIfNoUnknownRecords(
                releaseOrderId, null, null, null, "TERMINAL_CLEANUP",
                "发布结果需要人工核对，不能证明远端状态安全");
        if (patched > 0) {
            log.warn("发布单 {} 未知结果兜底：{} 条 RUNNING 部署记录已标记为 UNKNOWN", releaseOrderId, patched);
        }
    }

    /**
     * 加锁并读回发布单当前事实。
     *
     * <p>第一次读取只用来确定锁对象：serviceId 是发布单创建时写入、之后不再变更的归属字段，
     * 这次读到的状态、版本等一律不参与判定。加锁顺序固定为 base_service → release_order。
     *
     * <p>拿到服务行锁之后，用 {@code SELECT ... FOR UPDATE} 一次取回完整订单行，判定全部使用
     * 这个对象。这里不能改成“再加锁后再来一次 {@code selectById}”：同一事务内的 MyBatis
     * 一级缓存是 SESSION 级、以 Statement + 参数为键，快照那次 {@code selectById} 已经把旧对象
     * 放进了缓存，再加锁后 {@code selectById} 会直接命中缓存返回旧对象，于是别的事务已经提交的
     * RUNNING / SUCCESS / FAILED 会被当成可执行状态，行锁拿到了也等于没读。
     *
     * <p>{@link ReleaseOrderMapper#selectForUpdate} 用独立语句并显式 {@code flushCache = true} +
     * {@code useCache = false}：先清掉快照读留在缓存里的旧条目，再保证锁定读本身永远落到数据库。
     * 全局关闭一级缓存影响面更大，不采用。
     */
    private ReleaseOrder lockReleaseTarget(Long releaseOrderId) {
        ReleaseOrder snapshot = releaseOrderMapper.selectById(releaseOrderId);
        if (snapshot == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "发布单不存在");
        }
        if (snapshot.getServiceId() == null || baseServiceMapper.lockById(snapshot.getServiceId()) == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "发布单关联的服务不存在");
        }

        ReleaseOrder locked = releaseOrderMapper.selectForUpdate(releaseOrderId);
        if (locked == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "发布单不存在");
        }
        return locked;
    }

    private void ensureExecutableStatus(String status) {
        if (!STATUS_CREATED.equalsIgnoreCase(status) && !STATUS_APPROVED.equalsIgnoreCase(status)) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, MESSAGE_NOT_EXECUTABLE);
        }
    }

    /**
     * 流水线部署步骤的一次性执行认领（短事务）：先按**持久化关系**确认本次步骤尝试的归属，
     * 再在既有的部署前置核对之上写下一行 HELD 认领。
     *
     * <p><b>为什么不接受调用方传入的 ID 组合</b>：执行步骤、流水线运行与发布单的关系是可以直接
     * 从库里读出来的事实。只把调用方给的三个 ID 拼起来（运行属于订单、步骤尝试属于运行）等于
     * 相信调用方自述的归属；一旦调用方的参数被拼错或被构造，认领就会落到另一个发布单上，
     * 而认领行正是后续“谁执行过这次部署”的唯一答案。因此这里一律以库里读回的行为准：
     * <ol>
     *   <li>{@code pipeline_step_run(id)} 必须存在，且它的 {@code pipeline_run_id} 等于传入的运行、
     *       {@code step_id} 等于当前部署步骤、{@code run_status} 仍是 RUNNING（已 SUCCESS / FAILED / SKIPPED
     *       的尝试不允许再执行）；</li>
     *   <li>{@code pipeline_step(id)} 必须存在且 {@code executor_type} 是 RELEASE_DEPLOY；</li>
     *   <li>{@code pipeline_run(id)} 必须存在，发布单身份取它的 {@code release_id}（不用调用方给的订单）；</li>
     *   <li>发布单仍是 RUNNING、是唯一执行中运行、没有活动灰度计划、同一服务/环境没有未解除未知事实。</li>
     * </ol>
     * 全部通过后，在同一个短事务里写一行 {@code release_execution_run_claim}，由唯一索引保证
     * 一个发布单只能有一行部署认领。
     *
     * <p><b>锁序</b>：{@code base_service → release_order → release_execution_run_claim}，
     * 与发布认领、未知登记、人工解除一致；上面的第 1~3 步都是无锁快照读，不引入新的取锁顺序。
     *
     * <p><b>事务边界</b>：本方法本身就是那个短事务，**不含任何远端调用**。方法返回即提交，
     * 锁释放而认领行保持 HELD；调用方必须在远端调用结束后、无论成败都释放它。
     *
     * <p><b>尚存竞态</b>：上面的核对与写认领在同一把锁下完成，但“步骤尝试的记录存在且为 RUNNING”
     * 与“真正调用远端”之间仍然隔着一次调用方代码跳转；与认领释放之间的窗口由
     * 步骤执行器的 try/finally 闭合，不在本方法的职责内。
     *
     * @return 本次成功认领的凭证，调用方必须持有并在执行结束后释放
     * @throws BizException 无活动事务、步骤尝试归属不成立或不在可执行状态、发布单不在执行中、
     *                      不是唯一执行中运行、存在活动灰度计划、存在未解除未知事实，
     *                      或该执行身份已被占用/已消费（含 V32 未应用）
     */
    @Transactional
    public ReleaseExecutionRunClaimService.ExecutionClaimHandle claimDirectExecution(PipelineRun run,
                                                                                      PipelineStep step,
                                                                                      PipelineStepRun stepRun) {
        if (run == null || run.getId() == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR,
                    "流水线部署缺少流水线运行标识，无法确认执行的独占性，已拒绝");
        }
        if (step == null || step.getId() == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "流水线部署缺少当前步骤标识，已拒绝");
        }
        if (stepRun == null || stepRun.getId() == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "流水线部署缺少步骤尝试标识，已拒绝");
        }
        assertTransactionActive("流水线部署认领");

        PipelineStepRun persistedStepRun = requireExecutableStepRun(run, step, stepRun);
        requireDeployStep(step);

        PipelineRun persistedRun = pipelineRunMapper.selectById(run.getId());
        if (persistedRun == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "流水线运行不存在: " + run.getId());
        }
        if (persistedRun.getReleaseId() == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR,
                    "流水线运行未关联发布单，无法确认执行归属，已拒绝: " + persistedRun.getId());
        }

        ReleaseOrder locked = lockReleaseTarget(persistedRun.getReleaseId());
        if (!STATUS_RUNNING.equalsIgnoreCase(locked.getStatus())) {
            throw new BizException(ErrorCode.VALIDATION_ERROR,
                    "发布单 " + locked.getId() + " 已不在执行中，拒绝流水线部署");
        }
        assertExclusivePipelineRun(locked.getId(), persistedRun);
        assertNoActiveCanaryPlan(locked);
        runtimeExecutionUnknownService.assertNoUnresolvedUnknown(locked.getServiceId(), locked.getEnvId());
        return releaseExecutionRunClaimService.acquireDeployClaim(locked, persistedRun.getId(),
                persistedStepRun.getId());
    }

    /**
     * 核对本次步骤尝试确实属于传入的流水线运行与当前部署步骤，并且仍处于允许执行的状态。
     *
     * <p>全部判定使用 {@code pipeline_step_run} 中读回的那一行：调用方传入的对象只用于提供 ID。
     *
     * <p>只允许 RUNNING：已成功/已失败/已跳过（前置步骤失败）的尝试都不是“本次要执行的部署”，
     * 放行它们会让一次已经收口的尝试再执行一次远端动作。
     *
     * @return 库里读回的步骤尝试行（它的 ID 才是写进认领的那个）
     */
    private PipelineStepRun requireExecutableStepRun(PipelineRun run, PipelineStep step, PipelineStepRun stepRun) {
        PipelineStepRun persisted = pipelineStepRunMapper.selectById(stepRun.getId());
        if (persisted == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR,
                    "流水线步骤尝试不存在，无法确认本次执行的归属，已拒绝: " + stepRun.getId());
        }
        if (!run.getId().equals(persisted.getPipelineRunId())) {
            throw new BizException(ErrorCode.VALIDATION_ERROR,
                    "流水线步骤尝试 " + persisted.getId() + " 不属于运行 " + run.getId()
                            + "（实际运行 " + persisted.getPipelineRunId() + "），已拒绝");
        }
        if (!step.getId().equals(persisted.getStepId())) {
            throw new BizException(ErrorCode.VALIDATION_ERROR,
                    "流水线步骤尝试 " + persisted.getId() + " 不属于当前部署步骤 " + step.getId()
                            + "（实际步骤 " + persisted.getStepId() + "），已拒绝");
        }
        if (!STATUS_RUNNING.equalsIgnoreCase(persisted.getRunStatus())) {
            throw new BizException(ErrorCode.VALIDATION_ERROR,
                    "流水线步骤尝试 " + persisted.getId() + " 当前状态为 " + persisted.getRunStatus()
                            + "，不是可执行的步骤尝试，已拒绝部署");
        }
        return persisted;
    }

    /** 当前步骤必须是真实部署步骤：认领只能发给 RELEASE_DEPLOY，不能由其它类型的步骤套取。 */
    private void requireDeployStep(PipelineStep step) {
        PipelineStep persisted = pipelineStepMapper.selectById(step.getId());
        if (persisted == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "流水线步骤不存在: " + step.getId());
        }
        String executorType = persisted.getExecutorType();
        if (executorType == null || !DEPLOY_EXECUTOR_TYPE.equalsIgnoreCase(executorType.trim())) {
            throw new BizException(ErrorCode.VALIDATION_ERROR,
                    "流水线步骤 " + persisted.getId() + " 的执行器类型为 " + executorType
                            + "，不是真实部署步骤，不能取得一次性执行认领");
        }
    }

    /**
     * 流水线运行的独占性核对。
     *
     * <p>流水线自身的 {@code run_status=RUNNING} 只是展示状态，不能当作执行凭证：进程崩溃后它可能
     * 长时间残留，同一发布单也可能出现两个 RUNNING 运行（重复触发、重试）。这里要求“调用方这一次
     * 运行确实是该发布单唯一的执行中运行”，否则拒绝部署步骤，避免同一发布单被两条流水线同时部署。
     *
     * <p>残留的运行仍然有效：只要它还是 RUNNING，就会一直挡住新的流水线部署，需要由运行管理入口
     * 收口而不是被这里静默忽略。
     *
     * <p>这条判定本身仍然只是核对，它的结果不构成执行凭证：真正的一次性凭证是同一事务内写下的
     * {@code release_execution_run_claim} 行（见 {@link #claimDirectExecution}）。两者互补 ——
     * 这里排除“同一发布单出现两个执行中运行”的既有脏数据，认领表排除“核对之后才启动”的新竞争者。
     *
     * @param run 已经从 {@code pipeline_run} 读回的行，其对发布单的归属由调用方按同一行确定
     */
    private void assertExclusivePipelineRun(Long releaseOrderId, PipelineRun run) {
        if (!STATUS_RUNNING.equalsIgnoreCase(run.getRunStatus())) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "流水线运行已不在执行中，拒绝流水线部署");
        }
        long runningRuns = pipelineRunMapper.countRunningByReleaseId(releaseOrderId);
        if (runningRuns != 1) {
            throw new BizException(ErrorCode.VALIDATION_ERROR,
                    "该发布单存在 " + runningRuns + " 个执行中的流水线运行，无法确认本次执行独占，拒绝流水线部署");
        }
    }

    /**
     * CANARY 订单存在活动灰度计划（CREATED/PAUSED/RUNNING/BLOCKED）时拒绝整单执行。
     *
     * <p>直接发布、执行审批、流水线触发与流水线部署步骤共用这一判定；灰度计划自身的启动走
     * {@link #claimForCanaryExecution}，不会撞上这条规则。非 CANARY 订单完全不受影响。
     */
    private void assertNoActiveCanaryPlan(ReleaseOrder order) {
        if (order == null || order.getId() == null) {
            return;
        }
        if (!RELEASE_TYPE_CANARY.equalsIgnoreCase(order.getReleaseType())) {
            return;
        }
        if (releaseOrderMapper.countActiveCanaryPlanByReleaseOrderId(order.getId()) > 0) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, MESSAGE_CANARY_PLAN_ACTIVE);
        }
    }

    /** 保留既有发布/回滚互斥检查：同一服务在同一环境下不允许出现两个执行中的任务。 */
    private void ensureNoRunningOperation(Long serviceId, Long envId) {
        long runningReleaseCount = releaseOrderMapper.countRunningByServiceAndEnv(serviceId, envId);
        if (runningReleaseCount > 0 || releaseRollbackOrderMapper.countRunningByServiceAndEnv(serviceId, envId) > 0) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, MESSAGE_RUNNING_CONFLICT);
        }
    }

    /**
     * 金丝雀恢复场景的互斥检查：当前订单本身是 RUNNING，必须把它排除在外；
     * 同一服务/环境下另一张 RUNNING 发布单或任一 RUNNING 回滚单仍然算冲突。
     */
    private void ensureNoOtherRunningOperation(Long serviceId, Long envId, Long excludedOrderId) {
        long otherRunningReleaseCount =
                releaseOrderMapper.countRunningByServiceAndEnvExcludingId(serviceId, envId, excludedOrderId);
        if (otherRunningReleaseCount > 0
                || releaseRollbackOrderMapper.countRunningByServiceAndEnv(serviceId, envId) > 0) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, MESSAGE_RUNNING_CONFLICT);
        }
    }

    private long countPendingExecuteApprovals(Long releaseOrderId) {
        Long pendingCount = approvalRequestMapper.selectCount(
                new LambdaQueryWrapper<ApprovalRequest>()
                        .eq(ApprovalRequest::getBizType, ApprovalRequest.BIZ_TYPE_RELEASE_ORDER)
                        .eq(ApprovalRequest::getBizId, releaseOrderId)
                        .eq(ApprovalRequest::getAction, ApprovalRequest.ACTION_EXECUTE_RELEASE)
                        .eq(ApprovalRequest::getStatus, ApprovalRequest.STATUS_PENDING));
        return pendingCount == null ? 0L : pendingCount;
    }

    /** 无活动事务时拒绝，避免行锁在自动提交语句中立即释放。 */
    private void assertTransactionActive(String scene) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new BizException(ErrorCode.VALIDATION_ERROR,
                    scene + "未处于事务中，发布单行锁不会生效，已拒绝执行");
        }
    }
}

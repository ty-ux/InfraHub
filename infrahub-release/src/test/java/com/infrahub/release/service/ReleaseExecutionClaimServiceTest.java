package com.infrahub.release.service;

import com.infrahub.base.mapper.BaseServiceMapper;
import com.infrahub.common.exception.BizException;
import com.infrahub.release.entity.ReleaseOrder;
import com.infrahub.release.mapper.ApprovalRequestMapper;
import com.infrahub.release.mapper.ReleaseOrderMapper;
import com.infrahub.release.mapper.ReleaseRollbackOrderMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 发布执行认领纯单元测试：只验证 fail-closed、锁顺序、判据与“最多一个胜者”，
 * 不连接真实 DB / HTTP / SSH / Docker / Kubernetes，不启动 Spring 容器。
 *
 * <p>事务是否存在用 {@link TransactionSynchronizationManager} 的线程状态模拟（与
 * RollbackTargetCoordinatorTest 同一做法）；数据库行锁与条件更新用可控制同步原语模拟，
 * 并发用例用 {@link CyclicBarrier} 强制两个线程同时进入，不使用 sleep 猜测阶段。
 *
 * <p><b>本测试不能证明真实数据库行为</b>：模拟只覆盖本类自己的判定与调用顺序，
 * 真实 MyBatis 一级缓存、行锁阻塞、事务提交可见性必须由真实数据库验证（Codex 负责）。
 */
class ReleaseExecutionClaimServiceTest {

    private static final Long ORDER_ID = 10L;
    private static final Long SERVICE_ID = 3L;
    private static final Long ENV_ID = 2L;
    private static final Long APPROVAL_ID = 55L;
    private static final Long APPROVER_ID = 9L;

    private BaseServiceMapper baseServiceMapper;
    private ReleaseOrderMapper releaseOrderMapper;
    private ReleaseRollbackOrderMapper releaseRollbackOrderMapper;
    private ApprovalRequestMapper approvalRequestMapper;
    private RuntimeDeployRecordService runtimeDeployRecordService;
    private RuntimeServiceInstanceService runtimeServiceInstanceService;
    private ReleaseCurrentVersionService releaseCurrentVersionService;

    private ReleaseExecutionClaimService claimService;

    @BeforeEach
    void setUp() {
        baseServiceMapper = mock(BaseServiceMapper.class);
        releaseOrderMapper = mock(ReleaseOrderMapper.class);
        releaseRollbackOrderMapper = mock(ReleaseRollbackOrderMapper.class);
        approvalRequestMapper = mock(ApprovalRequestMapper.class);
        runtimeDeployRecordService = mock(RuntimeDeployRecordService.class);
        runtimeServiceInstanceService = mock(RuntimeServiceInstanceService.class);
        releaseCurrentVersionService = mock(ReleaseCurrentVersionService.class);

        claimService = new ReleaseExecutionClaimService(
                baseServiceMapper, releaseOrderMapper, releaseRollbackOrderMapper,
                approvalRequestMapper, runtimeDeployRecordService,
                runtimeServiceInstanceService, releaseCurrentVersionService,
                    org.mockito.Mockito.mock(RuntimeExecutionUnknownService.class), org.mockito.Mockito.mock(com.infrahub.pipeline.mapper.PipelineRunMapper.class), org.mockito.Mockito.mock(com.infrahub.pipeline.mapper.PipelineStepRunMapper.class), org.mockito.Mockito.mock(com.infrahub.pipeline.mapper.PipelineStepMapper.class), org.mockito.Mockito.mock(ReleaseExecutionRunClaimService.class));

        when(baseServiceMapper.lockById(SERVICE_ID)).thenReturn(SERVICE_ID);
        // 快照读只用于确定锁对象；锁后事实由 selectForUpdate 提供
        when(releaseOrderMapper.selectById(ORDER_ID)).thenReturn(order("CREATED"));
        when(releaseOrderMapper.selectForUpdate(ORDER_ID)).thenReturn(order("CREATED"));
        when(releaseOrderMapper.markRunningIfExecutable(ORDER_ID)).thenReturn(1);
        when(approvalRequestMapper.selectCount(any())).thenReturn(0L);
        when(approvalRequestMapper.markApprovedIfPending(eq(APPROVAL_ID), any(), any(), any())).thenReturn(1);
    }

    @AfterEach
    void clearTransactionState() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    private void markTransactionActive() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }

    private ReleaseOrder order(String status) {
        ReleaseOrder order = new ReleaseOrder();
        order.setId(ORDER_ID);
        order.setServiceId(SERVICE_ID);
        order.setEnvId(ENV_ID);
        order.setStatus(status);
        return order;
    }

    // ---------- 事务守卫：无活动事务时行锁立即释放，必须直接拒绝 ----------

    @Test
    @DisplayName("事务守卫：无活动事务时认领被拒，且不触碰任何数据库操作")
    void claimRejectedWithoutActiveTransaction() {
        BizException ex = assertThrows(BizException.class, () -> claimService.claimForExecution(ORDER_ID));

        assertTrue(ex.getMessage().contains("未处于事务中"), "必须说明锁不会生效");
        verify(releaseOrderMapper, never()).selectById(any());
        verify(releaseOrderMapper, never()).selectForUpdate(any());
        verify(baseServiceMapper, never()).lockById(any());
        verify(releaseOrderMapper, never()).markRunningIfExecutable(any());
    }

    @Test
    @DisplayName("事务守卫：无活动事务时批准并认领被拒，审批与订单都不被触碰")
    void approveAndClaimRejectedWithoutActiveTransaction() {
        BizException ex = assertThrows(BizException.class,
                () -> claimService.claimForApprovedExecution(APPROVAL_ID, ORDER_ID, APPROVER_ID, "同意"));

        assertTrue(ex.getMessage().contains("未处于事务中"));
        verify(releaseOrderMapper, never()).selectForUpdate(any());
        verify(releaseOrderMapper, never()).markRunningIfExecutable(any());
        verify(approvalRequestMapper, never()).markApprovedIfPending(any(), any(), any(), any());
    }

    @Test
    @DisplayName("事务守卫：无活动事务时审批创建前协调被拒")
    void approvalGuardRejectedWithoutActiveTransaction() {
        BizException ex = assertThrows(BizException.class,
                () -> claimService.assertApprovalCreationAllowed(ORDER_ID));

        assertTrue(ex.getMessage().contains("未处于事务中"));
        verify(approvalRequestMapper, never()).selectCount(any());
    }

    // ---------- 审批创建前协调：重复申请必须被受控拒绝 ----------

    @Test
    @DisplayName("审批创建前协调：发布单已是 RUNNING 时拒绝，且不查询待处理审批")
    void approvalGuardRejectsRunningOrder() {
        markTransactionActive();
        when(releaseOrderMapper.selectForUpdate(ORDER_ID)).thenReturn(order("RUNNING"));

        BizException ex = assertThrows(BizException.class,
                () -> claimService.assertApprovalCreationAllowed(ORDER_ID));

        assertEquals("当前发布单状态不允许执行", ex.getMessage());
        verify(approvalRequestMapper, never()).selectCount(any());
    }

    @Test
    @DisplayName("审批创建前协调：发布单已终结（SUCCESS/FAILED）时拒绝")
    void approvalGuardRejectsFinishedOrder() {
        markTransactionActive();
        when(releaseOrderMapper.selectForUpdate(ORDER_ID)).thenReturn(order("FAILED"));

        BizException ex = assertThrows(BizException.class,
                () -> claimService.assertApprovalCreationAllowed(ORDER_ID));

        assertEquals("当前发布单状态不允许执行", ex.getMessage());
    }

    @Test
    @DisplayName("审批创建前协调：已有待处理执行审批时拒绝，消息不漂移")
    void approvalGuardRejectsDuplicatePendingApproval() {
        markTransactionActive();
        when(approvalRequestMapper.selectCount(any())).thenReturn(1L);

        BizException ex = assertThrows(BizException.class,
                () -> claimService.assertApprovalCreationAllowed(ORDER_ID));

        assertEquals("该发布单已有待处理执行审批，不能重复申请执行审批", ex.getMessage());
    }

    @Test
    @DisplayName("审批创建前协调：CREATED 且无待处理审批时放行")
    void approvalGuardPassesForExecutableOrderWithoutPendingApproval() {
        markTransactionActive();

        assertDoesNotThrow(() -> claimService.assertApprovalCreationAllowed(ORDER_ID));

        verify(baseServiceMapper).lockById(SERVICE_ID);
        verify(releaseOrderMapper).selectForUpdate(ORDER_ID);
    }

    // ---------- 加锁后事实：判定必须来自锁后整行，不能来自加锁前快照 ----------

    @Test
    @DisplayName("锁后事实优先：快照是 CREATED、锁后已是 RUNNING 时拒绝执行（快照不参与判定）")
    void postLockFactWinsWhenSnapshotIsStale() {
        markTransactionActive();
        // 加锁前快照：CREATED（会被放进 MyBatis SESSION 一级缓存的那次读）
        when(releaseOrderMapper.selectById(ORDER_ID)).thenReturn(order("CREATED"));
        // 加锁后读回：别的请求已经把它改成 RUNNING 并提交
        when(releaseOrderMapper.selectForUpdate(ORDER_ID)).thenReturn(order("RUNNING"));

        BizException ex = assertThrows(BizException.class, () -> claimService.claimForExecution(ORDER_ID));

        assertEquals("当前发布单状态不允许执行", ex.getMessage());
        verify(releaseOrderMapper, never()).markRunningIfExecutable(any());
        // 快照读只允许一次：再加一次 selectById 会被一级缓存命中而拿回旧状态
        verify(releaseOrderMapper, times(1)).selectById(ORDER_ID);
        verify(releaseOrderMapper, times(1)).selectForUpdate(ORDER_ID);
    }

    @Test
    @DisplayName("锁后事实优先：快照是 SUCCESS、锁后已回到 CREATED 时按锁后事实放行")
    void postLockFactWinsWhenSnapshotIsPessimistic() {
        markTransactionActive();
        when(releaseOrderMapper.selectById(ORDER_ID)).thenReturn(order("SUCCESS"));
        when(releaseOrderMapper.selectForUpdate(ORDER_ID)).thenReturn(order("CREATED"));

        ReleaseExecutionClaimService.ClaimedExecution claimed = claimService.claimForExecution(ORDER_ID);

        assertEquals("RUNNING", claimed.order().getStatus());
        verify(releaseOrderMapper).markRunningIfExecutable(ORDER_ID);
    }

    // ---------- 执行认领：锁顺序、判据与条件更新 ----------

    @Test
    @DisplayName("认领成功：锁顺序固定为 base_service → release_order，返回加锁后重读并置 RUNNING 的发布单")
    void claimLocksServiceThenOrderAndReturnsRunningOrder() {
        markTransactionActive();

        ReleaseExecutionClaimService.ClaimedExecution claimed = claimService.claimForExecution(ORDER_ID);

        assertNotNull(claimed);
        assertNotNull(claimed.order());
        assertEquals("RUNNING", claimed.order().getStatus());
        assertEquals(SERVICE_ID, claimed.order().getServiceId());

        InOrder sequence = inOrder(baseServiceMapper, releaseOrderMapper);
        sequence.verify(baseServiceMapper).lockById(SERVICE_ID);
        sequence.verify(releaseOrderMapper).selectForUpdate(ORDER_ID);
        sequence.verify(releaseOrderMapper).markRunningIfExecutable(ORDER_ID);
        // 快照读只用来确定锁对象，判定用的是锁后整行
        verify(releaseOrderMapper, times(1)).selectById(ORDER_ID);
    }

    @Test
    @DisplayName("认领拒绝：状态不可执行时不调用条件更新（既有消息不变）")
    void claimRejectsNonExecutableStatus() {
        markTransactionActive();
        when(releaseOrderMapper.selectForUpdate(ORDER_ID)).thenReturn(order("SUCCESS"));

        BizException ex = assertThrows(BizException.class, () -> claimService.claimForExecution(ORDER_ID));

        assertEquals("当前发布单状态不允许执行", ex.getMessage());
        verify(releaseOrderMapper, never()).markRunningIfExecutable(any());
    }

    @Test
    @DisplayName("认领拒绝：发布单不存在时拒绝")
    void claimRejectsMissingOrder() {
        markTransactionActive();
        when(releaseOrderMapper.selectById(ORDER_ID)).thenReturn(null);

        BizException ex = assertThrows(BizException.class, () -> claimService.claimForExecution(ORDER_ID));

        assertEquals("发布单不存在", ex.getMessage());
        verify(baseServiceMapper, never()).lockById(any());
    }

    @Test
    @DisplayName("认领拒绝：同一服务/环境已有执行中的发布")
    void claimRejectsWhenReleaseAlreadyRunningForTarget() {
        markTransactionActive();
        when(releaseOrderMapper.countRunningByServiceAndEnv(SERVICE_ID, ENV_ID)).thenReturn(1L);

        BizException ex = assertThrows(BizException.class, () -> claimService.claimForExecution(ORDER_ID));

        assertEquals("当前服务在该环境下已有执行中的发布或回滚任务", ex.getMessage());
        verify(releaseOrderMapper, never()).markRunningIfExecutable(any());
    }

    @Test
    @DisplayName("认领拒绝：同一服务/环境已有执行中的回滚（保留既有发布/回滚互斥）")
    void claimRejectsWhenRollbackAlreadyRunningForTarget() {
        markTransactionActive();
        when(releaseRollbackOrderMapper.countRunningByServiceAndEnv(SERVICE_ID, ENV_ID)).thenReturn(1L);

        BizException ex = assertThrows(BizException.class, () -> claimService.claimForExecution(ORDER_ID));

        assertEquals("当前服务在该环境下已有执行中的发布或回滚任务", ex.getMessage());
        verify(releaseOrderMapper, never()).markRunningIfExecutable(any());
    }

    @Test
    @DisplayName("认领拒绝：条件更新未命中（状态已被改掉）时不视为认领成功")
    void claimRejectsWhenConditionalUpdateMisses() {
        markTransactionActive();
        when(releaseOrderMapper.markRunningIfExecutable(ORDER_ID)).thenReturn(0);

        BizException ex = assertThrows(BizException.class, () -> claimService.claimForExecution(ORDER_ID));

        assertEquals("当前发布单状态不允许执行", ex.getMessage());
    }

    // ---------- 批准 + 认领：同一短事务内的两处状态 ----------

    @Test
    @DisplayName("批准并认领：先认领订单再消费审批，顺序固定，返回本次认领的订单")
    void approveAndClaimClaimsOrderThenConsumesApproval() {
        markTransactionActive();

        ReleaseExecutionClaimService.ClaimedExecution claimed =
                claimService.claimForApprovedExecution(APPROVAL_ID, ORDER_ID, APPROVER_ID, "同意执行");

        assertEquals("RUNNING", claimed.order().getStatus());
        assertEquals(ORDER_ID, claimed.order().getId());

        InOrder sequence = inOrder(baseServiceMapper, releaseOrderMapper, approvalRequestMapper);
        sequence.verify(baseServiceMapper).lockById(SERVICE_ID);
        sequence.verify(releaseOrderMapper).selectForUpdate(ORDER_ID);
        sequence.verify(releaseOrderMapper).markRunningIfExecutable(ORDER_ID);
        sequence.verify(approvalRequestMapper).markApprovedIfPending(eq(APPROVAL_ID), eq(APPROVER_ID), eq("同意执行"), any());
    }

    @Test
    @DisplayName("批准并认领：审批已被其他请求处理（受影响 0 行）时抛异常，由调用方事务整体回滚")
    void approveAndClaimFailsWhenApprovalAlreadyProcessed() {
        markTransactionActive();
        when(approvalRequestMapper.markApprovedIfPending(eq(APPROVAL_ID), any(), any(), any())).thenReturn(0);

        BizException ex = assertThrows(BizException.class,
                () -> claimService.claimForApprovedExecution(APPROVAL_ID, ORDER_ID, APPROVER_ID, "同意"));

        assertEquals("审批单状态不是PENDING，已被其他请求处理", ex.getMessage());
        verify(releaseOrderMapper).markRunningIfExecutable(ORDER_ID);
    }

    @Test
    @DisplayName("批准并认领：订单不可执行时审批完全不被消费（不产生“审批被吃掉却没执行”）")
    void approveAndClaimDoesNotConsumeApprovalWhenOrderNotExecutable() {
        markTransactionActive();
        when(releaseOrderMapper.selectForUpdate(ORDER_ID)).thenReturn(order("FAILED"));

        BizException ex = assertThrows(BizException.class,
                () -> claimService.claimForApprovedExecution(APPROVAL_ID, ORDER_ID, APPROVER_ID, "同意"));

        assertEquals("当前发布单状态不允许执行", ex.getMessage());
        verify(releaseOrderMapper, never()).markRunningIfExecutable(any());
        verify(approvalRequestMapper, never()).markApprovedIfPending(any(), any(), any(), any());
    }

    @Test
    @DisplayName("批准并认领：服务/环境已有执行中任务时审批不被消费")
    void approveAndClaimDoesNotConsumeApprovalOnRunningConflict() {
        markTransactionActive();
        when(releaseRollbackOrderMapper.countRunningByServiceAndEnv(SERVICE_ID, ENV_ID)).thenReturn(1L);

        BizException ex = assertThrows(BizException.class,
                () -> claimService.claimForApprovedExecution(APPROVAL_ID, ORDER_ID, APPROVER_ID, "同意"));

        assertEquals("当前服务在该环境下已有执行中的发布或回滚任务", ex.getMessage());
        verify(approvalRequestMapper, never()).markApprovedIfPending(any(), any(), any(), any());
    }

    @Test
    @DisplayName("批准并认领：订单条件更新未命中时审批不被消费")
    void approveAndClaimDoesNotConsumeApprovalWhenOrderClaimMisses() {
        markTransactionActive();
        when(releaseOrderMapper.markRunningIfExecutable(ORDER_ID)).thenReturn(0);

        BizException ex = assertThrows(BizException.class,
                () -> claimService.claimForApprovedExecution(APPROVAL_ID, ORDER_ID, APPROVER_ID, "同意"));

        assertEquals("当前发布单状态不允许执行", ex.getMessage());
        verify(approvalRequestMapper, never()).markApprovedIfPending(any(), any(), any(), any());
    }

    // ---------- 终态记录 ----------

    @Test
    @DisplayName("成功终态：记录 SUCCESS、按既有规则更新实例版本与当前版本")
    void markSucceededUpdatesOrderInstancesAndCurrentVersion() {
        markTransactionActive();
        when(releaseOrderMapper.markSuccessIfRunning(ORDER_ID)).thenReturn(1);

        claimService.markSucceeded(ORDER_ID, ENV_ID, SERVICE_ID, List.of(1001L, 1002L), 7L);

        verify(releaseOrderMapper).markSuccessIfRunning(ORDER_ID);
        verify(runtimeServiceInstanceService).updateVersion(List.of(1001L, 1002L), 7L);
        verify(releaseCurrentVersionService).markReleaseVersion(ENV_ID, SERVICE_ID, 7L, ORDER_ID);
    }

    @Test
    @DisplayName("成功终态：没有成功实例时不更新实例版本，当前版本仍按既有规则记录")
    void markSucceededSkipsInstanceUpdateWhenNoSuccessInstance() {
        markTransactionActive();
        when(releaseOrderMapper.markSuccessIfRunning(ORDER_ID)).thenReturn(1);

        claimService.markSucceeded(ORDER_ID, ENV_ID, SERVICE_ID, List.of(), 7L);

        verify(runtimeServiceInstanceService, never()).updateVersion(any(), any());
        verify(releaseCurrentVersionService).markReleaseVersion(ENV_ID, SERVICE_ID, 7L, ORDER_ID);
    }

    @Test
    @DisplayName("失败终态：记录 FAILED 并调起 RUNNING 部署记录的失败证据兜底")
    void markFailedRecordsFailedStatusAndDeployEvidence() {
        markTransactionActive();
        when(releaseOrderMapper.selectForUpdate(ORDER_ID)).thenReturn(order("RUNNING"));
        when(runtimeDeployRecordService.failRunningRecordsByReleaseOrderId(ORDER_ID, "kubectl 失败")).thenReturn(1);

        claimService.markFailed(ORDER_ID, "kubectl 失败");

        verify(releaseOrderMapper).markFailedIfRunning(ORDER_ID);
        verify(runtimeDeployRecordService).failRunningRecordsByReleaseOrderId(ORDER_ID, "kubectl 失败");
        verify(runtimeServiceInstanceService, never()).updateVersion(any(), any());
    }

    @Test
    @DisplayName("失败终态：无 RUNNING 部署记录时同样记录 FAILED，不报错")
    void markFailedToleratesNoRunningRecord() {
        markTransactionActive();
        when(releaseOrderMapper.selectForUpdate(ORDER_ID)).thenReturn(order("RUNNING"));
        when(runtimeDeployRecordService.failRunningRecordsByReleaseOrderId(any(), any())).thenReturn(0);

        assertDoesNotThrow(() -> claimService.markFailed(ORDER_ID, "无日志异常"));

        verify(releaseOrderMapper).markFailedIfRunning(ORDER_ID);
    }

    // ---------- 并发：同一发布单最多一个胜者 ----------

    @Test
    @DisplayName("并发认领：两个线程同时认领同一发布单，恰好一个成功、另一个被拒（不使用 sleep）")
    void concurrentClaimHasExactlyOneWinner() throws Exception {
        // 模拟数据库行锁：锁在“认领事务”结束时才释放（Semaphore 不绑定线程，与数据库行锁语义一致），
        // 因此两个线程的“加锁 → 重读 → 条件更新”整段被串行化，正是生产中的数据库行为。
        Semaphore serviceRowLock = new Semaphore(1, true);
        when(baseServiceMapper.lockById(SERVICE_ID)).thenAnswer(invocation -> {
            serviceRowLock.acquire();
            return SERVICE_ID;
        });

        ReleaseOrder mutableOrder = order("CREATED");
        when(releaseOrderMapper.selectById(ORDER_ID)).thenReturn(mutableOrder);
        when(releaseOrderMapper.selectForUpdate(ORDER_ID)).thenAnswer(invocation -> newOrderCopy(mutableOrder));

        AtomicBoolean runningClaimed = new AtomicBoolean(false);
        when(releaseOrderMapper.markRunningIfExecutable(ORDER_ID)).thenAnswer(invocation -> {
            if (runningClaimed.compareAndSet(false, true)) {
                mutableOrder.setStatus("RUNNING");
                return 1;
            }
            return 0;
        });

        CyclicBarrier bothThreadsReady = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Boolean> attempt = () -> {
                bothThreadsReady.await();
                TransactionSynchronizationManager.setActualTransactionActive(true);
                try {
                    claimService.claimForExecution(ORDER_ID);
                    return true;
                } catch (BizException rejected) {
                    return false;
                } finally {
                    // 事务提交/回滚 → 行锁释放
                    serviceRowLock.release();
                    TransactionSynchronizationManager.setActualTransactionActive(false);
                }
            };

            Future<Boolean> first = pool.submit(attempt);
            Future<Boolean> second = pool.submit(attempt);
            boolean firstWon = first.get();
            boolean secondWon = second.get();

            assertTrue(firstWon ^ secondWon, "并发认领必须恰好一个成功：两个都成功或都失败都意味着认领失效");
            // 败者拿到服务行锁时重读到的是已提交的 RUNNING，判定为不可执行，因此条件更新只被调用一次
            verify(releaseOrderMapper, times(1)).markRunningIfExecutable(ORDER_ID);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("并发批准：两个线程同时批准同一审批，恰好一个成功，订单与审批都只被认领一次（不使用 sleep）")
    void concurrentApproveHasExactlyOneWinnerAndConsumesApprovalOnce() throws Exception {
        Semaphore serviceRowLock = new Semaphore(1, true);
        when(baseServiceMapper.lockById(SERVICE_ID)).thenAnswer(invocation -> {
            serviceRowLock.acquire();
            return SERVICE_ID;
        });

        ReleaseOrder mutableOrder = order("APPROVED");
        when(releaseOrderMapper.selectById(ORDER_ID)).thenReturn(mutableOrder);
        // 锁后读回的是当前已提交事实（胜者提交后，败者看到 RUNNING）
        when(releaseOrderMapper.selectForUpdate(ORDER_ID)).thenAnswer(invocation -> newOrderCopy(mutableOrder));

        AtomicBoolean runningClaimed = new AtomicBoolean(false);
        when(releaseOrderMapper.markRunningIfExecutable(ORDER_ID)).thenAnswer(invocation -> {
            if (runningClaimed.compareAndSet(false, true)) {
                mutableOrder.setStatus("RUNNING");
                return 1;
            }
            return 0;
        });

        AtomicBoolean approvalConsumed = new AtomicBoolean(false);
        AtomicInteger approvalConsumeAttempts = new AtomicInteger();
        when(approvalRequestMapper.markApprovedIfPending(eq(APPROVAL_ID), any(), any(), any())).thenAnswer(invocation -> {
            approvalConsumeAttempts.incrementAndGet();
            return approvalConsumed.compareAndSet(false, true) ? 1 : 0;
        });

        CyclicBarrier bothThreadsReady = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Boolean> attempt = () -> {
                bothThreadsReady.await();
                TransactionSynchronizationManager.setActualTransactionActive(true);
                try {
                    claimService.claimForApprovedExecution(APPROVAL_ID, ORDER_ID, APPROVER_ID, "同意");
                    return true;
                } catch (BizException rejected) {
                    return false;
                } finally {
                    serviceRowLock.release();
                    TransactionSynchronizationManager.setActualTransactionActive(false);
                }
            };

            Future<Boolean> first = pool.submit(attempt);
            Future<Boolean> second = pool.submit(attempt);
            boolean firstWon = first.get();
            boolean secondWon = second.get();

            assertTrue(firstWon ^ secondWon, "并发批准必须恰好一个成功");
            assertEquals(true, approvalConsumed.get(), "审批必须恰好被消费一次");
            verify(releaseOrderMapper, times(1)).markRunningIfExecutable(ORDER_ID);
            verify(approvalRequestMapper, times(1)).markApprovedIfPending(eq(APPROVAL_ID), any(), any(), any());
            assertEquals(1, approvalConsumeAttempts.get(), "败者在认领阶段就被拒绝，不得再触碰审批行");
        } finally {
            pool.shutdownNow();
        }
    }

    /** 模拟数据库返回的是独立行对象：调用方拿到的是值，不是共享引用。 */
    private static ReleaseOrder newOrderCopy(ReleaseOrder source) {
        ReleaseOrder copy = new ReleaseOrder();
        copy.setId(source.getId());
        copy.setServiceId(source.getServiceId());
        copy.setEnvId(source.getEnvId());
        copy.setStatus(source.getStatus());
        return copy;
    }
}
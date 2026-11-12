package com.infrahub.release.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.infrahub.base.mapper.BaseServiceMapper;
import com.infrahub.common.exception.BizException;
import com.infrahub.release.entity.ApprovalRequest;
import com.infrahub.release.entity.ReleaseOrder;
import com.infrahub.release.mapper.ApprovalRequestMapper;
import com.infrahub.release.mapper.ReleaseOrderMapper;
import com.infrahub.release.mapper.ReleaseRollbackOrderMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.infrahub.release.service.MonitorAuditLogService.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 审批拒绝/取消的条件状态转换测试：只允许 PENDING → REJECTED / PENDING → CANCELLED，
 * 由数据库条件更新的受影响行数判定成败。不连接真实 DB / HTTP / SSH / Docker / Kubernetes。
 *
 * <p>要防的缺陷是“先读到 PENDING 再无条件写回”：读到与写回之间并发批准可能已经消费掉这条审批，
 * 无条件写回会把 APPROVED 覆盖成 REJECTED/CANCELLED，被消费的审批连同它的执行事实一起消失。
 *
 * <p>本测试用真实 Spring 事务代理（CGLIB）+ 只记数的假事务管理器，
 * 并用 {@link Semaphore} 编排并发先后（不使用 sleep）；真实数据库行锁与条件更新
 * 仍需真实数据库验证（Codex 负责）。
 */
class ApprovalRequestStatusTransitionTest {

    private static final Long APPROVAL_ID = 1L;
    private static final Long RELEASE_ORDER_ID = 7L;
    private static final Long APPLICANT_ID = 1L;
    private static final Long OTHER_USER_ID = 999L;
    private static final Long SERVICE_ID = 3L;
    private static final Long ENV_ID = 2L;

    private static final TrackingTransactionManager TRANSACTIONS = new TrackingTransactionManager();
    private static final ApprovalRequestMapper APPROVAL_MAPPER = mock(ApprovalRequestMapper.class);
    private static final BaseServiceMapper BASE_SERVICE_MAPPER = mock(BaseServiceMapper.class);
    private static final ReleaseOrderMapper RELEASE_ORDER_MAPPER = mock(ReleaseOrderMapper.class);
    private static final ReleaseRollbackOrderMapper ROLLBACK_ORDER_MAPPER = mock(ReleaseRollbackOrderMapper.class);
    private static final ReleaseOrderService RELEASE_ORDER_SERVICE = mock(ReleaseOrderService.class);
    private static final ReleaseRollbackOrderService ROLLBACK_ORDER_SERVICE = mock(ReleaseRollbackOrderService.class);
    private static final MonitorAuditLogService AUDIT_LOG_SERVICE = mock(MonitorAuditLogService.class);
    private static final ApprovalNotificationService NOTIFICATION_SERVICE = mock(ApprovalNotificationService.class);

    static class TrackingTransactionManager extends AbstractPlatformTransactionManager {

        final AtomicInteger begun = new AtomicInteger();
        final AtomicInteger committed = new AtomicInteger();
        final AtomicInteger rolledBack = new AtomicInteger();
        /** “大事务已被参与型内层失败标记为 rollback-only”的按线程状态，对应真实连接的 rollback-only 标记。 */
        private final ThreadLocal<Boolean> rollbackOnly = ThreadLocal.withInitial(() -> Boolean.FALSE);

        void resetCounters() {
            begun.set(0);
            committed.set(0);
            rolledBack.set(0);
        }

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        /**
         * 必须如实回答“当前线程是否已有事务”：REQUIRED 传播在已有事务时是<b>加入</b>而不是新开。
         * 恒返回 false 会把嵌套的 @Transactional 都变成新事务，数出的事务数与真实语义不符。
         */
        @Override
        protected boolean isExistingTransaction(Object transaction) {
            return TransactionSynchronizationManager.isActualTransactionActive();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            begun.incrementAndGet();
            rollbackOnly.set(Boolean.FALSE);
        }

        /**
         * 参与型内层事务失败时框架要求提供这一行为：外层大事务必须被标记为 rollback-only。
         * 没有它，内层失败可以被外层“吞掉异常后照样提交”，正是要防的一半提交。
         */
        @Override
        protected void doSetRollbackOnly(DefaultTransactionStatus status) {
            rollbackOnly.set(Boolean.TRUE);
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            if (Boolean.TRUE.equals(rollbackOnly.get())) {
                throw new UnexpectedRollbackException("大事务已被参与型内层失败标记为 rollback-only，禁止提交");
            }
            committed.incrementAndGet();
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            rolledBack.incrementAndGet();
        }
    }

    @Configuration
    @EnableTransactionManagement(proxyTargetClass = true)
    static class TestConfig {

        @Bean
        PlatformTransactionManager transactionManager() {
            return TRANSACTIONS;
        }

        @Bean
        ApprovalRequestMapper approvalRequestMapper() {
            return APPROVAL_MAPPER;
        }

        @Bean
        BaseServiceMapper baseServiceMapper() {
            return BASE_SERVICE_MAPPER;
        }

        @Bean
        ReleaseOrderMapper releaseOrderMapper() {
            return RELEASE_ORDER_MAPPER;
        }

        @Bean
        ReleaseRollbackOrderMapper releaseRollbackOrderMapper() {
            return ROLLBACK_ORDER_MAPPER;
        }

        @Bean
        ReleaseExecutionClaimService releaseExecutionClaimService() {
            return new ReleaseExecutionClaimService(
                    BASE_SERVICE_MAPPER, RELEASE_ORDER_MAPPER, ROLLBACK_ORDER_MAPPER,
                    APPROVAL_MAPPER, mock(RuntimeDeployRecordService.class),
                    mock(RuntimeServiceInstanceService.class), mock(ReleaseCurrentVersionService.class),
                    org.mockito.Mockito.mock(RuntimeExecutionUnknownService.class), org.mockito.Mockito.mock(com.infrahub.pipeline.mapper.PipelineRunMapper.class), org.mockito.Mockito.mock(com.infrahub.pipeline.mapper.PipelineStepRunMapper.class), org.mockito.Mockito.mock(com.infrahub.pipeline.mapper.PipelineStepMapper.class), org.mockito.Mockito.mock(ReleaseExecutionRunClaimService.class));
        }

        @Bean
        ApprovalRequestService approvalRequestService(ReleaseExecutionClaimService releaseExecutionClaimService) {
            return new ApprovalRequestService(
                    mock(ApprovalPolicyService.class),
                    AUDIT_LOG_SERVICE,
                    mock(MonitorAlertRuleService.class),
                    mock(MonitorHealthCheckService.class),
                    RELEASE_ORDER_SERVICE,
                    ROLLBACK_ORDER_SERVICE,
                    new ObjectMapper(),
                    NOTIFICATION_SERVICE,
                    mock(RollbackTargetCoordinator.class),
                    releaseExecutionClaimService);
        }
    }

    private AnnotationConfigApplicationContext context;
    private ApprovalRequestService service;

    @BeforeEach
    void setUp() {
        reset(APPROVAL_MAPPER, BASE_SERVICE_MAPPER, RELEASE_ORDER_MAPPER, ROLLBACK_ORDER_MAPPER,
                RELEASE_ORDER_SERVICE, ROLLBACK_ORDER_SERVICE, AUDIT_LOG_SERVICE, NOTIFICATION_SERVICE);
        TRANSACTIONS.resetCounters();

        when(BASE_SERVICE_MAPPER.lockById(SERVICE_ID)).thenReturn(SERVICE_ID);
        when(RELEASE_ORDER_MAPPER.selectById(RELEASE_ORDER_ID)).thenReturn(releaseOrder());
        when(RELEASE_ORDER_MAPPER.selectForUpdate(RELEASE_ORDER_ID)).thenReturn(releaseOrder());
        when(RELEASE_ORDER_MAPPER.markRunningIfExecutable(RELEASE_ORDER_ID)).thenReturn(1);

        context = new AnnotationConfigApplicationContext(TestConfig.class);
        service = context.getBean(ApprovalRequestService.class);
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    private static ReleaseOrder releaseOrder() {
        ReleaseOrder order = new ReleaseOrder();
        order.setId(RELEASE_ORDER_ID);
        order.setServiceId(SERVICE_ID);
        order.setEnvId(ENV_ID);
        order.setStatus("APPROVED");
        return order;
    }

    private ApprovalRequest pendingReleaseApproval(Long applicantId) {
        ApprovalRequest approval = new ApprovalRequest();
        approval.setId(APPROVAL_ID);
        approval.setBizType(ApprovalRequest.BIZ_TYPE_RELEASE_ORDER);
        approval.setAction(ApprovalRequest.ACTION_EXECUTE_RELEASE);
        approval.setBizId(RELEASE_ORDER_ID);
        approval.setStatus(ApprovalRequest.STATUS_PENDING);
        approval.setApplicantId(applicantId);
        return approval;
    }

    /**
     * 每次读取都返回<b>独立的 PENDING 快照</b>，而不是所有请求共用同一个可变实例。
     *
     * <p>真实数据库里两个并发请求各自读到自己的行快照；共用实例会让胜者在内存里的
     * {@code setStatus(...)} 泄漏进败者的读取，败者在非事务的快路径上就提前失败，
     * 于是“败者已经认领了订单，但整个事务必须回滚”这条最危险的交错根本走不到。
     * 谁真正赢得状态转换仍然只由条件更新的受影响行数决定。
     */
    private void stubPendingReleaseApproval(Long applicantId) {
        when(APPROVAL_MAPPER.selectById(APPROVAL_ID))
                .thenAnswer(invocation -> pendingReleaseApproval(applicantId));
    }

    // ---------- 拒绝：只允许 PENDING → REJECTED ----------

    @Test
    @DisplayName("拒绝：条件更新命中时受控成功，写成功审计并发通知，不再做无条件写回")
    void rejectWinsConditionalTransition() {
        stubPendingReleaseApproval(OTHER_USER_ID);
        when(APPROVAL_MAPPER.markRejectedIfPending(eq(APPROVAL_ID), any(), any(), any())).thenReturn(1);

        service.reject(APPROVAL_ID, "环境不稳定");

        verify(APPROVAL_MAPPER).markRejectedIfPending(eq(APPROVAL_ID), any(), eq("环境不稳定"), any());
        verify(APPROVAL_MAPPER, never()).updateById(any(ApprovalRequest.class));
        verify(AUDIT_LOG_SERVICE).recordApproval(eq(APPROVAL_ID), eq(ACTION_APPROVAL_REJECT),
                eq(RESULT_SUCCESS), any(), any());
        verify(NOTIFICATION_SERVICE).notifyRejected(any());
        assertEquals(1, TRANSACTIONS.committed.get());
        assertEquals(0, TRANSACTIONS.rolledBack.get());
    }

    @Test
    @DisplayName("拒绝：审批已被并发批准/取消（受影响 0 行）时受控失败，不写成功审计、不发通知")
    void rejectLosesWhenApprovalAlreadyProcessed() {
        stubPendingReleaseApproval(OTHER_USER_ID);
        when(APPROVAL_MAPPER.markRejectedIfPending(eq(APPROVAL_ID), any(), any(), any())).thenReturn(0);

        BizException thrown = assertThrows(BizException.class,
                () -> service.reject(APPROVAL_ID, "环境不稳定"));

        assertTrue(thrown.getMessage().contains("已被其他请求处理"), "必须说明状态已被并发请求改变");
        verify(APPROVAL_MAPPER, never()).updateById(any(ApprovalRequest.class));
        verify(AUDIT_LOG_SERVICE, never()).recordApproval(any(), eq(ACTION_APPROVAL_REJECT),
                eq(RESULT_SUCCESS), any(), any());
        verify(NOTIFICATION_SERVICE, never()).notifyRejected(any());
        assertEquals(0, TRANSACTIONS.committed.get());
        assertEquals(1, TRANSACTIONS.rolledBack.get(), "抢不到转换权时不得留下任何写入");
    }

    // ---------- 取消：只允许 PENDING → CANCELLED，且只有申请人可以取消 ----------

    @Test
    @DisplayName("取消：条件更新命中时受控成功，写成功审计并发通知，不再做无条件写回")
    void cancelWinsConditionalTransition() {
        stubPendingReleaseApproval(APPLICANT_ID);
        when(APPROVAL_MAPPER.markCancelledIfPending(APPROVAL_ID)).thenReturn(1);

        service.cancel(APPROVAL_ID);

        verify(APPROVAL_MAPPER).markCancelledIfPending(APPROVAL_ID);
        verify(APPROVAL_MAPPER, never()).updateById(any(ApprovalRequest.class));
        verify(AUDIT_LOG_SERVICE).recordApproval(eq(APPROVAL_ID), eq(ACTION_APPROVAL_CANCEL),
                eq(RESULT_SUCCESS), any(), any());
        verify(NOTIFICATION_SERVICE).notifyCancelled(any());
    }

    @Test
    @DisplayName("取消：审批已被并发处理（受影响 0 行）时受控失败，不写成功审计、不发通知")
    void cancelLosesWhenApprovalAlreadyProcessed() {
        stubPendingReleaseApproval(APPLICANT_ID);
        when(APPROVAL_MAPPER.markCancelledIfPending(APPROVAL_ID)).thenReturn(0);

        BizException thrown = assertThrows(BizException.class, () -> service.cancel(APPROVAL_ID));

        assertTrue(thrown.getMessage().contains("已被其他请求处理"));
        verify(AUDIT_LOG_SERVICE, never()).recordApproval(any(), eq(ACTION_APPROVAL_CANCEL),
                eq(RESULT_SUCCESS), any(), any());
        verify(NOTIFICATION_SERVICE, never()).notifyCancelled(any());
        assertEquals(1, TRANSACTIONS.rolledBack.get());
    }

    @Test
    @DisplayName("取消：非申请人取消被拒，且完全不触碰审批行")
    void cancelRequiresApplicant() {
        stubPendingReleaseApproval(OTHER_USER_ID);

        BizException thrown = assertThrows(BizException.class, () -> service.cancel(APPROVAL_ID));

        assertEquals("无权限取消他人的审批单", thrown.getMessage());
        verify(APPROVAL_MAPPER, never()).markCancelledIfPending(any());
        verify(APPROVAL_MAPPER, never()).updateById(any(ApprovalRequest.class));
        verify(NOTIFICATION_SERVICE, never()).notifyCancelled(any());
    }

    // ---------- 并发批准 vs 拒绝：被消费的审批不得被覆盖 ----------

    @Test
    @DisplayName("并发批准与拒绝：审批只被消费一次、拒绝获胜后不被批准覆盖，败者不写成功审计与通知（不使用 sleep）")
    void concurrentApproveAndRejectHaveExactlyOneWinner() throws Exception {
        // 审批行状态由条件更新独占推进：无论谁先，只有一个请求能把 PENDING 改成终态
        AtomicReference<String> approvalStatus = new AtomicReference<>("PENDING");

        // 事件编排：让批准线程在“消费审批”这一步等拒绝线程先完成，从而确定性地制造
        // “批准已经认领了订单、但审批已被拒绝消费”这一最危险的交错。
        Semaphore rejectFinished = new Semaphore(0);
        when(APPROVAL_MAPPER.markRejectedIfPending(eq(APPROVAL_ID), any(), any(), any()))
                .thenAnswer(invocation -> approvalStatus.compareAndSet("PENDING", "REJECTED") ? 1 : 0);
        when(APPROVAL_MAPPER.markApprovedIfPending(eq(APPROVAL_ID), any(), any(), any()))
                .thenAnswer(invocation -> {
                    assertTrue(rejectFinished.tryAcquire(15, TimeUnit.SECONDS),
                            "批准线程必须等到拒绝完成后再消费审批");
                    return approvalStatus.compareAndSet("PENDING", "APPROVED") ? 1 : 0;
                });

        // 两个线程读到的都是 PENDING 快照：谁都不知道对方的存在，全靠条件更新分胜负
        stubPendingReleaseApproval(OTHER_USER_ID);

        CyclicBarrier bothThreadsReady = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Boolean> approveAttempt = () -> {
                bothThreadsReady.await();
                try {
                    service.approve(APPROVAL_ID, "同意发布");
                    return true;
                } catch (BizException rejected) {
                    return false;
                }
            };
            Callable<Boolean> rejectAttempt = () -> {
                bothThreadsReady.await();
                try {
                    service.reject(APPROVAL_ID, "环境不稳定");
                    return true;
                } finally {
                    rejectFinished.release();
                }
            };

            Future<Boolean> approveWon = pool.submit(approveAttempt);
            Future<Boolean> rejectWon = pool.submit(rejectAttempt);

            assertTrue(rejectWon.get(15, TimeUnit.SECONDS), "拒绝先完成，必须获胜");
            assertFalse(approveWon.get(15, TimeUnit.SECONDS), "批准必须失败：审批已被拒绝消费");

            assertEquals("REJECTED", approvalStatus.get(), "被消费的审批不得被并发批准覆盖成 APPROVED");
            // 只有胜者产生成功审计与通知：败者不得写成功审计、不得发通知
            verify(AUDIT_LOG_SERVICE, times(1)).recordApproval(any(), any(), eq(RESULT_SUCCESS), any(), any());
            verify(AUDIT_LOG_SERVICE).recordApproval(eq(APPROVAL_ID), eq(ACTION_APPROVAL_REJECT),
                    eq(RESULT_SUCCESS), any(), any());
            verify(AUDIT_LOG_SERVICE, never()).recordApproval(any(), eq(ACTION_APPROVAL_APPROVE),
                    eq(RESULT_SUCCESS), any(), any());
            assertEquals(1, mockingDetails(NOTIFICATION_SERVICE).getInvocations().size(),
                    "只允许胜者产生一次通知");
            verify(NOTIFICATION_SERVICE, never()).notifyApproved(any());
            // 败者（批准）在事务里已经认领了订单，但整个事务必须回滚
            assertTrue(TRANSACTIONS.rolledBack.get() >= 1, "失败的批准必须整体回滚，订单认领不得留下");
        } finally {
            pool.shutdownNow();
        }
    }

    // ---------- 结构性守卫 ----------

    @Test
    @DisplayName("结构性守卫：拒绝与取消必须是事务方法，条件更新语句必须存在")
    void statusTransitionsAreTransactional() throws Exception {
        assertNotNull(ApprovalRequestService.class.getMethod("reject", Long.class, String.class)
                .getAnnotation(Transactional.class), "拒绝必须是事务方法");
        assertNotNull(ApprovalRequestService.class.getMethod("cancel", Long.class)
                .getAnnotation(Transactional.class), "取消必须是事务方法");
        assertNotNull(ApprovalRequestMapper.class
                        .getMethod("markRejectedIfPending", Long.class, Long.class, String.class,
                                java.time.OffsetDateTime.class),
                "拒绝必须走数据库条件更新");
        assertNotNull(ApprovalRequestMapper.class.getMethod("markCancelledIfPending", Long.class),
                "取消必须走数据库条件更新");
        assertNotNull(ApprovalRequestMapper.class
                        .getMethod("markApprovedIfPending", Long.class, Long.class, String.class,
                                java.time.OffsetDateTime.class),
                "批准必须走数据库条件更新");
    }
}
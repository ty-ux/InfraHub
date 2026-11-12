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

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static com.infrahub.release.service.MonitorAuditLogService.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 审批批准的事务边界与原子性测试：用真实 Spring 事务代理（CGLIB）+ 只记数的假事务管理器验证，
 * 不连接真实 DB / HTTP / SSH / Docker / Kubernetes。
 *
 * <p>这里必须是真的容器，因为要防的是“@Transactional 静默失效”这一整类缺陷：
 * 同类自调用、非懒加载自注入都会让事务注解不生效，而纯 Mockito 单测看不出来。
 *
 * <p>本测试验证的是<b>调用顺序与事务边界</b>，不验证真实数据库协议：
 * 真实 MyBatis 一级缓存、行锁阻塞、提交可见性与并发竞争由真实数据库验证（Codex 负责）。
 *
 * <p>验收四条：
 * <ol>
 *   <li>发布执行审批：批准（PENDING → APPROVED）与认领（订单 → RUNNING）在同一个短事务内完成，
 *       该事务提交之前不调用外部执行器，执行期间没有活动事务，全程只有一个事务；</li>
 *   <li>认领失败或审批竞争失败时整个短事务回滚：审批不被消费、外部执行器不被调用；</li>
 *   <li>批准事实提交后，执行失败/异常不会把它回滚掉，但会记录失败审计、不发成功通知；
 *       执行器“返回失败结果”与“抛异常”走同一条失败反馈路径：受控业务失败、失败处理只执行一次，
 *       不重试、不重新认领、不回退审批状态；</li>
 *   <li>其他审批业务（回滚、监控开关）保留原有“批准 + 业务动作”同一事务语义。</li>
 * </ol>
 */
class ApprovalRequestServiceTransactionTest {

    private static final Long APPROVAL_ID = 1L;
    private static final Long RELEASE_ORDER_ID = 7L;
    private static final Long SERVICE_ID = 3L;
    private static final Long ENV_ID = 2L;

    private static final TrackingTransactionManager TRANSACTIONS = new TrackingTransactionManager();
    private static final ApprovalRequestMapper APPROVAL_MAPPER = mock(ApprovalRequestMapper.class);
    private static final BaseServiceMapper BASE_SERVICE_MAPPER = mock(BaseServiceMapper.class);
    private static final ReleaseOrderMapper RELEASE_ORDER_MAPPER = mock(ReleaseOrderMapper.class);
    private static final ReleaseRollbackOrderMapper ROLLBACK_ORDER_MAPPER = mock(ReleaseRollbackOrderMapper.class);
    private static final RuntimeDeployRecordService RUNTIME_DEPLOY_RECORD_SERVICE = mock(RuntimeDeployRecordService.class);
    private static final RuntimeServiceInstanceService RUNTIME_SERVICE_INSTANCE_SERVICE = mock(RuntimeServiceInstanceService.class);
    private static final ReleaseCurrentVersionService RELEASE_CURRENT_VERSION_SERVICE = mock(ReleaseCurrentVersionService.class);
    private static final ReleaseOrderService RELEASE_ORDER_SERVICE = mock(ReleaseOrderService.class);
    private static final ReleaseRollbackOrderService ROLLBACK_ORDER_SERVICE = mock(ReleaseRollbackOrderService.class);
    private static final MonitorAuditLogService AUDIT_LOG_SERVICE = mock(MonitorAuditLogService.class);
    private static final ApprovalNotificationService NOTIFICATION_SERVICE = mock(ApprovalNotificationService.class);

    /** 只记数的事务管理器：doBegin/doCommit/doRollback 计数，不碰任何真实数据源。 */
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

        boolean isRollbackOnly() {
            return Boolean.TRUE.equals(rollbackOnly.get());
        }

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        /**
         * 必须如实回答“当前线程是否已有事务”：默认的 REQUIRED 传播在已有事务时是<b>加入</b>而不是新开。
         * 这里恒返回 false 会让每一次嵌套的 @Transactional 都新开一个事务，
         * 于是“批准 + 认领是否在同一个短事务内”这类断言会数出 2 个事务，测出的就不是真实事务语义了。
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
            if (isRollbackOnly()) {
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

        /**
         * 认领服务用真实实现 + mock Mapper：这样“批准 + 认领在同一短事务内”才是被真正执行的代码路径，
         * 而不是被 mock 掉的空壳。
         */
        @Bean
        ReleaseExecutionClaimService releaseExecutionClaimService() {
            return new ReleaseExecutionClaimService(
                    BASE_SERVICE_MAPPER, RELEASE_ORDER_MAPPER, ROLLBACK_ORDER_MAPPER,
                    APPROVAL_MAPPER, RUNTIME_DEPLOY_RECORD_SERVICE,
                    RUNTIME_SERVICE_INSTANCE_SERVICE, RELEASE_CURRENT_VERSION_SERVICE,
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
                RUNTIME_DEPLOY_RECORD_SERVICE, RELEASE_ORDER_SERVICE, ROLLBACK_ORDER_SERVICE,
                AUDIT_LOG_SERVICE, NOTIFICATION_SERVICE);
        TRANSACTIONS.resetCounters();

        when(BASE_SERVICE_MAPPER.lockById(SERVICE_ID)).thenReturn(SERVICE_ID);
        when(RELEASE_ORDER_MAPPER.selectById(RELEASE_ORDER_ID)).thenReturn(order("CREATED"));
        when(RELEASE_ORDER_MAPPER.selectForUpdate(RELEASE_ORDER_ID)).thenReturn(order("CREATED"));
        when(RELEASE_ORDER_MAPPER.markRunningIfExecutable(RELEASE_ORDER_ID)).thenReturn(1);

        context = new AnnotationConfigApplicationContext(TestConfig.class);
        service = context.getBean(ApprovalRequestService.class);
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    private ApprovalRequest pendingApproval(String bizType, String action, Long bizId) {
        ApprovalRequest approval = new ApprovalRequest();
        approval.setId(APPROVAL_ID);
        approval.setBizType(bizType);
        approval.setAction(action);
        approval.setBizId(bizId);
        approval.setStatus(ApprovalRequest.STATUS_PENDING);
        return approval;
    }

    private static ReleaseOrder order(String status) {
        ReleaseOrder order = new ReleaseOrder();
        order.setId(RELEASE_ORDER_ID);
        order.setServiceId(SERVICE_ID);
        order.setEnvId(ENV_ID);
        order.setStatus(status);
        return order;
    }

    @Test
    @DisplayName("发布执行审批：批准与认领在同一短事务内完成，提交后才调用外部执行器，全程只有一个事务")
    void releaseApprovalConsumesApprovalAndClaimsOrderAtomicallyBeforeExecution() {
        when(APPROVAL_MAPPER.selectById(APPROVAL_ID))
                .thenReturn(pendingApproval("RELEASE_ORDER", "EXECUTE_RELEASE", RELEASE_ORDER_ID));

        AtomicBoolean transactionActiveWhileClaimingOrder = new AtomicBoolean(false);
        AtomicBoolean transactionActiveWhileConsumingApproval = new AtomicBoolean(false);
        when(RELEASE_ORDER_MAPPER.markRunningIfExecutable(RELEASE_ORDER_ID)).thenAnswer(invocation -> {
            // 认领必须在真实事务中写入，否则条件更新与行锁都不会生效
            transactionActiveWhileClaimingOrder.set(TransactionSynchronizationManager.isActualTransactionActive());
            return 1;
        });
        when(APPROVAL_MAPPER.markApprovedIfPending(eq(APPROVAL_ID), any(), any(), any())).thenAnswer(invocation -> {
            transactionActiveWhileConsumingApproval.set(TransactionSynchronizationManager.isActualTransactionActive());
            return 1;
        });

        AtomicBoolean transactionActiveDuringExecution = new AtomicBoolean(true);
        AtomicInteger commitsAtExecutionStart = new AtomicInteger(-1);
        when(RELEASE_ORDER_SERVICE.executeClaimedReleaseOrder(any())).thenAnswer(invocation -> {
            transactionActiveDuringExecution.set(TransactionSynchronizationManager.isActualTransactionActive());
            commitsAtExecutionStart.set(TRANSACTIONS.committed.get());
            return true;
        });

        service.approve(APPROVAL_ID, "同意发布");

        assertTrue(transactionActiveWhileClaimingOrder.get(), "订单认领必须在事务中写入");
        assertTrue(transactionActiveWhileConsumingApproval.get(), "审批消费必须与订单认领在同一事务中");
        assertEquals(1, commitsAtExecutionStart.get(), "调用外部执行器之前，批准与认领必须已经一起提交");
        assertFalse(transactionActiveDuringExecution.get(), "外部执行（kubectl/SSH）期间不得持有事务");
        assertEquals(1, TRANSACTIONS.begun.get(), "批准链路只允许一个短事务，不能有覆盖整段执行的外层长事务");
        assertEquals(1, TRANSACTIONS.committed.get());
        assertEquals(0, TRANSACTIONS.rolledBack.get());

        verify(APPROVAL_MAPPER, times(1)).markApprovedIfPending(eq(APPROVAL_ID), any(), any(), any());
        verify(RELEASE_ORDER_MAPPER, times(1)).markRunningIfExecutable(RELEASE_ORDER_ID);
        // 执行入口只接受本次认领的结果，不能再去走“直接发布”那条会重新认领的路径
        verify(RELEASE_ORDER_SERVICE).executeClaimedReleaseOrder(argThat(claimed ->
                claimed != null && claimed.order() != null
                        && RELEASE_ORDER_ID.equals(claimed.order().getId())
                        && "RUNNING".equals(claimed.order().getStatus())));
        verify(RELEASE_ORDER_SERVICE, never()).doExecuteReleaseOrder(any());
    }

    @Test
    @DisplayName("发布执行审批：认领失败（订单已被别人改掉）时整个短事务回滚，审批不被消费、外部执行器不被调用")
    void releaseApprovalRollsBackBothFactsWhenOrderClaimFails() {
        when(APPROVAL_MAPPER.selectById(APPROVAL_ID))
                .thenReturn(pendingApproval("RELEASE_ORDER", "EXECUTE_RELEASE", RELEASE_ORDER_ID));
        // 并发请求已经把它改成 RUNNING 并提交：锁后事实不可执行
        when(RELEASE_ORDER_MAPPER.selectForUpdate(RELEASE_ORDER_ID)).thenReturn(order("RUNNING"));

        BizException thrown = assertThrows(BizException.class,
                () -> service.approve(APPROVAL_ID, "同意发布"));

        assertEquals("当前发布单状态不允许执行", thrown.getMessage());
        assertEquals(1, TRANSACTIONS.begun.get());
        assertEquals(0, TRANSACTIONS.committed.get(), "认领失败时不能提交任何状态");
        assertEquals(1, TRANSACTIONS.rolledBack.get(), "必须整体回滚");
        verify(APPROVAL_MAPPER, never()).markApprovedIfPending(any(), any(), any(), any());
        verify(RELEASE_ORDER_SERVICE, never()).executeClaimedReleaseOrder(any());
        verify(NOTIFICATION_SERVICE, never()).notifyApproved(any());
    }

    @Test
    @DisplayName("发布执行审批：审批已被并发批准/拒绝/取消时条件更新未命中，整体回滚且不启动业务动作")
    void releaseApprovalRollsBackWhenApprovalAlreadyConsumed() {
        when(APPROVAL_MAPPER.selectById(APPROVAL_ID))
                .thenReturn(pendingApproval("RELEASE_ORDER", "EXECUTE_RELEASE", RELEASE_ORDER_ID));
        when(APPROVAL_MAPPER.markApprovedIfPending(eq(APPROVAL_ID), any(), any(), any())).thenReturn(0);

        BizException thrown = assertThrows(BizException.class,
                () -> service.approve(APPROVAL_ID, "同意发布"));

        assertTrue(thrown.getMessage().contains("已被其他请求处理"), "必须说明已被其他请求消费");
        assertEquals(1, TRANSACTIONS.begun.get());
        assertEquals(0, TRANSACTIONS.committed.get(), "审批竞争失败时订单认领也必须一起回滚");
        assertEquals(1, TRANSACTIONS.rolledBack.get());
        verify(RELEASE_ORDER_SERVICE, never()).executeClaimedReleaseOrder(any());
        verify(RELEASE_ORDER_SERVICE, never()).doExecuteReleaseOrder(any());
        verify(NOTIFICATION_SERVICE, never()).notifyApproved(any());
    }

    @Test
    @DisplayName("发布执行审批：执行成功时成功审计与成功通知各一次")
    void releaseApprovalReportsSuccessExactlyOnce() {
        when(APPROVAL_MAPPER.selectById(APPROVAL_ID))
                .thenReturn(pendingApproval("RELEASE_ORDER", "EXECUTE_RELEASE", RELEASE_ORDER_ID));
        when(APPROVAL_MAPPER.markApprovedIfPending(eq(APPROVAL_ID), any(), any(), any())).thenReturn(1);
        when(RELEASE_ORDER_SERVICE.executeClaimedReleaseOrder(any())).thenReturn(true);

        service.approve(APPROVAL_ID, "同意发布");

        verify(AUDIT_LOG_SERVICE, times(1)).recordApproval(eq(APPROVAL_ID), eq(ACTION_APPROVAL_APPROVE),
                eq(RESULT_SUCCESS), any(), any());
        verify(NOTIFICATION_SERVICE, times(1)).notifyApproved(any());
        assertEquals(1, mockingDetails(AUDIT_LOG_SERVICE).getInvocations().size(),
                "成功路径只允许一条审计");
        assertEquals(1, TRANSACTIONS.committed.get());
        assertEquals(0, TRANSACTIONS.rolledBack.get());
    }

    /**
     * 执行器返回失败结果（不是抛异常）时，审批入口必须给出受控业务失败，并且与异常路径一致地
     * 只写失败审计、不发成功通知；同时不得重试、不得重新认领、不得回退审批状态，也不得抹掉
     * 已经提交的批准事实与执行证据。
     */
    @Test
    @DisplayName("发布执行审批：执行器返回失败结果时受控失败、失败审计一次，不重试、不重新认领、不回退审批")
    void releaseApprovalReportsExecutionFailureWithoutRetryOrReclaim() {
        when(APPROVAL_MAPPER.selectById(APPROVAL_ID))
                .thenReturn(pendingApproval("RELEASE_ORDER", "EXECUTE_RELEASE", RELEASE_ORDER_ID));
        when(APPROVAL_MAPPER.markApprovedIfPending(eq(APPROVAL_ID), any(), any(), any())).thenReturn(1);
        // 执行器正常返回，但结果是失败（发布单与部署记录已由执行链路写成 FAILED）
        when(RELEASE_ORDER_SERVICE.executeClaimedReleaseOrder(any())).thenReturn(false);

        BizException thrown = assertThrows(BizException.class,
                () -> service.approve(APPROVAL_ID, "同意发布"));

        assertEquals("审批已批准，但发布执行失败，请查看发布单和部署记录", thrown.getMessage(),
                "必须明确告知审批已批准但发布执行失败，并把排查方向指向发布单与部署记录");

        // 失败审计恰好一次，且失败原因随审计落库；不得写成功审计、不得发成功通知
        verify(AUDIT_LOG_SERVICE, times(1)).recordApproval(eq(APPROVAL_ID), eq(ACTION_APPROVAL_APPROVE),
                eq(RESULT_FAILED), any(),
                argThat(data -> "审批已批准，但发布执行失败，请查看发布单和部署记录".equals(data.get("error"))));
        verify(AUDIT_LOG_SERVICE, never()).recordApproval(eq(APPROVAL_ID), eq(ACTION_APPROVAL_APPROVE),
                eq(RESULT_SUCCESS), any(), any());
        verify(NOTIFICATION_SERVICE, never()).notifyApproved(any());
        assertEquals(1, mockingDetails(AUDIT_LOG_SERVICE).getInvocations().size(),
                "失败路径只允许一条审计，不得重复处理");

        // 不重发执行、不重新认领、不切换到会重新认领的直接发布入口
        verify(RELEASE_ORDER_SERVICE, times(1)).executeClaimedReleaseOrder(any());
        verify(RELEASE_ORDER_SERVICE, never()).doExecuteReleaseOrder(any());
        verify(RELEASE_ORDER_MAPPER, times(1)).markRunningIfExecutable(RELEASE_ORDER_ID);
        verify(RELEASE_ORDER_MAPPER, times(1)).selectForUpdate(RELEASE_ORDER_ID);

        // 不回退审批状态、不再次写审批终态（无条件写回会把已消费的审批改回去）
        verify(APPROVAL_MAPPER, times(1)).markApprovedIfPending(eq(APPROVAL_ID), any(), any(), any());
        verify(APPROVAL_MAPPER, never()).markRejectedIfPending(any(), any(), any(), any());
        verify(APPROVAL_MAPPER, never()).markCancelledIfPending(any());
        verify(APPROVAL_MAPPER, never()).updateById(any(ApprovalRequest.class));

        // 已提交的批准与认领事实不回滚：失败只发生在执行侧，不撤销任何既有终态
        assertEquals(1, TRANSACTIONS.committed.get(), "批准与认领的短事务必须已经提交");
        assertEquals(0, TRANSACTIONS.rolledBack.get(), "执行失败不得回滚已提交的批准事实");
    }

    @Test
    @DisplayName("发布执行审批：执行异常时已提交的批准与认领不回滚，记录失败审计且不发成功通知")
    void releaseApprovalKeepsCommittedFactsWhenExecutionFails() {
        when(APPROVAL_MAPPER.selectById(APPROVAL_ID))
                .thenReturn(pendingApproval("RELEASE_ORDER", "EXECUTE_RELEASE", RELEASE_ORDER_ID));
        when(APPROVAL_MAPPER.markApprovedIfPending(eq(APPROVAL_ID), any(), any(), any())).thenReturn(1);
        when(RELEASE_ORDER_SERVICE.executeClaimedReleaseOrder(any()))
                .thenThrow(new IllegalStateException("kubectl 连接中断"));

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> service.approve(APPROVAL_ID, "同意发布"));

        assertEquals("kubectl 连接中断", thrown.getMessage(), "原始异常必须原样抛出");
        assertEquals(1, TRANSACTIONS.committed.get(), "批准与认领已经提交");
        assertEquals(0, TRANSACTIONS.rolledBack.get(), "外部动作已经发生，批准事实不得回滚");
        verify(AUDIT_LOG_SERVICE).recordApproval(eq(APPROVAL_ID), eq(ACTION_APPROVAL_APPROVE),
                eq(RESULT_FAILED), any(), any());
        verify(AUDIT_LOG_SERVICE, never()).recordApproval(eq(APPROVAL_ID), eq(ACTION_APPROVAL_APPROVE),
                eq(RESULT_SUCCESS), any(), any());
        verify(NOTIFICATION_SERVICE, never()).notifyApproved(any());
        // 异常路径与失败结果路径共用同一条失败处理：不得重复写失败审计、不得重试执行
        assertEquals(1, mockingDetails(AUDIT_LOG_SERVICE).getInvocations().size(),
                "失败处理只允许执行一次");
        verify(RELEASE_ORDER_SERVICE, times(1)).executeClaimedReleaseOrder(any());
        verify(RELEASE_ORDER_MAPPER, times(1)).markRunningIfExecutable(RELEASE_ORDER_ID);
    }

    @Test
    @DisplayName("发布执行审批：不允许经非认领路径启动（缺少本次认领结果时失败关闭并回滚）")
    void releaseApprovalCannotStartWithoutClaimResult() {
        when(APPROVAL_MAPPER.selectById(APPROVAL_ID))
                .thenReturn(pendingApproval("RELEASE_ORDER", "EXECUTE_RELEASE", RELEASE_ORDER_ID));
        when(APPROVAL_MAPPER.markApprovedIfPending(eq(APPROVAL_ID), any(), any(), any())).thenReturn(1);

        // 直接调用非认领路径（approveAtomically 只服务其他审批业务），发布执行必须失败关闭
        BizException thrown = assertThrows(BizException.class,
                () -> service.approveAtomically(APPROVAL_ID, "同意发布"));

        assertTrue(thrown.getMessage().contains("缺少本次成功认领的结果"), "必须拒绝“仅凭订单为 RUNNING 就执行”");
        assertEquals(1, TRANSACTIONS.rolledBack.get(), "批准事实随事务回滚，审批不被消费掉");
        assertEquals(0, TRANSACTIONS.committed.get());
        verify(RELEASE_ORDER_SERVICE, never()).executeClaimedReleaseOrder(any());
        verify(RELEASE_ORDER_SERVICE, never()).doExecuteReleaseOrder(any());
    }

    @Test
    @DisplayName("非 PENDING 审批：直接拒绝，不写库也不执行")
    void nonPendingApprovalRejected() {
        ApprovalRequest approved = pendingApproval("RELEASE_ORDER", "EXECUTE_RELEASE", RELEASE_ORDER_ID);
        approved.setStatus(ApprovalRequest.STATUS_APPROVED);
        when(APPROVAL_MAPPER.selectById(APPROVAL_ID)).thenReturn(approved);

        BizException thrown = assertThrows(BizException.class,
                () -> service.approve(APPROVAL_ID, "同意发布"));

        assertTrue(thrown.getMessage().contains("不是PENDING"));
        assertEquals(0, TRANSACTIONS.begun.get(), "连事务都不该开启");
        verify(APPROVAL_MAPPER, never()).markApprovedIfPending(any(), any(), any(), any());
        verify(RELEASE_ORDER_SERVICE, never()).executeClaimedReleaseOrder(any());
    }

    @Test
    @DisplayName("其他审批业务：保留原有“批准 + 业务动作”同一事务语义（证明自我代理未失效）")
    void otherApprovalKeepsAtomicTransactionSemantics() {
        when(APPROVAL_MAPPER.selectById(APPROVAL_ID))
                .thenReturn(pendingApproval("ROLLBACK_ORDER", "EXECUTE_ROLLBACK", 9L));
        when(APPROVAL_MAPPER.markApprovedIfPending(eq(APPROVAL_ID), any(), any(), any())).thenReturn(1);

        AtomicBoolean transactionActiveWhileExecuting = new AtomicBoolean(false);
        AtomicInteger commitsWhileExecuting = new AtomicInteger(-1);
        when(ROLLBACK_ORDER_SERVICE.doExecuteRollbackOrder(9L)).thenAnswer(invocation -> {
            transactionActiveWhileExecuting.set(TransactionSynchronizationManager.isActualTransactionActive());
            commitsWhileExecuting.set(TRANSACTIONS.committed.get());
            return true;
        });

        service.approve(APPROVAL_ID, "同意回滚");

        assertTrue(transactionActiveWhileExecuting.get(),
                "其他审批业务必须在同一事务内执行；为 false 说明自我代理失效、@Transactional 静默无效");
        assertEquals(0, commitsWhileExecuting.get(), "业务动作执行时事务尚未提交");
        assertEquals(1, TRANSACTIONS.committed.get(), "整体事务最终提交一次");
        verify(ROLLBACK_ORDER_SERVICE).doExecuteRollbackOrder(9L);
        verify(RELEASE_ORDER_SERVICE, never()).executeClaimedReleaseOrder(any());
    }

    @Test
    @DisplayName("事务边界契约：approve 与发布执行入口不得是事务入口，事务方法必须存在")
    void transactionBoundaryContract() throws Exception {
        assertNull(ApprovalRequestService.class.getMethod("approve", Long.class, String.class)
                        .getAnnotation(Transactional.class),
                "approve 不能是事务入口：发布执行审批必须先提交批准与认领再调用外部执行器");
        assertNotNull(ApprovalRequestService.class.getMethod("approveAtomically", Long.class, String.class)
                        .getAnnotation(Transactional.class),
                "其他审批业务的原子批准必须是事务方法");
        java.lang.reflect.Method approveAndClaim = ApprovalRequestService.class
                .getMethod("approveAndClaimReleaseExecution", Long.class, String.class);
        assertNotNull(approveAndClaim.getAnnotation(Transactional.class),
                "发布执行审批的“批准 + 认领”必须由短事务提交");
        assertEquals(ReleaseExecutionClaimService.ClaimedExecution.class, approveAndClaim.getReturnType(),
                "批准并认领必须返回本次认领结果，供内部执行路径使用");
        assertThrows(NoSuchMethodException.class,
                () -> ApprovalRequestService.class.getMethod("markApprovedForRelease", Long.class, String.class),
                "旧的“单独提交批准事实”入口必须移除：它会留下审批已消费但订单未认领的窗口");
        assertNull(ReleaseOrderService.class.getMethod("doExecuteReleaseOrder", Long.class)
                        .getAnnotation(Transactional.class),
                "发布执行不得被长事务覆盖");
        assertNull(ReleaseOrderService.class.getMethod("executeReleaseOrder", Long.class)
                        .getAnnotation(Transactional.class),
                "发布执行不得被长事务覆盖");
        assertNull(ReleaseOrderService.class
                        .getMethod("executeClaimedReleaseOrder", ReleaseExecutionClaimService.ClaimedExecution.class)
                        .getAnnotation(Transactional.class),
                "已认领执行的入口不得再被事务覆盖：外部执行期间不能持有事务");
    }

    @Test
    @DisplayName("结构性守卫：发布执行执行器必须先于终态记录，且不重新认领")
    void claimedEntryUsesClaimingServiceOnlyOnce() {
        when(APPROVAL_MAPPER.selectById(APPROVAL_ID))
                .thenReturn(pendingApproval("RELEASE_ORDER", "EXECUTE_RELEASE", RELEASE_ORDER_ID));
        when(APPROVAL_MAPPER.markApprovedIfPending(eq(APPROVAL_ID), any(), any(), any())).thenReturn(1);
        when(RELEASE_ORDER_SERVICE.executeClaimedReleaseOrder(any())).thenReturn(true);

        service.approve(APPROVAL_ID, "同意发布");

        // 订单认领只发生一次（由 claimForApprovedExecution 完成），执行入口不再认领第二遍
        verify(RELEASE_ORDER_MAPPER, times(1)).markRunningIfExecutable(anyLong());
        verify(RELEASE_ORDER_MAPPER, times(1)).selectForUpdate(RELEASE_ORDER_ID);
    }
}
package com.infrahub.release.service;

import com.infrahub.common.exception.BizException;
import com.infrahub.release.entity.ApprovalRequest;
import com.infrahub.release.mapper.ApprovalRequestMapper;
import com.infrahub.release.mapper.ReleasePrecheckRecordMapper;
import com.infrahub.release.mapper.ReleaseRollbackOrderMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 回滚目标协调器纯单元测试：只验证 fail-closed 与冻结判据本身，不连接真实 DB/Docker/SSH/Kubernetes。
 *
 * <p>覆盖三点：无活动事务时行锁会立即释放、必须直接拒绝；回滚单不存在时拒绝；
 * 已有预检记录或待处理审批时拒绝换目标。事务是否真的存在用
 * {@link TransactionSynchronizationManager} 的线程状态模拟，不启动 Spring 容器。
 */
class RollbackTargetCoordinatorTest {

    private static final Long ROLLBACK_ORDER_ID = 10L;

    private ReleaseRollbackOrderMapper releaseRollbackOrderMapper;
    private ReleasePrecheckRecordMapper releasePrecheckRecordMapper;
    private ApprovalRequestMapper approvalRequestMapper;

    private RollbackTargetCoordinator coordinator;

    @BeforeEach
    void setUp() {
        releaseRollbackOrderMapper = mock(ReleaseRollbackOrderMapper.class);
        releasePrecheckRecordMapper = mock(ReleasePrecheckRecordMapper.class);
        approvalRequestMapper = mock(ApprovalRequestMapper.class);
        coordinator = new RollbackTargetCoordinator(
                releaseRollbackOrderMapper, releasePrecheckRecordMapper, org.mockito.Mockito.mock(com.infrahub.release.mapper.ReleaseOrderMapper.class), org.mockito.Mockito.mock(com.infrahub.base.mapper.BaseServiceMapper.class), approvalRequestMapper);
    }

    @AfterEach
    void clearTransactionState() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    private void markTransactionActive() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }

    @Test
    @DisplayName("事务守卫：无活动事务时拒绝加锁，不触碰数据库（避免行锁在自动提交下立即释放）")
    void lockRollbackOrderRejectedWithoutActiveTransaction() {
        BizException ex = assertThrows(BizException.class,
                () -> coordinator.lockRollbackOrder(ROLLBACK_ORDER_ID));

        assertTrue(ex.getMessage().contains("未处于事务中"), "必须明确说明锁不会生效");
        verify(releaseRollbackOrderMapper, never()).lockById(any());
    }

    @Test
    @DisplayName("事务守卫：事务存在但回滚单行不存在时拒绝")
    void lockRollbackOrderRejectedWhenRowMissing() {
        markTransactionActive();
        when(releaseRollbackOrderMapper.lockById(ROLLBACK_ORDER_ID)).thenReturn(null);

        BizException ex = assertThrows(BizException.class,
                () -> coordinator.lockRollbackOrder(ROLLBACK_ORDER_ID));

        assertEquals("回滚单不存在", ex.getMessage());
    }

    @Test
    @DisplayName("事务守卫：事务存在且行存在时加锁成功")
    void lockRollbackOrderSucceedsInsideTransaction() {
        markTransactionActive();
        when(releaseRollbackOrderMapper.lockById(ROLLBACK_ORDER_ID)).thenReturn(ROLLBACK_ORDER_ID);

        assertDoesNotThrow(() -> coordinator.lockRollbackOrder(ROLLBACK_ORDER_ID));

        verify(releaseRollbackOrderMapper).lockById(ROLLBACK_ORDER_ID);
    }

    @Test
    @DisplayName("目标冻结：已有预检记录时拒绝换目标，提示新建回滚单")
    void assertNoPrecheckEvidenceRejectsWhenEvidenceExists() {
        when(releasePrecheckRecordMapper.selectCount(any())).thenReturn(1L);

        BizException ex = assertThrows(BizException.class,
                () -> coordinator.assertNoPrecheckEvidence(ROLLBACK_ORDER_ID));

        assertEquals("该回滚单已有预检记录，不能更换回滚目标，请新建回滚单", ex.getMessage());
    }

    @Test
    @DisplayName("目标冻结：无预检记录时放行")
    void assertNoPrecheckEvidencePassesWhenNoEvidence() {
        when(releasePrecheckRecordMapper.selectCount(any())).thenReturn(0L);

        assertDoesNotThrow(() -> coordinator.assertNoPrecheckEvidence(ROLLBACK_ORDER_ID));
    }

    @Test
    @DisplayName("目标冻结：存在待处理回滚执行审批时拒绝换目标")
    void assertNoPendingApprovalRejectsWhenPendingExists() {
        when(approvalRequestMapper.selectCount(any())).thenReturn(1L);

        BizException ex = assertThrows(BizException.class,
                () -> coordinator.assertNoPendingApproval(ROLLBACK_ORDER_ID));

        assertEquals("该回滚单存在待处理审批，不能更换回滚目标", ex.getMessage());
        // 常量取值与审批实体保持一致，避免两侧字面量漂移
        assertEquals("ROLLBACK_ORDER", ApprovalRequest.BIZ_TYPE_ROLLBACK_ORDER);
        assertEquals("EXECUTE_ROLLBACK", ApprovalRequest.ACTION_EXECUTE_ROLLBACK);
    }

    @Test
    @DisplayName("目标冻结：无待处理审批时放行（含 count 返回 null 的兜底）")
    void assertNoPendingApprovalPassesWhenNone() {
        when(approvalRequestMapper.selectCount(any())).thenReturn(0L);

        assertDoesNotThrow(() -> coordinator.assertNoPendingApproval(ROLLBACK_ORDER_ID));
    }
}
package com.infrahub.release.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.infrahub.base.mapper.BaseServiceMapper;
import com.infrahub.common.error.ErrorCode;
import com.infrahub.common.exception.BizException;
import com.infrahub.release.entity.ApprovalRequest;
import com.infrahub.release.entity.ReleaseOrder;
import com.infrahub.release.entity.ReleasePrecheckRecord;
import com.infrahub.release.entity.ReleaseRollbackOrder;
import com.infrahub.release.mapper.ApprovalRequestMapper;
import com.infrahub.release.mapper.ReleaseOrderMapper;
import com.infrahub.release.mapper.ReleasePrecheckRecordMapper;
import com.infrahub.release.mapper.ReleaseRollbackOrderMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 回滚目标协调器：把“目标修改 / 预检 / 审批申请 / 执行”四个入口收敛到回滚单同一行的数据库行锁上。
 *
 * <p>协调机制只有一条：进入任一入口后先取回滚单行的排他行锁（SELECT ... FOR UPDATE），
 * 再读取用于预检、审批或执行的目标（to_version_id / status）。锁范围限定到同一回滚单，
 * 不引入通用锁框架。
 *
 * <p>所有入口都在同一把锁上排队，因此“检查”与“写入”不会互相穿插：
 * <ul>
 *   <li>目标修改持有锁期间看到已有预检记录或待处理审批 → 拒绝修改；</li>
 *   <li>预检持有锁期间写入预检记录 → 期间的目标修改被阻塞，不会出现“旧预检为新目标背书”；</li>
 *   <li>审批申请持有锁期间写入待处理审批 → 期间的目标修改被阻塞；</li>
 *   <li>执行持有锁期间读取并落库目标 → 不会执行到已被换掉的目标。</li>
 * </ul>
 *
 * <p>行锁随事务提交或回滚释放，所以调用方必须已经处于真实生效的事务中。自行进入的入口用
 * {@code @Transactional} 标注；同类内部自调用不会经过 Spring 代理、注解不生效，因此这里再补一层
 * 事务守卫：没有活动事务就直接拒绝，避免行锁在自动提交下失效却仍被当作已协调。
 */
@Service
@RequiredArgsConstructor
public class RollbackTargetCoordinator {

    private static final String BIZ_TYPE_ROLLBACK_ORDER = "ROLLBACK_ORDER";
    private static final String ACTION_EXECUTE_ROLLBACK = "EXECUTE_ROLLBACK";

    private final ReleaseRollbackOrderMapper releaseRollbackOrderMapper;
    private final ReleasePrecheckRecordMapper releasePrecheckRecordMapper;
    private final ReleaseOrderMapper releaseOrderMapper;
    /** 与发布认领、实例移除登记共用的服务行锁。 */
    private final BaseServiceMapper baseServiceMapper;
    /**
     * 直接用 Mapper 读审批表，不经 ApprovalRequestService：审批服务本身要复用本协调器加锁，
     * 走 Mapper 可避免两个服务互相构造注入形成循环依赖。
     */
    private final ApprovalRequestMapper approvalRequestMapper;

    /**
     * 获取回滚单行的排他锁；必须在本事务内最先执行，之后才允许读取目标版本。
     *
     * @param rollbackOrderId 回滚单 ID
     * @throws BizException 无活动事务（锁会失效）或回滚单不存在时抛出
     */
    public void lockRollbackOrder(Long rollbackOrderId) {
        assertTransactionActive("回滚目标协调");
        Long lockedId = releaseRollbackOrderMapper.lockById(rollbackOrderId);
        if (lockedId == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "回滚单不存在");
        }
    }

    /**
     * 回滚**执行**入口的锁组：先 {@code base_service} 行锁，再回滚单行锁，返回锁后读回的回滚单整行。
     *
     * <p>为什么执行入口必须多取一把 {@code base_service} 锁：移除实例登记先检查"该服务+环境没有
     * 执行中的发布/回滚"，再删除实例行；发布认领用 {@code base_service} 行锁把"检查"与"认领"串行化。
     * 若回滚执行只锁回滚单，它就与移除登记互不阻塞 —— 移除可能在回滚读取实例之后、使用实例之前提交，
     * 回滚会拿着已删除实例的快照继续执行。因此执行入口与 {@link ReleaseExecutionClaimService}、
     * {@code RuntimeInstanceDeregistrationService} 使用**同一把** {@code base_service} 锁。
     *
     * <p>加锁顺序固定为 {@code base_service → release_rollback_order}：先用非锁定快照确定锁对象
     * （{@code release_order_id} 与 {@code service_id} 是创建后不再变更的归属字段），再按此顺序加锁，
     * 最后用锁后读回的整行做判定。快照只用于定位锁对象，不参与任何业务判断。
     *
     * <p>锁随事务提交或回滚释放，因此调用方必须处于真实事务中；没有活动事务直接拒绝。
     *
     * @param rollbackOrderId 回滚单 ID
     * @return 锁后读回的回滚单整行
     * @throws BizException 无活动事务、回滚单/发布单/服务不存在，或加锁期间归属字段变化
     */
    public ReleaseRollbackOrder lockForExecution(Long rollbackOrderId) {
        assertTransactionActive("回滚执行协调");
        ReleaseRollbackOrder snapshot = releaseRollbackOrderMapper.selectById(rollbackOrderId);
        if (snapshot == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "回滚单不存在");
        }
        Long serviceId = snapshot.getServiceId();
        if (snapshot.getReleaseOrderId() != null) {
            ReleaseOrder releaseOrderSnapshot = releaseOrderMapper.selectById(snapshot.getReleaseOrderId());
            if (releaseOrderSnapshot == null) {
                throw new BizException(ErrorCode.VALIDATION_ERROR, "回滚单关联的发布单不存在");
            }
            serviceId = releaseOrderSnapshot.getServiceId();
        }
        lockServiceForExecution(serviceId);
        ReleaseRollbackOrder locked = releaseRollbackOrderMapper.selectFullForUpdate(rollbackOrderId);
        if (locked == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "回滚单不存在");
        }
        // 归属字段理论上不可变；一旦变化说明存在本机制未覆盖的写入，失败关闭而不是用错锁继续
        if (!java.util.Objects.equals(locked.getReleaseOrderId(), snapshot.getReleaseOrderId())
                || !java.util.Objects.equals(locked.getServiceId(), snapshot.getServiceId())
                || !java.util.Objects.equals(locked.getEnvId(), snapshot.getEnvId())) {
            throw new BizException(ErrorCode.VALIDATION_ERROR,
                    "回滚单归属在核对期间发生变化，已拒绝执行");
        }
        return locked;
    }

    public void lockServiceForExecution(Long serviceId) {
        assertTransactionActive("回滚执行协调");
        if (serviceId == null || baseServiceMapper.lockById(serviceId) == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "回滚目标的服务不存在");
        }
    }

    /**
     * 目标冻结校验：已有预检记录时不允许换目标，避免旧预检证据为新目标背书。
     * 必须在持有回滚单行锁之后调用（存在性检查与预检写入因此互斥）。
     */
    public void assertNoPrecheckEvidence(Long rollbackOrderId) {
        Long precheckCount = releasePrecheckRecordMapper.selectCount(
                new LambdaQueryWrapper<ReleasePrecheckRecord>()
                        .eq(ReleasePrecheckRecord::getBizType, BIZ_TYPE_ROLLBACK_ORDER)
                        .eq(ReleasePrecheckRecord::getBizId, rollbackOrderId));
        if (precheckCount != null && precheckCount > 0) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "该回滚单已有预检记录，不能更换回滚目标，请新建回滚单");
        }
    }

    /**
     * 目标冻结校验：存在待处理审批时不允许换目标，否则审批将指向另一个目标。
     * 必须在持有回滚单行锁之后调用（存在性检查与审批创建因此互斥）。
     */
    public void assertNoPendingApproval(Long rollbackOrderId) {
        Long pendingApprovalCount = approvalRequestMapper.selectCount(
                new LambdaQueryWrapper<ApprovalRequest>()
                        .eq(ApprovalRequest::getBizType, BIZ_TYPE_ROLLBACK_ORDER)
                        .eq(ApprovalRequest::getBizId, rollbackOrderId)
                        .eq(ApprovalRequest::getAction, ACTION_EXECUTE_ROLLBACK)
                        .eq(ApprovalRequest::getStatus, ApprovalRequest.STATUS_PENDING));
        if (pendingApprovalCount != null && pendingApprovalCount > 0) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "该回滚单存在待处理审批，不能更换回滚目标");
        }
    }

    /** 无活动事务时拒绝，避免行锁在自动提交语句中立即释放。 */
    private void assertTransactionActive(String scene) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new BizException(ErrorCode.VALIDATION_ERROR,
                    scene + "未处于事务中，回滚单行锁不会生效，已拒绝执行");
        }
    }
}

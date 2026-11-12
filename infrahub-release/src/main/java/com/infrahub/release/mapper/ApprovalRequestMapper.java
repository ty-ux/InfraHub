package com.infrahub.release.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.infrahub.release.entity.ApprovalRequest;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.OffsetDateTime;

@Mapper
public interface ApprovalRequestMapper extends BaseMapper<ApprovalRequest> {

    /**
     * 审批消费的条件更新：只有仍处于 PENDING 的审批才能被置为 APPROVED。
     *
     * <p>返回 1 表示本次调用抢到了审批消费权；返回 0 表示审批已被其他请求处理
     * （并发批准、并发拒绝或已取消），调用方必须拒绝继续执行业务动作。
     * 这样“同一审批并发批准”最多只有一次请求能启动外部动作，且不依赖 JVM 内锁。
     */
    @Update("""
            UPDATE approval_request
            SET status = 'APPROVED',
                approver_id = #{approverId},
                approve_note = #{approveNote},
                approved_at = #{approvedAt}
            WHERE id = #{id}
              AND status = 'PENDING'
            """)
    int markApprovedIfPending(@Param("id") Long id,
                              @Param("approverId") Long approverId,
                              @Param("approveNote") String approveNote,
                              @Param("approvedAt") OffsetDateTime approvedAt);

    /**
     * 审批拒绝的条件状态转换：只有仍处于 PENDING 的审批才能被置为 REJECTED。
     *
     * <p>返回 1 表示本次调用抢到了状态转换权；返回 0 表示审批已被其他请求处理
     * （并发批准、并发取消或已终结），调用方必须受控失败，并且不得生成成功拒绝审计或通知。
     *
     * <p>与 {@link #markApprovedIfPending} 使用同一个 {@code WHERE status = 'PENDING'} 条件，
     * 因此并发 approve / reject / cancel 之间最多只有一个能拿到 1 行受影响：
     * 批准一旦被消费就不会再被拒绝或取消覆盖。
     */
    @Update("""
            UPDATE approval_request
            SET status = 'REJECTED',
                approver_id = #{approverId},
                approve_note = #{approveNote},
                approved_at = #{approvedAt}
            WHERE id = #{id}
              AND status = 'PENDING'
            """)
    int markRejectedIfPending(@Param("id") Long id,
                              @Param("approverId") Long approverId,
                              @Param("approveNote") String approveNote,
                              @Param("approvedAt") OffsetDateTime approvedAt);

    /**
     * 审批取消的条件状态转换：只有仍处于 PENDING 的审批才能被置为 CANCELLED。
     *
     * <p>字段与拒绝/批准不同：取消只改状态，不写 approver_id / approve_note / approved_at，
     * 保持既有取消语义不变（取消是申请人动作，不是审批动作）。
     *
     * <p>返回 1 表示本次调用抢到了状态转换权；返回 0 表示审批已被其他请求处理，
     * 调用方必须受控失败，并且不得生成成功取消审计或通知。
     */
    @Update("""
            UPDATE approval_request
            SET status = 'CANCELLED'
            WHERE id = #{id}
              AND status = 'PENDING'
            """)
    int markCancelledIfPending(@Param("id") Long id);
}
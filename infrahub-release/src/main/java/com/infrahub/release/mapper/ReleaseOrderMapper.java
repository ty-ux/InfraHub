package com.infrahub.release.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.infrahub.release.entity.ReleaseOrder;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ReleaseOrderMapper extends BaseMapper<ReleaseOrder> {

    @Select("""
            SELECT COUNT(1)
            FROM release_order
            WHERE service_id = #{serviceId}
              AND env_id = #{envId}
              AND status = 'RUNNING'
            """)
    long countRunningByServiceAndEnv(@Param("serviceId") Long serviceId, @Param("envId") Long envId);

    /**
     * 排除当前发布单后，同一服务/环境是否还有别的执行中发布单。
     *
     * <p>金丝雀恢复批次时当前订单本身就是 RUNNING，不能再用 {@link #countRunningByServiceAndEnv}
     * 把"自己"当成冲突；但同一服务/环境下另一张 RUNNING 订单仍必须拒绝。
     */
    @Select("""
            SELECT COUNT(1)
            FROM release_order
            WHERE service_id = #{serviceId}
              AND env_id = #{envId}
              AND status = 'RUNNING'
              AND id <> #{excludedId}
            """)
    long countRunningByServiceAndEnvExcludingId(@Param("serviceId") Long serviceId,
                                                @Param("envId") Long envId,
                                                @Param("excludedId") Long excludedId);

    /**
     * 该发布单是否存在“活动灰度计划”。用于阻止 CANARY 订单绕过批次计划走直接发布 / 审批整批执行 /
     * 流水线部署步骤。只统计仍未收口的计划状态；已 FAILED / ROLLED_BACK / SUCCESS 的计划不再拦截
     * （此时订单自身状态也已不可执行）。
     */
    @Select("""
            SELECT COUNT(1)
            FROM release_canary_plan
            WHERE release_order_id = #{releaseOrderId}
              AND status IN ('CREATED', 'PAUSED', 'RUNNING', 'BLOCKED')
            """)
    long countActiveCanaryPlanByReleaseOrderId(@Param("releaseOrderId") Long releaseOrderId);

    @Update("""
            UPDATE release_order
            SET status = 'RUNNING',
                updated_at = NOW()
            WHERE id = #{id}
              AND status IN ('CREATED', 'APPROVED')
            """)
    int markRunningIfExecutable(@Param("id") Long id);

    @Update("""
            UPDATE release_order
            SET status = #{status},
                updated_at = NOW()
            WHERE id = #{id}
            """)
    int updateStatus(@Param("id") Long id, @Param("status") String status);

    /** 只允许把仍在执行的发布单收敛为 SUCCESS，避免覆盖并发回滚写下的终态。 */
    @Update("""
            UPDATE release_order
            SET status = 'SUCCESS',
                updated_at = NOW()
            WHERE id = #{id}
              AND status = 'RUNNING'
            """)
    int markSuccessIfRunning(@Param("id") Long id);

    /** 只允许把仍在执行的发布单收敛为 FAILED，避免覆盖并发回滚写下的终态。 */
    @Update("""
            UPDATE release_order
            SET status = 'FAILED',
                updated_at = NOW()
            WHERE id = #{id}
              AND status = 'RUNNING'
            """)
    int markFailedIfRunning(@Param("id") Long id);

    /**
     * 只改写回滚状态列，且在期望值仍成立时才写入。
     *
     * <p>人工恢复回滚单时用整行 {@code updateById} 回写发布单，会把并发写下的其它列
     * （例如流水线终态、当前版本）一起写回旧快照；这里只允许动 rollback_status 一列。
     *
     * @return 1 表示写入生效；0 表示发布单的回滚状态已被其它流程改写，本次不覆盖
     */
    @Update("""
            UPDATE release_order
            SET rollback_status = #{rollbackStatus},
                updated_at = NOW()
            WHERE id = #{id}
              AND rollback_status = #{expectedRollbackStatus}
            """)
    int markRollbackStatusIfExpected(@Param("id") Long id,
                                     @Param("rollbackStatus") String rollbackStatus,
                                     @Param("expectedRollbackStatus") String expectedRollbackStatus);

    /**
     * 发布认领的发布单行锁，并在加锁的同时取回完整订单行（SELECT ... FOR UPDATE）。
     *
     * <p>锁顺序固定为 base_service(serviceId) → release_order(id)，本方法只做第二步，
     * 必须在取到服务行锁之后调用。所有入口（直接发布 / 审批回调 / 流水线触发 / 执行审批创建）
     * 都按同一顺序加锁，避免相互等待形成环路。
     *
     * <p>认领判定必须使用本方法返回的对象：这是“加锁之后”的当前事实，而不是加锁前的快照。
     * 不要用再加一次 {@code selectById} 代替本方法——同一事务内的 MyBatis 一级缓存（SESSION 级）
     * 以 Statement + 参数为键，加锁前的 {@code selectById} 已经把旧对象放进了缓存，
     * 再加锁后再 {@code selectById} 会命中缓存、拿回加锁前的旧状态，于是“已被其他事务改成
     * RUNNING / SUCCESS / FAILED 的发布单”会被判成可执行。
     *
     * <p>缓存行为在这里是显式处理的：{@code flushCache = FlushCachePolicy.TRUE} 在锁定读之前清掉
     * 本 SqlSession 一级缓存里快照读留下的旧条目（含 {@code selectById} 写进去的那份），
     * {@code useCache = false} 保证锁定读永远落到数据库、也不把结果放进缓存供后续同参数读命中。
     * 全局关闭一级缓存影响面更大，不采用。
     *
     * <p>必须在真实生效的事务内调用；调用方（ReleaseExecutionClaimService）会先做事务守卫。
     * 返回 null 表示发布单行不存在。
     */
    @Select("SELECT * FROM release_order WHERE id = #{id} FOR UPDATE")
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    ReleaseOrder selectForUpdate(@Param("id") Long id);
}

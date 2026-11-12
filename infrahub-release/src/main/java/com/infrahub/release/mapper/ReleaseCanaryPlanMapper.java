package com.infrahub.release.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.infrahub.release.entity.ReleaseCanaryPlan;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ReleaseCanaryPlanMapper extends BaseMapper<ReleaseCanaryPlan> {

    @Select("""
            SELECT COUNT(1)
            FROM release_canary_plan
            WHERE release_order_id = #{releaseOrderId}
              AND status IN ('CREATED', 'RUNNING', 'PAUSED', 'BLOCKED')
            """)
    long countActiveByReleaseOrderId(@Param("releaseOrderId") Long releaseOrderId);

    @Select("""
            SELECT *
            FROM release_canary_plan
            WHERE release_order_id = #{releaseOrderId}
            """)
    ReleaseCanaryPlan findByReleaseOrderId(@Param("releaseOrderId") Long releaseOrderId);

    @Update("""
            UPDATE release_canary_plan
            SET status = #{status},
                updated_at = NOW()
            WHERE id = #{id}
            """)
    int updateStatus(@Param("id") Long id, @Param("status") String status);

    /**
     * 灰度计划行锁：锁后读取计划的当前状态，供批次认领与暂停在数据库层串行化。
     *
     * <p>先前的快照读会把旧行放进 MyBatis 一级缓存，因此这里显式清缓存并绕过缓存，
     * 保证拿到的是加锁后的当前行。必须在真实事务内调用。
     */
    @Select("SELECT * FROM release_canary_plan WHERE release_order_id = #{releaseOrderId} FOR UPDATE")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    ReleaseCanaryPlan selectByReleaseOrderIdForUpdate(@Param("releaseOrderId") Long releaseOrderId);

    @Select("SELECT * FROM release_canary_plan WHERE id = #{id} FOR UPDATE")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    ReleaseCanaryPlan selectByIdForUpdate(@Param("id") Long id);

    /**
     * 启动/恢复计划的**认领凭证**：只有把 CREATED 或 PAUSED 条件更新成 RUNNING 的那个请求
     * 才拿到受影响行数 1，其余并发请求拿到 0 并被拒绝。不能只靠"计划当前是 CREATED"做判断。
     */
    @Update("""
            UPDATE release_canary_plan
            SET status = 'RUNNING',
                updated_at = NOW()
            WHERE id = #{id}
              AND status IN ('CREATED', 'PAUSED')
            """)
    int markRunningIfStartable(@Param("id") Long id);

    @Update("""
            UPDATE release_canary_plan
            SET status = 'PAUSED',
                updated_at = NOW()
            WHERE id = #{id}
              AND status = 'RUNNING'
            """)
    int markPausedIfRunning(@Param("id") Long id);

    /**
     * 自动完成只允许从 RUNNING 出发：执行期间用户若已暂停，这里必须拿不到行，
     * 由用户显式恢复后才完成计划，避免"暂停被一次批次成功覆盖"。
     */
    @Update("""
            UPDATE release_canary_plan
            SET status = 'SUCCESS',
                updated_at = NOW()
            WHERE id = #{id}
              AND status = 'RUNNING'
            """)
    int markSuccessIfRunning(@Param("id") Long id);

    /** 显式恢复/继续时允许把 PAUSED 收敛为 SUCCESS（已无待执行批次）。 */
    @Update("""
            UPDATE release_canary_plan
            SET status = 'SUCCESS',
                updated_at = NOW()
            WHERE id = #{id}
              AND status IN ('RUNNING', 'PAUSED')
            """)
    int markSuccessIfRunningOrPaused(@Param("id") Long id);

    @Update("""
            UPDATE release_canary_plan
            SET status = 'BLOCKED',
                updated_at = NOW()
            WHERE id = #{id}
              AND status IN ('RUNNING', 'PAUSED')
            """)
    int markBlockedIfActive(@Param("id") Long id);

    @Update("""
            UPDATE release_canary_plan
            SET status = 'FAILED',
                updated_at = NOW()
            WHERE id = #{id}
              AND status IN ('RUNNING', 'PAUSED', 'BLOCKED')
            """)
    int markFailedIfActive(@Param("id") Long id);

    @Update("""
            UPDATE release_canary_plan
            SET status = 'ROLLED_BACK',
                updated_at = NOW()
            WHERE id = #{id}
              AND status IN ('RUNNING', 'PAUSED', 'BLOCKED', 'FAILED')
            """)
    int markRolledBackIfActive(@Param("id") Long id);

    /** 质量门禁重新评估通过后，只允许把 BLOCKED 恢复成 RUNNING。 */
    @Update("""
            UPDATE release_canary_plan
            SET status = 'RUNNING',
                updated_at = NOW()
            WHERE id = #{id}
              AND status = 'BLOCKED'
            """)
    int markRunningIfBlocked(@Param("id") Long id);

    /** 自动回滚单只允许绑定一次：并发失败处理不会建出第二张回滚单。 */
    @Update("""
            UPDATE release_canary_plan
            SET rollback_order_id = #{rollbackOrderId},
                updated_at = NOW()
            WHERE id = #{id}
              AND rollback_order_id IS NULL
            """)
    int setRollbackOrderIdIfNull(@Param("id") Long id, @Param("rollbackOrderId") Long rollbackOrderId);

    @Update("""
            UPDATE release_canary_plan
            SET failure_reason = #{failureReason},
                updated_at = NOW()
            WHERE id = #{id}
            """)
    int updateFailureReason(@Param("id") Long id, @Param("failureReason") String failureReason);
}
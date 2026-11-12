package com.infrahub.release.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.infrahub.release.entity.ReleaseRollbackOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ReleaseRollbackOrderMapper extends BaseMapper<ReleaseRollbackOrder> {

    @Select("""
            SELECT COUNT(1)
            FROM release_rollback_order rro
            LEFT JOIN release_order ro ON ro.id = rro.release_order_id
            WHERE COALESCE(rro.service_id, ro.service_id) = #{serviceId}
              AND COALESCE(rro.env_id, ro.env_id) = #{envId}
              AND rro.status = 'RUNNING'
            """)
    long countRunningByServiceAndEnv(Long serviceId, Long envId);

    /**
     * 回滚目标协调锁：对回滚单行加排他行锁（SELECT ... FOR UPDATE），
     * 使“目标修改 / 预检 / 审批申请 / 执行”四个入口在数据库层互斥。
     *
     * <p>必须在真实生效的事务内调用：行锁随事务提交或回滚释放。若当前没有活动事务，
     * 语句会走自动提交并立即释放锁，起不到协调作用，因此调用方（RollbackTargetCoordinator）
     * 会先做事务守卫，缺少事务时直接拒绝，避免出现“看似加锁、实际未加锁”。
     *
     * <p>返回 1 表示锁定成功；返回 null 表示回滚单行不存在，由调用方按“回滚单不存在”处理。
     */
    @Select("SELECT id FROM release_rollback_order WHERE id = #{id} FOR UPDATE")
    Long lockById(@Param("id") Long id);

    /**
     * 锁定并读回回滚单整行（{@code SELECT * ... FOR UPDATE}）。
     *
     * <p>与 {@link #lockById} 的区别只在读取内容：执行入口需要状态与目标版本，且必须拿到**锁后**
     * 的值。先前的快照读会把旧行放进 MyBatis 一级缓存，因此这里显式
     * {@code flushCache = true} + {@code useCache = false}，避免"锁拿到了、读到的却是锁前状态"。
     *
     * <p>必须在真实生效的事务内调用。
     *
     * @return 回滚单整行；行不存在时返回 null
     */
    @Select("SELECT * FROM release_rollback_order WHERE id = #{id} FOR UPDATE")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    ReleaseRollbackOrder selectFullForUpdate(@Param("id") Long id);

    /**
     * 只允许把仍在执行的回滚单收敛为 FAILED。
     *
     * <p>人工恢复回滚单时用整行 {@code updateById} 回写，会把并发写下的其它列一起写回旧快照；
     * 条件更新同时保证“已经收口为 SUCCEEDED / FAILED 的回滚单不会被再次改写”。
     *
     * @return 1 表示写入生效；0 表示回滚单不在 RUNNING，调用方必须如实失败而不是继续
     */
    @Update("""
            UPDATE release_rollback_order
            SET status = 'FAILED'
            WHERE id = #{id}
              AND status = 'RUNNING'
            """)
    int markFailedIfRunning(@Param("id") Long id);
}

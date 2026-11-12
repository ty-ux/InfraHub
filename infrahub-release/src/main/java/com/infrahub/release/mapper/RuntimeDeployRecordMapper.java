package com.infrahub.release.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.infrahub.release.entity.RuntimeDeployRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface RuntimeDeployRecordMapper extends BaseMapper<RuntimeDeployRecord> {

    @Select("SELECT * FROM runtime_deploy_record WHERE id = #{id} FOR UPDATE")
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    RuntimeDeployRecord selectByIdForUpdate(@Param("id") Long id);

    /**
     * 读取单条记录的当前状态。
     *
     * <p>必须绕过 MyBatis 一级缓存：同一事务里先前读到的 RUNNING 快照会命中缓存，
     * 让“条件更新没生效后重新确认状态”永远读到旧值。加锁读取（FOR UPDATE）则会在恢复路径上
     * 多拿一把记录行锁，这里只需要一个当前值，因此用 {@code flushCache} 而不是 {@code FOR UPDATE}。
     */
    @Select("SELECT deploy_status FROM runtime_deploy_record WHERE id = #{id}")
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    String selectStatusById(@Param("id") Long id);

    @Select("""
            SELECT *
            FROM runtime_deploy_record
            WHERE release_order_id = #{releaseOrderId}
              AND deploy_status = 'RUNNING'
            ORDER BY id
            """)
    List<RuntimeDeployRecord> listRunningByReleaseOrderId(@Param("releaseOrderId") Long releaseOrderId);

    @Select("""
            SELECT *
            FROM runtime_deploy_record
            WHERE rollback_order_id = #{rollbackOrderId}
              AND deploy_status = 'RUNNING'
            ORDER BY id
            """)
    List<RuntimeDeployRecord> listRunningByRollbackOrderId(@Param("rollbackOrderId") Long rollbackOrderId);

    @Select("""
            SELECT *
            FROM runtime_deploy_record
            WHERE release_order_id = #{releaseOrderId}
            ORDER BY id
            """)
    List<RuntimeDeployRecord> listByReleaseOrderId(@Param("releaseOrderId") Long releaseOrderId);

    @Select("""
            SELECT *
            FROM runtime_deploy_record
            WHERE rollback_order_id = #{rollbackOrderId}
            ORDER BY id
            """)
    List<RuntimeDeployRecord> listByRollbackOrderId(@Param("rollbackOrderId") Long rollbackOrderId);

    /**
     * 人工恢复：只把仍为 RUNNING 的记录改成 UNKNOWN，绝不覆盖已经写入的终态。
     * 追加日志而不是覆盖，保留原有证据。
     */
    @Update("""
            UPDATE runtime_deploy_record
            SET deploy_status = 'UNKNOWN',
                ended_at = COALESCE(ended_at, NOW()),
                deploy_log = CASE
                    WHEN deploy_log IS NULL OR deploy_log = '' THEN #{append}
                    WHEN POSITION(#{append} IN deploy_log) > 0 THEN deploy_log
                    ELSE deploy_log || E'\\n\\n' || #{append}
                END
            WHERE id = #{id}
              AND deploy_status = 'RUNNING'
            """)
    int markUnknownIfRunning(@Param("id") Long id, @Param("append") String append);

    /**
     * 失败终态兜底：只把仍为 RUNNING 的记录改成 FAILED，同样追加日志而不是覆盖。
     * 并发写入的 UNKNOWN（以及其后的人工核对证据）不会被这次失败改写。
     */
    @Update("""
            UPDATE runtime_deploy_record
            SET deploy_status = 'FAILED',
                ended_at = COALESCE(ended_at, NOW()),
                deploy_log = CASE
                    WHEN deploy_log IS NULL OR deploy_log = '' THEN #{append}
                    WHEN POSITION(#{append} IN deploy_log) > 0 THEN deploy_log
                    ELSE deploy_log || E'\\n\\n' || #{append}
                END
            WHERE id = #{id}
              AND deploy_status = 'RUNNING'
            """)
    int markFailedIfRunning(@Param("id") Long id, @Param("append") String append);

    @Select("""
            SELECT COUNT(1)
            FROM runtime_deploy_record
            WHERE release_order_id = #{releaseOrderId}
              AND deploy_status = #{status}
            """)
    long countByReleaseOrderIdAndStatus(@Param("releaseOrderId") Long releaseOrderId,
                                        @Param("status") String status);

    /**
     * 把**刚刚创建的这一条**部署记录绑定到产生它的执行认领。
     *
     * <p>按记录 ID 精确绑定，不做“按发布单回填”的批量归属：发布单下可能有历史记录、回滚记录，
     * 也可能有凭据是订单/批次条件更新的另一次执行；按发布单批量补写会把它们错误地归给本次认领。
     * 条件里的 {@code IS NULL} 保证不会改写已经归属于别的认领的记录。
     *
     * <p>调用方必须保证 {@code execution_claim_id} 列存在（即认领行来自
     * {@code release_execution_run_claim}，V32 已应用）；没有认领的路径不应调用本方法。
     *
     * @return 1 表示绑定生效；0 表示记录不存在或已经归属于其它认领
     */
    @Update("""
            UPDATE runtime_deploy_record
            SET execution_claim_id = #{claimId}
            WHERE id = #{recordId}
              AND execution_claim_id IS NULL
            """)
    int bindRecordToExecutionClaim(@Param("recordId") Long recordId, @Param("claimId") Long claimId);

    /**
     * 某次执行认领产出的全部部署记录。
     *
     * <p>这是“哪些部署记录确实由这次执行产生”的唯一查询口径：认领身份在记录创建时写入，
     * 之后不再回填，因此结果集不会随时间变化。
     *
     * <p>引用 {@code execution_claim_id} 列，只有存在认领行（V32 已应用）时才可调用。
     */
    @Select("""
            SELECT *
            FROM runtime_deploy_record
            WHERE execution_claim_id = #{claimId}
            ORDER BY id
            """)
    List<RuntimeDeployRecord> listByExecutionClaimId(@Param("claimId") Long claimId);

    /** 某次执行认领产出、且仍停在 RUNNING 的部署记录。用于未知结果收口。 */
    @Select("""
            SELECT *
            FROM runtime_deploy_record
            WHERE execution_claim_id = #{claimId}
              AND deploy_status = 'RUNNING'
            ORDER BY id
            """)
    List<RuntimeDeployRecord> listRunningByExecutionClaimId(@Param("claimId") Long claimId);

    /** 某次执行认领产出的、指定状态的记录数。引用 {@code execution_claim_id} 列，同上。 */
    @Select("""
            SELECT COUNT(1)
            FROM runtime_deploy_record
            WHERE execution_claim_id = #{claimId}
              AND deploy_status = #{status}
            """)
    long countByExecutionClaimIdAndStatus(@Param("claimId") Long claimId, @Param("status") String status);
}

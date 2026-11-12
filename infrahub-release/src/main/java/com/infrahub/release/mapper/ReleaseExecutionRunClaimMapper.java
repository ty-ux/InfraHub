package com.infrahub.release.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.infrahub.release.entity.ReleaseExecutionRunClaim;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.OffsetDateTime;
import java.util.List;

@Mapper
public interface ReleaseExecutionRunClaimMapper extends BaseMapper<ReleaseExecutionRunClaim> {

    /**
     * 表是否存在探针。与 {@code runtime_execution_unknown} 同一写法：用 {@code to_regclass}
     * 而不是直接 SELECT，表不存在时只返回 null，不会把当前事务标记为 aborted。
     */
    @Select("SELECT to_regclass('release_execution_run_claim')::text")
    String probe();

    /**
     * 写下一行 HELD 认领。
     *
     * <p>唯一索引 {@code uk_release_execution_run_claim_identity} 是真正的守卫：
     * {@code ON CONFLICT DO NOTHING} 在身份重复时返回 0，调用方据此拒绝执行。
     * 同一运行的重复步骤、重复投递与并发运行都会在这里拿到 0。
     *
     * @return 1 表示本次写入生效（认领成功）；0 表示该执行身份已被占用或已被消费
     */
    @Insert("""
            INSERT INTO release_execution_run_claim(
                release_order_id, service_id, env_id, claim_purpose, holder_kind, holder_token,
                pipeline_run_id, pipeline_step_run_id, rollback_order_id,
                status, claimed_at, updated_at
            ) VALUES (
                #{releaseOrderId}, #{serviceId}, #{envId}, #{claimPurpose}, #{holderKind}, #{holderToken},
                #{pipelineRunId}, #{pipelineStepRunId}, #{rollbackOrderId},
                'HELD', #{claimedAt}, NOW()
            )
            ON CONFLICT DO NOTHING
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insertHeld(ReleaseExecutionRunClaim row);

    /**
     * 按执行身份读回认领行，用于区分“别人正在执行（HELD）”与“这次执行已经被消费过（RELEASED）”。
     * 只读，不参与判定；判定一律用写入结果（受影响行数）。
     */
    @Select("""
            <script>
            SELECT *
            FROM release_execution_run_claim
            WHERE release_order_id = #{releaseOrderId}
              AND claim_purpose = #{claimPurpose}
            <choose>
                <when test="rollbackOrderId != null">
                    AND rollback_order_id = #{rollbackOrderId}
                </when>
                <otherwise>
                    AND rollback_order_id IS NULL
                </otherwise>
            </choose>
            </script>
            """)
    ReleaseExecutionRunClaim selectByIdentity(@Param("releaseOrderId") Long releaseOrderId,
                                              @Param("claimPurpose") String claimPurpose,
                                              @Param("rollbackOrderId") Long rollbackOrderId);

    @Select("SELECT * FROM release_execution_run_claim WHERE id = #{id}")
    ReleaseExecutionRunClaim selectClaimById(@Param("id") Long id);

    @Select("SELECT * FROM release_execution_run_claim WHERE id = #{id} FOR UPDATE")
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    ReleaseExecutionRunClaim selectByIdForUpdate(@Param("id") Long id);

    @Select("""
            SELECT *
            FROM release_execution_run_claim
            WHERE release_order_id = #{releaseOrderId}
            ORDER BY id DESC
            """)
    List<ReleaseExecutionRunClaim> listByReleaseOrderId(@Param("releaseOrderId") Long releaseOrderId);

    /**
     * 同一服务/环境下仍处于 HELD 的部署认领 ID（升序）。
     *
     * <p>HELD 表示“平台认为这次执行仍在飞”。它是一份持久的阻断事实：即使发布单已经被
     * 终态收敛为 FAILED（例如流水线回调按失败收敛），只要这次执行没有被持有者正常释放、
     * 也没有被操作人显式放弃，同一服务/环境就不允许开始新的冲突动作。
     *
     * <p>只返回 ID：阻断消息只需要让操作人能定位到认领行，不暴露持有者令牌。
     */
    @Select("""
            SELECT id
            FROM release_execution_run_claim
            WHERE service_id = #{serviceId}
              AND env_id = #{envId}
              AND status = 'HELD'
            ORDER BY id
            """)
    List<Long> listHeldIdsByServiceAndEnv(@Param("serviceId") Long serviceId, @Param("envId") Long envId);

    /**
     * 持有者自己释放认领：必须同时匹配行 ID、持有者令牌与 HELD 状态。
     *
     * <p>令牌让“释放”只能由写下沉这行的那个执行线程发起：重复释放返回 0，
     * 拿错行的调用方也改不动别人的认领。
     */
    @Update("""
            UPDATE release_execution_run_claim
            SET status = 'RELEASED',
                release_outcome = #{outcome},
                release_reason = #{reason},
                released_by = #{releasedBy},
                released_at = #{releasedAt},
                updated_at = NOW()
            WHERE id = #{id}
              AND holder_token = #{holderToken}
              AND status = 'HELD'
            """)
    int markReleasedIfHeld(@Param("id") Long id,
                           @Param("holderToken") String holderToken,
                           @Param("outcome") String outcome,
                           @Param("reason") String reason,
                           @Param("releasedBy") Long releasedBy,
                           @Param("releasedAt") OffsetDateTime releasedAt);

    /**
     * 操作人放弃认领（进程崩溃后没有任何线程会再释放它）。
     *
     * <p>只按行 ID 与 HELD 条件更新：令牌已经随进程消失，这里用 {@code released_by} 与
     * {@code release_reason} 记录是谁、因为什么放弃的。它**不**声明远端已经停止。
     */
    @Update("""
            UPDATE release_execution_run_claim
            SET status = 'RELEASED',
                release_outcome = 'ABANDONED',
                release_reason = #{reason},
                released_by = #{releasedBy},
                released_at = #{releasedAt},
                updated_at = NOW()
            WHERE id = #{id}
              AND status = 'HELD'
            """)
    int markAbandonedIfHeld(@Param("id") Long id,
                            @Param("reason") String reason,
                            @Param("releasedBy") Long releasedBy,
                            @Param("releasedAt") OffsetDateTime releasedAt);
}

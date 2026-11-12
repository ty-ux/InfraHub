package com.infrahub.release.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.infrahub.release.entity.ReleaseCanaryBatch;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface ReleaseCanaryBatchMapper extends BaseMapper<ReleaseCanaryBatch> {

    @Select("""
            SELECT *
            FROM release_canary_batch
            WHERE plan_id = #{planId}
            ORDER BY batch_no ASC
            """)
    List<ReleaseCanaryBatch> findByPlanId(@Param("planId") Long planId);

    @Select("""
            SELECT *
            FROM release_canary_batch
            WHERE plan_id = #{planId}
              AND status = #{status}
            ORDER BY batch_no ASC
            LIMIT 1
            """)
    ReleaseCanaryBatch findFirstByPlanIdAndStatus(@Param("planId") Long planId, @Param("status") String status);

    @Update("""
            UPDATE release_canary_batch
            SET status = #{status},
                started_at = #{startedAt},
                ended_at = #{endedAt},
                failure_reason = #{failureReason},
                updated_at = NOW()
            WHERE id = #{id}
            """)
    int updateStatusWithTimestamps(
            @Param("id") Long id,
            @Param("status") String status,
            @Param("startedAt") java.time.OffsetDateTime startedAt,
            @Param("endedAt") java.time.OffsetDateTime endedAt,
            @Param("failureReason") String failureReason
    );

    /**
     * 锁住计划下的全部批次并读回。继续批次时用一次锁定读同时完成三件事：
     * 确认没有残留 RUNNING 批次、确认上一批次已成功、取出第一个 PENDING 批次。
     * 这样"判定"与"认领"在同一组行锁内完成，不会被并发请求插入。
     */
    @Select("""
            SELECT *
            FROM release_canary_batch
            WHERE plan_id = #{planId}
            ORDER BY batch_no ASC
            FOR UPDATE
            """)
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    List<ReleaseCanaryBatch> findByPlanIdForUpdate(@Param("planId") Long planId);

    @Select("""
            SELECT *
            FROM release_canary_batch
            WHERE plan_id = #{planId}
              AND status = 'PENDING'
            ORDER BY batch_no ASC
            LIMIT 1
            FOR UPDATE
            """)
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    ReleaseCanaryBatch findFirstPendingForUpdate(@Param("planId") Long planId);

    @Select("SELECT * FROM release_canary_batch WHERE id = #{id} FOR UPDATE")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    ReleaseCanaryBatch selectByIdForUpdate(@Param("id") Long id);

    /**
     * 批次认领的**认领凭证**：PENDING → RUNNING 条件更新，受影响行数为 1 的那个请求
     * 才拥有这次外部执行的所有权；其余并发请求拿到 0 并拒绝。
     */
    @Update("""
            UPDATE release_canary_batch
            SET status = 'RUNNING',
                started_at = #{startedAt},
                ended_at = NULL,
                failure_reason = NULL,
                updated_at = NOW()
            WHERE id = #{id}
              AND status = 'PENDING'
            """)
    int markRunningIfPending(@Param("id") Long id, @Param("startedAt") java.time.OffsetDateTime startedAt);

    /**
     * 批次终态只允许从 RUNNING 落一次。受影响行数为 0 说明批次已被其他路径收口，
     * 调用方必须按"结果未知/冲突"失败关闭，不能把这次外部执行谎报为成功。
     */
    @Update("""
            UPDATE release_canary_batch
            SET status = #{status},
                ended_at = #{endedAt},
                failure_reason = #{failureReason},
                updated_at = NOW()
            WHERE id = #{id}
              AND status = 'RUNNING'
            """)
    int finishIfRunning(
            @Param("id") Long id,
            @Param("status") String status,
            @Param("endedAt") java.time.OffsetDateTime endedAt,
            @Param("failureReason") String failureReason
    );
}
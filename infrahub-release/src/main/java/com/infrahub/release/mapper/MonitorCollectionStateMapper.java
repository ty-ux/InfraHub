package com.infrahub.release.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.infrahub.release.entity.MonitorCollectionState;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 采集状态写回。所有写回都是**条件更新**，正确性依赖 SQL 而不是调用方的纪律。
 *
 * <p>三条保证：
 * <ol>
 *   <li>{@link #upsertObservation} 由 {@code observed_at} 守卫：迟到的旧样本命中 0 行，绝不覆盖新状态。</li>
 *   <li>失败不推进 {@code last_success_at}：由 {@code COALESCE(EXCLUDED.last_success_at, 现值)} 保证，
 *       单次瞬时失败不会清除最近成功时间。</li>
 *   <li>连续失败计数在 SQL 内自增，不依赖"先读后写"，并发下不丢计数。</li>
 * </ol>
 *
 * @see docs/release/v2-host-instance-status-collection-contract.md 第 5.4 节
 */
@Mapper
public interface MonitorCollectionStateMapper extends BaseMapper<MonitorCollectionState> {

    /**
     * 写回一次采集尝试（含成功观测）。
     *
     * @return 受影响行数：1 = 已写入；0 = 样本迟到（observed_at 不比现值新），应计为被拒收
     */
    @Insert("""
            INSERT INTO monitor_collection_state
                (target_type, target_id, observer_type, config_present, last_source, last_result,
                 last_attempt_at, last_success_at, consecutive_failure_count,
                 last_error_category, last_error_summary,
                 last_observed_status, last_observed_health, observed_at,
                 discarded_sample_count, created_at, updated_at)
            VALUES
                (#{targetType}, #{targetId}, #{observerType}, #{configPresent}, #{lastSource}, #{lastResult},
                 #{lastAttemptAt}, #{lastSuccessAt}, #{consecutiveFailureCount},
                 #{lastErrorCategory}, #{lastErrorSummary},
                 #{lastObservedStatus}, #{lastObservedHealth}, #{observedAt},
                 0, NOW(), NOW())
            ON CONFLICT (target_type, target_id) DO UPDATE
            SET
                observer_type = EXCLUDED.observer_type,
                config_present = EXCLUDED.config_present,
                last_source = EXCLUDED.last_source,
                last_result = EXCLUDED.last_result,
                last_attempt_at = EXCLUDED.last_attempt_at,
                last_error_category = EXCLUDED.last_error_category,
                last_error_summary = EXCLUDED.last_error_summary,
                observed_at = EXCLUDED.observed_at,
                last_success_at = COALESCE(EXCLUDED.last_success_at, monitor_collection_state.last_success_at),
                last_observed_status = COALESCE(EXCLUDED.last_observed_status, monitor_collection_state.last_observed_status),
                last_observed_health = COALESCE(EXCLUDED.last_observed_health, monitor_collection_state.last_observed_health),
                consecutive_failure_count = CASE
                    WHEN EXCLUDED.last_result = 'FAILED'
                        THEN monitor_collection_state.consecutive_failure_count + 1
                    ELSE 0
                END,
                updated_at = NOW()
            WHERE monitor_collection_state.observed_at IS NULL
               OR monitor_collection_state.observed_at < EXCLUDED.observed_at
            """)
    int upsertObservation(MonitorCollectionState state);

    /**
     * 记录一次被拒收的迟到样本。仅当库中确实已有更新的样本时才计数。
     */
    @Insert("""
            UPDATE monitor_collection_state
            SET discarded_sample_count = discarded_sample_count + 1,
                updated_at = NOW()
            WHERE target_type = #{targetType}
              AND target_id = #{targetId}
              AND observed_at IS NOT NULL
              AND observed_at >= #{observedAt}
            """)
    int incrementDiscarded(@Param("targetType") String targetType,
                           @Param("targetId") Long targetId,
                           @Param("observedAt") OffsetDateTime observedAt);

    /**
     * 按目标锁定采集状态行，供写回事务在"比对 → 写采集表 → 写目标表"期间持有。
     *
     * <p>首次采集时该行还不存在，本方法返回 null、不锁任何东西 —— 这是安全的：调用方在进入本方法
     * 之前已经锁住了目标行，同一目标的写回在目标行上就已经串行化了，
     * {@code INSERT ... ON CONFLICT} 再由唯一索引兜底。
     *
     * <p>锁顺序固定为：目标行 → 采集状态行 → SSH 配置行，全模块统一。
     */
    @Select("""
            SELECT id, target_type, target_id, observer_type, config_present, last_source, last_result,
                   last_attempt_at, last_success_at, consecutive_failure_count,
                   last_error_category, last_error_summary,
                   last_observed_status, last_observed_health, observed_at, discarded_sample_count
            FROM monitor_collection_state
            WHERE target_type = #{targetType}
              AND target_id = #{targetId}
            FOR UPDATE
            """)
    @Options(useCache = false)
    MonitorCollectionState selectByTargetForUpdate(@Param("targetType") String targetType,
                                                   @Param("targetId") Long targetId);

    /**
     * 列出"证据已过期、但尚未标记 EXPIRED"的目标，**只读、不加锁**。
     *
     * <p>过期复位必须按目标逐个放进各自的事务里，因此这里只负责选出候选；
     * 真正改状态由 {@link #markExpiredOne} 完成。批量 UPDATE 已被删除，
     * 原因是它与"随后按条件复位目标状态"无法同事务，复位失败会让该目标永久停在 EXPIRED 分支上。
     */
    @Select("""
            SELECT id, target_type, target_id, observer_type, config_present, last_source, last_result,
                   last_attempt_at, last_success_at, consecutive_failure_count,
                   last_error_category, last_error_summary,
                   last_observed_status, last_observed_health, observed_at, discarded_sample_count
            FROM monitor_collection_state
            WHERE last_success_at IS NOT NULL
              AND last_success_at < #{staleBefore}
              AND last_result != 'EXPIRED'
            ORDER BY target_type, target_id
            """)
    @Options(useCache = false)
    List<MonitorCollectionState> selectExpiredTargets(@Param("staleBefore") OffsetDateTime staleBefore);

    /**
     * 把单个目标的过期证据标记为 EXPIRED。
     *
     * <p>写成 {@code @Update}（而不是 {@code @Select} 包一条 UPDATE ... RETURNING）是刻意的：
     * MyBatis 会把 {@code @Select} 当读操作、把它塞进一级缓存，同 SqlSession 内二次调用返回陈旧结果
     * （该缺陷曾在真实 PostgreSQL 上复现，旧实现不得不靠 {@code flushCache}/{@code useCache} 打补丁）。
     * 用 {@code @Update} 从形状上就没有这个坑，无需额外开关。
     *
     * @return 受影响行数：1 = 本轮标记成功；0 = 期间已被别的执行者标记（幂等，无需复位）
     */
    @Update("""
            UPDATE monitor_collection_state
            SET last_result = 'EXPIRED',
                updated_at = NOW()
            WHERE target_type = #{targetType}
              AND target_id = #{targetId}
              AND last_success_at IS NOT NULL
              AND last_success_at < #{staleBefore}
              AND last_result != 'EXPIRED'
            """)
    int markExpiredOne(@Param("targetType") String targetType,
                       @Param("targetId") Long targetId,
                       @Param("staleBefore") OffsetDateTime staleBefore);

    /**
     * 按目标批量读取采集状态（列表页展示"来源/最近成功观察/新鲜度/失败原因"用）。
     */
    @Select("""
            <script>
            SELECT id, target_type, target_id, observer_type, config_present, last_source, last_result,
                   last_attempt_at, last_success_at, consecutive_failure_count,
                   last_error_category, last_error_summary,
                   last_observed_status, last_observed_health, observed_at, discarded_sample_count
            FROM monitor_collection_state
            WHERE target_type = #{targetType}
              AND target_id IN
              <foreach collection="targetIds" item="targetId" open="(" separator="," close=")">
                  #{targetId}
              </foreach>
            </script>
            """)
    @Options(useCache = false)
    List<MonitorCollectionState> selectByTargets(@Param("targetType") String targetType,
                                                 @Param("targetIds") Collection<Long> targetIds);

    @Select("""
            SELECT id, target_type, target_id, observer_type, config_present, last_source, last_result,
                   last_attempt_at, last_success_at, consecutive_failure_count,
                   last_error_category, last_error_summary,
                   last_observed_status, last_observed_health, observed_at, discarded_sample_count
            FROM monitor_collection_state
            WHERE target_type = #{targetType}
              AND target_id = #{targetId}
            """)
    @Options(useCache = false)
    MonitorCollectionState selectByTarget(@Param("targetType") String targetType,
                                          @Param("targetId") Long targetId);
}
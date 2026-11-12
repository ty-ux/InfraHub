package com.infrahub.release.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.infrahub.release.entity.RuntimeDeployRecord;
import com.infrahub.release.entity.RuntimeExecutionUnknown;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.OffsetDateTime;
import java.util.List;

@Mapper
public interface RuntimeExecutionUnknownMapper extends BaseMapper<RuntimeExecutionUnknown> {

    /**
     * 表是否存在探针。用 {@code to_regclass} 而不是直接 SELECT 表：后者在表不存在时会报错，
     * 在 PostgreSQL 中会把当前事务标记为 aborted，连后续的阻断检查都无法继续。
     * {@code to_regclass} 只查系统目录，表不存在时返回 null，不会污染事务。
     */
    @Select("SELECT to_regclass('runtime_execution_unknown')::text")
    String probe();

    @Select("""
            SELECT COUNT(1)
            FROM runtime_execution_unknown
            WHERE service_id = #{serviceId}
              AND env_id = #{envId}
              AND status = 'UNRESOLVED'
            """)
    long countUnresolvedByServiceAndEnv(@Param("serviceId") Long serviceId,
                                        @Param("envId") Long envId);

    @Select("""
            <script>
            SELECT *
            FROM runtime_execution_unknown
            WHERE status = 'UNRESOLVED'
            <if test="serviceId != null">
                AND service_id = #{serviceId}
            </if>
            <if test="envId != null">
                AND env_id = #{envId}
            </if>
            ORDER BY detected_at DESC, id DESC
            LIMIT 500
            </script>
            """)
    List<RuntimeExecutionUnknown> listUnresolved(@Param("serviceId") Long serviceId,
                                                 @Param("envId") Long envId);

    /**
     * 已解除的结果未知执行（历史存档）。RESOLVED 只是历史记录，不参与任何阻断判断，
     * 因此这里没有 LIMIT 500 之外的写入口，也不允许把它当证据去回填。
     */
    @Select("""
            <script>
            SELECT *
            FROM runtime_execution_unknown
            WHERE status = 'RESOLVED'
            <if test="serviceId != null">
                AND service_id = #{serviceId}
            </if>
            <if test="envId != null">
                AND env_id = #{envId}
            </if>
            ORDER BY resolved_at DESC NULLS LAST, id DESC
            LIMIT #{limit}
            </script>
            """)
    List<RuntimeExecutionUnknown> listResolved(@Param("serviceId") Long serviceId,
                                               @Param("envId") Long envId,
                                               @Param("limit") int limit);

    @Select("SELECT * FROM runtime_execution_unknown WHERE id = #{id}")
    RuntimeExecutionUnknown selectById(@Param("id") Long id);

    @Select("SELECT * FROM runtime_execution_unknown WHERE id = #{id} FOR UPDATE")
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    RuntimeExecutionUnknown selectByIdForUpdate(@Param("id") Long id);

    @Select("""
            SELECT COUNT(1)
            FROM runtime_deploy_record d
            WHERE d.service_id = #{serviceId}
              AND d.env_id = #{envId}
              AND d.deploy_status = 'UNKNOWN'
              AND NOT EXISTS (
                  SELECT 1 FROM runtime_execution_unknown u WHERE u.deploy_record_id = d.id
              )
            """)
    long countLegacyUnknownWithoutMarker(@Param("serviceId") Long serviceId,
                                         @Param("envId") Long envId);

    @Select("""
            SELECT d.*
            FROM runtime_deploy_record d
            WHERE d.service_id = #{serviceId}
              AND d.env_id = #{envId}
              AND d.deploy_status = 'UNKNOWN'
              AND NOT EXISTS (
                  SELECT 1 FROM runtime_execution_unknown u WHERE u.deploy_record_id = d.id
              )
            ORDER BY d.created_at DESC, d.id DESC
            """)
    List<RuntimeDeployRecord> listLegacyUnknownWithoutMarker(@Param("serviceId") Long serviceId,
                                                             @Param("envId") Long envId);

    @Select("""
            SELECT COUNT(1)
            FROM runtime_deploy_record
            WHERE service_id = #{serviceId}
              AND env_id = #{envId}
              AND deploy_status = 'UNKNOWN'
            """)
    long countUnknownDeployRecordsByServiceAndEnv(@Param("serviceId") Long serviceId,
                                                  @Param("envId") Long envId);

    @Select("""
            SELECT COUNT(1)
            FROM runtime_deploy_record
            WHERE release_order_id = #{releaseOrderId}
              AND deploy_status = 'UNKNOWN'
            """)
    long countUnknownDeployRecordsByReleaseOrder(@Param("releaseOrderId") Long releaseOrderId);

    @Select("""
            SELECT COUNT(1)
            FROM runtime_deploy_record
            WHERE rollback_order_id = #{rollbackOrderId}
              AND deploy_status = 'UNKNOWN'
            """)
    long countUnknownDeployRecordsByRollbackOrder(@Param("rollbackOrderId") Long rollbackOrderId);

    @Select("""
            SELECT COUNT(1)
            FROM runtime_execution_unknown
            WHERE deploy_record_id = #{deployRecordId}
            """)
    long countMarkersByDeployRecord(@Param("deployRecordId") Long deployRecordId);

    @Select("""
            SELECT *
            FROM runtime_execution_unknown
            WHERE deploy_record_id = #{deployRecordId}
            ORDER BY id DESC
            LIMIT 1
            """)
    RuntimeExecutionUnknown selectLatestByDeployRecordId(@Param("deployRecordId") Long deployRecordId);

    /**
     * 同一来源已经存在的那一条**订单级**未解除事实（{@code deploy_record_id IS NULL}）。
     *
     * <p>订单级登记走 {@code ON CONFLICT DO NOTHING}，插入被挡下时调用方需要读回被命中的那一行，
     * 才能判断自己是“重复登记同一条事实”还是“被去重掉了另一个灰度批次的事实”。
     */
    /**
     * 回读"插入被去重挡下"时**所有可能挡住它的**订单级未解除事实（按 id 升序）。
     *
     * <p>为什么不 {@code ORDER BY id LIMIT 1}：唯一索引的粒度决定"是哪一行挡住了插入"，
     * 而不是"哪一行的 id 最小"。V31 的索引只到 (source_type, 订单, deploy_record_id)，
     * 同一发布单下**所有**订单级事实共用一个键；V33 把 plan/batch 纳入键后，
     * 只有 (plan, batch) 相同的事实才会互相冲突。取一行等于把两个索引的语义混在一起，
     * 会把"被合并到别的批次"错判成"重复登记"（或反之）。
     *
     * <p>回读条件与两个索引键域一致：订单级（{@code deploy_record_id IS NULL}）、未解除、
     * 同 source 与同订单。调用方按 plan/batch 逐行比对后分类，不再依赖行序。
     */
    @Select("""
            SELECT *
            FROM runtime_execution_unknown
            WHERE status = 'UNRESOLVED'
              AND deploy_record_id IS NULL
              AND source_type = #{sourceType}
              AND COALESCE(release_order_id, 0) = COALESCE(#{releaseOrderId}, 0)
              AND COALESCE(rollback_order_id, 0) = COALESCE(#{rollbackOrderId}, 0)
            ORDER BY id
            """)
    List<RuntimeExecutionUnknown> listUnresolvedOrderLevelCandidates(@Param("sourceType") String sourceType,
                                                                    @Param("releaseOrderId") Long releaseOrderId,
                                                                    @Param("rollbackOrderId") Long rollbackOrderId);

    @Insert("""
            INSERT INTO runtime_execution_unknown(
                service_id, env_id, source_type, release_order_id, rollback_order_id,
                deploy_record_id, plan_id, batch_id, status, detection_source,
                detection_reason, detected_by, detected_at, updated_at
            ) VALUES (
                #{serviceId}, #{envId}, #{sourceType}, #{releaseOrderId}, #{rollbackOrderId},
                #{deployRecordId}, #{planId}, #{batchId}, #{status}, #{detectionSource},
                #{detectionReason}, #{detectedBy}, #{detectedAt}, NOW()
            )
            ON CONFLICT DO NOTHING
            """)
    int insertIfAbsent(RuntimeExecutionUnknown row);

    @Insert("""
            INSERT INTO runtime_execution_unknown(
                service_id, env_id, source_type, release_order_id, rollback_order_id,
                deploy_record_id, plan_id, batch_id, status, detection_source,
                detection_reason, detected_by, detected_at,
                resolution_outcome, resolution_note, resolved_by, resolved_at, updated_at
            ) VALUES (
                #{serviceId}, #{envId}, #{sourceType}, #{releaseOrderId}, #{rollbackOrderId},
                #{deployRecordId}, #{planId}, #{batchId}, 'RESOLVED', #{detectionSource},
                #{detectionReason}, #{detectedBy}, #{detectedAt},
                #{resolutionOutcome}, #{resolutionNote}, #{resolvedBy}, #{resolvedAt}, NOW()
            )
            """)
    int insertResolved(RuntimeExecutionUnknown row);

    @Update("""
            UPDATE runtime_execution_unknown
            SET status = 'RESOLVED',
                resolution_outcome = #{outcome},
                resolution_note = #{note},
                resolved_by = #{resolvedBy},
                resolved_at = #{resolvedAt},
                updated_at = NOW()
            WHERE id = #{id}
              AND status = 'UNRESOLVED'
            """)
    int markResolved(@Param("id") Long id,
                     @Param("outcome") String outcome,
                     @Param("note") String note,
                     @Param("resolvedBy") Long resolvedBy,
                     @Param("resolvedAt") OffsetDateTime resolvedAt);
}

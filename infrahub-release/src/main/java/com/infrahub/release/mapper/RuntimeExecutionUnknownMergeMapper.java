package com.infrahub.release.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.infrahub.release.entity.RuntimeExecutionUnknownMerge;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface RuntimeExecutionUnknownMergeMapper extends BaseMapper<RuntimeExecutionUnknownMerge> {

    /** 同上：{@code to_regclass} 不污染事务，表不存在时返回 null。 */
    @Select("SELECT to_regclass('runtime_execution_unknown_merge')::text")
    String probe();

    /**
     * 记录一次“被去重索引合并”：某条订单级事实被既有的未解除事实吸收。
     *
     * <p>{@code ON CONFLICT DO NOTHING} 按 (unknown_id, source_type, plan, batch) 去重，
     * 因此重复登记同一个被并批次不会产生重复台账行。
     *
     * @return 1 表示本次新增了一条合并记录；0 表示这一条被并事实已经登记过
     */
    @Insert("""
            INSERT INTO runtime_execution_unknown_merge(
                unknown_id, service_id, env_id, source_type, release_order_id, rollback_order_id,
                plan_id, batch_id, detection_source, detection_reason, merged_at
            ) VALUES (
                #{unknownId}, #{serviceId}, #{envId}, #{sourceType}, #{releaseOrderId}, #{rollbackOrderId},
                #{planId}, #{batchId}, #{detectionSource}, #{detectionReason}, NOW()
            )
            ON CONFLICT DO NOTHING
            """)
    int insertIfAbsent(RuntimeExecutionUnknownMerge row);

    /** 某条未解除事实吸收掉的其它批次事实（升序）。 */
    @Select("""
            SELECT *
            FROM runtime_execution_unknown_merge
            WHERE unknown_id = #{unknownId}
            ORDER BY id
            """)
    List<RuntimeExecutionUnknownMerge> listByUnknownId(@Param("unknownId") Long unknownId);

    @Select("""
            SELECT COUNT(1)
            FROM runtime_execution_unknown_merge
            WHERE unknown_id = #{unknownId}
            """)
    long countByUnknownId(@Param("unknownId") Long unknownId);

    /** 未解除列表的批量富化；调用方必须保证 ids 非空。 */
    @Select("""
            <script>
            SELECT *
            FROM runtime_execution_unknown_merge
            WHERE unknown_id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">
                #{id}
            </foreach>
            ORDER BY unknown_id, id
            </script>
            """)
    List<RuntimeExecutionUnknownMerge> listByUnknownIds(@Param("ids") List<Long> ids);
}

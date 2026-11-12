package com.infrahub.release.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.infrahub.release.dto.MonitorSnapshotQuery;
import com.infrahub.release.entity.MonitorSnapshot;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface MonitorSnapshotMapper extends BaseMapper<MonitorSnapshot> {

    @Select("""
            <script>
            SELECT
                id,
                target_type,
                target_id,
                snapshot_time,
                metric_payload::text AS metric_payload,
                source,
                created_at
            FROM monitor_snapshot
            <where>
                <if test="query.snapshotId != null">
                    AND id = #{query.snapshotId}
                </if>
                <if test="query.targetType != null and query.targetType != ''">
                    AND target_type = #{query.targetType}
                </if>
                <if test="query.targetId != null">
                    AND target_id = #{query.targetId}
                </if>
                <if test="query.source != null and query.source != ''">
                    AND source = #{query.source}
                </if>
            </where>
            ORDER BY snapshot_time DESC
            </script>
            """)
    Page<MonitorSnapshot> selectSnapshotPage(Page<MonitorSnapshot> page, @Param("query") MonitorSnapshotQuery query);

    @Insert("""
            INSERT INTO monitor_snapshot
                (target_type, target_id, snapshot_time, metric_payload, source, created_at)
            VALUES
                (#{targetType}, #{targetId}, #{snapshotTime}, CAST(#{metricPayload} AS jsonb), #{source}, #{createdAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertManualSnapshot(MonitorSnapshot snapshot);
}

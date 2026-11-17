package com.infrahub.pipeline.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.infrahub.pipeline.entity.PipelineRun;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface PipelineRunMapper extends BaseMapper<PipelineRun> {

    @Select("SELECT * FROM pipeline_run WHERE id = #{id} FOR UPDATE")
    PipelineRun lockById(@Param("id") Long id);

    @Select("""
            SELECT EXISTS (SELECT 1 FROM base_service s JOIN runtime_service_instance i ON i.service_id = s.id
            WHERE s.id = #{serviceId} AND s.app_id = #{appId} AND s.is_deleted = FALSE AND i.env_id = #{envId})
            """)
    boolean hasDeploymentTarget(Long serviceId, Long appId, Long envId);

    @Select("SELECT EXISTS (SELECT 1 FROM base_service WHERE id = #{serviceId} AND app_id = #{appId} AND is_deleted = false)")
    boolean belongsToApplication(Long serviceId, Long appId);

    @Select("SELECT EXISTS (SELECT 1 FROM release_version WHERE id = #{versionId} AND app_id = #{appId})")
    boolean versionBelongsToApplication(Long versionId, Long appId);

    @Select("""
            <script>
            SELECT r.id, v.version_no, v.artifact_id
            FROM pipeline_run r JOIN release_version v ON v.id = r.version_id AND v.app_id = r.app_id
            WHERE r.id IN <foreach collection="runIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<PipelineRun> listVersionOutputs(@Param("runIds") List<Long> runIds);

    /**
     * 统计关联同一发布单且仍在执行中的流水线运行数。
     *
     * <p>用于发布认领时确认“这次调用就是该发布单唯一的执行中流水线”。
     * 流水线自身的外层 RUNNING 状态只是展示用的状态，不是执行凭证：没有这个计数，
     * 一个发布单可以同时存在多个 RUNNING 运行，各自都通过了订单级的检查。
     */
    @Select("""
            SELECT COUNT(1)
            FROM pipeline_run
            WHERE release_id = #{releaseId}
              AND run_status = 'RUNNING'
            """)
    long countRunningByReleaseId(@Param("releaseId") Long releaseId);
}

package com.infrahub.release.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.infrahub.release.entity.ReleaseVersion;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ReleaseVersionMapper extends BaseMapper<ReleaseVersion> {
    @Select("SELECT service_id FROM pipeline_run WHERE id = #{runId}")
    Long findBuildServiceId(Long runId);

    @Select("""
            SELECT v.id, v.app_id, v.version_no, p.service_id AS build_service_id
            FROM release_version v
            JOIN release_artifact a ON a.id = v.artifact_id
            LEFT JOIN pipeline_run p ON p.id = v.build_pipeline_run_id
            WHERE NOT v.is_deleted AND a.artifact_type IN ('JAR', 'DOCKER_COMPOSE')
            ORDER BY v.id DESC
            """)
    List<ReleaseVersion> listRollbackVersions();
}

package com.infrahub.release.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.infrahub.release.entity.ReleaseExecutorConfig;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ReleaseExecutorConfigMapper extends BaseMapper<ReleaseExecutorConfig> {

    @Select("SELECT * FROM release_executor_config WHERE env_id = #{envId} AND enabled = true ORDER BY id")
    List<ReleaseExecutorConfig> findEnabledByEnvId(Long envId);

    @Select("SELECT * FROM release_executor_config WHERE env_id = #{envId} AND executor_name = #{executorName}")
    ReleaseExecutorConfig findByEnvIdAndName(Long envId, String executorName);

    @Select("SELECT * FROM release_executor_config WHERE id = #{id} AND enabled = true")
    ReleaseExecutorConfig findEnabledById(Long id);
}
package com.infrahub.pipeline.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.infrahub.pipeline.entity.PipelineStep;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PipelineStepMapper extends BaseMapper<PipelineStep> {
}

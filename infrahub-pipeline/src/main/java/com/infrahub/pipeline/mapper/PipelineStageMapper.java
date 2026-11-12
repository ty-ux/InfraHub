package com.infrahub.pipeline.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.infrahub.pipeline.entity.PipelineStage;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PipelineStageMapper extends BaseMapper<PipelineStage> {
}

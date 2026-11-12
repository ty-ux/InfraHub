package com.infrahub.pipeline.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.infrahub.pipeline.entity.PipelineStepRun;
import com.infrahub.pipeline.mapper.PipelineStepRunMapper;
import org.springframework.stereotype.Service;

@Service
public class PipelineStepRunService extends ServiceImpl<PipelineStepRunMapper, PipelineStepRun> {
}

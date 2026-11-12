package com.infrahub.pipeline.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.infrahub.common.context.UserContextHolder;
import com.infrahub.pipeline.entity.PipelineDef;
import com.infrahub.pipeline.mapper.PipelineDefMapper;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;

@Service
public class PipelineDefService extends ServiceImpl<PipelineDefMapper, PipelineDef> {

    public boolean deleteDefinition(Long id) {
        return lambdaUpdate()
                .eq(PipelineDef::getId, id)
                .eq(PipelineDef::getIsDeleted, false)
                .set(PipelineDef::getIsDeleted, true)
                .set(PipelineDef::getEnabled, false)
                .set(PipelineDef::getUpdatedBy, UserContextHolder.getUserIdOrDefault(1L))
                .set(PipelineDef::getUpdatedAt, OffsetDateTime.now())
                .update();
    }
}

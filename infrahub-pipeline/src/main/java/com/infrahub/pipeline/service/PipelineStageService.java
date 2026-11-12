package com.infrahub.pipeline.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.infrahub.common.error.ErrorCode;
import com.infrahub.common.exception.BizException;
import com.infrahub.pipeline.entity.PipelineStage;
import com.infrahub.pipeline.mapper.PipelineStageMapper;
import com.infrahub.pipeline.mapper.PipelineStepMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class PipelineStageService extends ServiceImpl<PipelineStageMapper, PipelineStage> {

    private final PipelineDefService pipelineDefService;
    private final PipelineStepMapper pipelineStepMapper;

    public List<PipelineStage> listByPipelineIdOrdered(Long pipelineId) {
        return baseMapper.selectList(new QueryWrapper<PipelineStage>()
                .eq("pipeline_id", pipelineId)
                .orderByAsc("stage_order"));
    }

    public boolean createForPipeline(Long pipelineId, PipelineStage body) {
        ensurePipelineExists(pipelineId);
        body.setId(null);
        body.setPipelineId(pipelineId);
        try {
            return save(body);
        } catch (DataIntegrityViolationException exception) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "阶段顺序已存在");
        }
    }

    public boolean updateStage(Long stageId, PipelineStage body) {
        PipelineStage existing = getById(stageId);
        if (existing == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "流水线阶段不存在");
        }
        Long pipelineId = body.getPipelineId() == null ? existing.getPipelineId() : body.getPipelineId();
        ensurePipelineExists(pipelineId);
        body.setId(stageId);
        body.setPipelineId(pipelineId);
        try {
            return updateById(body);
        } catch (DataIntegrityViolationException exception) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "阶段顺序已存在");
        }
    }

    public boolean deleteStage(Long stageId) {
        if (getById(stageId) == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "流水线阶段不存在");
        }
        Long stepCount = pipelineStepMapper.selectCount(new QueryWrapper<com.infrahub.pipeline.entity.PipelineStep>()
                .eq("stage_id", stageId));
        if (stepCount != null && stepCount > 0) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "阶段下存在步骤，不能删除");
        }
        return removeById(stageId);
    }

    private void ensurePipelineExists(Long pipelineId) {
        if (pipelineDefService.getById(pipelineId) == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "流水线不存在");
        }
    }
}

package com.infrahub.pipeline.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.infrahub.common.error.ErrorCode;
import com.infrahub.common.exception.BizException;
import com.infrahub.pipeline.entity.PipelineStep;
import com.infrahub.pipeline.mapper.PipelineStepMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
@RequiredArgsConstructor
public class PipelineStepService extends ServiceImpl<PipelineStepMapper, PipelineStep> {

    private static final String EXECUTOR_LOCAL_SHELL = "LOCAL_SHELL";
    private static final String EXECUTOR_RELEASE_DEPLOY = "RELEASE_DEPLOY";
    private static final String EXECUTOR_REGISTER_ARTIFACT = "REGISTER_ARTIFACT";
    private static final String EXECUTOR_COMPOSE_TEMPLATE = "COMPOSE_TEMPLATE";

    private final PipelineStageService pipelineStageService;

    public List<PipelineStep> listByStageOrdered(Long stageId) {
        return baseMapper.selectList(new QueryWrapper<PipelineStep>()
                .eq("stage_id", stageId)
                .orderByAsc("step_order"));
    }

    public List<PipelineStep> listByStageIdsOrdered(List<Long> stageIds) {
        if (stageIds == null || stageIds.isEmpty()) {
            return List.of();
        }
        return baseMapper.selectList(new QueryWrapper<PipelineStep>()
                .in("stage_id", stageIds)
                .orderByAsc("stage_id")
                .orderByAsc("step_order"));
    }

    public boolean createForStage(Long stageId, PipelineStep body) {
        ensureStageExists(stageId);
        validateManualStep(body);
        body.setId(null);
        body.setStageId(stageId);
        applyDefaults(body);
        try {
            return save(body);
        } catch (DataIntegrityViolationException exception) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "步骤顺序已存在");
        }
    }

    public boolean updateStep(Long stepId, PipelineStep body) {
        PipelineStep existing = getById(stepId);
        if (existing == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "流水线步骤不存在");
        }
        Long stageId = body.getStageId() == null ? existing.getStageId() : body.getStageId();
        ensureStageExists(stageId);
        if (EXECUTOR_COMPOSE_TEMPLATE.equals(existing.getExecutorType())) {
            if (!existing.getStageId().equals(stageId) || !EXECUTOR_COMPOSE_TEMPLATE.equals(body.getExecutorType())) {
                throw new BizException(ErrorCode.VALIDATION_ERROR, "不能更换 Compose 模板步骤的阶段或执行器");
            }
            body.setScriptContent(existing.getScriptContent());
            body.setContinueOnFail(false);
        } else {
            validateManualStep(body);
        }
        body.setId(stepId);
        body.setStageId(stageId);
        applyDefaults(body);
        try {
            return updateById(body);
        } catch (DataIntegrityViolationException exception) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "步骤顺序已存在");
        }
    }

    public boolean deleteStep(Long stepId) {
        if (getById(stepId) == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "流水线步骤不存在");
        }
        return removeById(stepId);
    }

    private void ensureStageExists(Long stageId) {
        if (pipelineStageService.getById(stageId) == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "流水线阶段不存在");
        }
    }

    private void validateManualStep(PipelineStep body) {
        String executorType = normalizeExecutorType(body.getExecutorType());
        if (!EXECUTOR_LOCAL_SHELL.equals(executorType) && !EXECUTOR_RELEASE_DEPLOY.equals(executorType)
                && !EXECUTOR_REGISTER_ARTIFACT.equals(executorType)) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "不支持的流水线执行器: " + body.getExecutorType());
        }
        if (EXECUTOR_LOCAL_SHELL.equals(executorType) && !StringUtils.hasText(body.getScriptContent())) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "LOCAL_SHELL 步骤脚本不能为空");
        }
        body.setExecutorType(executorType);
    }

    private void applyDefaults(PipelineStep body) {
        body.setExecutorType(normalizeExecutorType(body.getExecutorType()));
        if (!StringUtils.hasText(body.getStepType())) {
            body.setStepType("SCRIPT");
        }
        if (body.getRetryTimes() == null) {
            body.setRetryTimes(0);
        }
        if (body.getContinueOnFail() == null) {
            body.setContinueOnFail(false);
        }
    }

    private static String normalizeExecutorType(String executorType) {
        if (!StringUtils.hasText(executorType)) {
            return EXECUTOR_LOCAL_SHELL;
        }
        return executorType.trim().toUpperCase();
    }
}

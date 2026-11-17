package com.infrahub.pipeline.service;

import com.infrahub.common.context.ApplicationAccess;
import com.infrahub.common.error.ErrorCode;
import com.infrahub.common.exception.BizException;
import com.infrahub.pipeline.entity.PipelineRun;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Set;

@Service
@RequiredArgsConstructor
public class PipelineAccessService {
    private final PipelineDefService definitions;
    private final PipelineStageService stages;
    private final PipelineRunService runs;

    public Set<Long> readableApps() {
        return ApplicationAccess.allowedApps("PIPELINE_VIEW", "PIPELINE_EXECUTE");
    }

    public void requireReadablePipeline(Long pipelineId) {
        var definition = definitions.getById(pipelineId);
        if (definition == null) throw new BizException(ErrorCode.VALIDATION_ERROR, "流水线不存在");
        ApplicationAccess.require(definition.getAppId(), "PIPELINE_VIEW", "PIPELINE_EXECUTE");
    }

    public void requireReadableStage(Long stageId) {
        var stage = stages.getById(stageId);
        if (stage == null) throw new BizException(ErrorCode.VALIDATION_ERROR, "流水线阶段不存在");
        requireReadablePipeline(stage.getPipelineId());
    }

    public PipelineRun requireReadableRun(Long runId) {
        PipelineRun run = runs.getById(runId);
        if (run != null) ApplicationAccess.require(run.getAppId(), "PIPELINE_VIEW", "PIPELINE_EXECUTE");
        return run;
    }
}

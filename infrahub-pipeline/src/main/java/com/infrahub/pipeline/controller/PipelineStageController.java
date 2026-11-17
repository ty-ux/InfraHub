package com.infrahub.pipeline.controller;

import com.infrahub.common.api.ApiResponse;
import com.infrahub.common.context.ApplicationAccess;
import com.infrahub.common.support.TraceIdHolder;
import com.infrahub.pipeline.entity.PipelineStage;
import com.infrahub.pipeline.entity.PipelineStep;
import com.infrahub.pipeline.service.PipelineStageService;
import com.infrahub.pipeline.service.PipelineStepService;
import com.infrahub.pipeline.service.PipelineAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@Validated
@RequiredArgsConstructor
@PreAuthorize("hasAnyAuthority('PIPELINE_VIEW','PIPELINE_EXECUTE','SYSTEM_ADMIN')")
public class PipelineStageController {

    private final PipelineStageService pipelineStageService;
    private final PipelineStepService pipelineStepService;
    private final PipelineAccessService access;

    @GetMapping("/api/pipelines/{pipelineId}/stages")
    public ApiResponse<List<PipelineStage>> listStages(@PathVariable Long pipelineId) {
        access.requireReadablePipeline(pipelineId);
        return ApiResponse.success(pipelineStageService.listByPipelineIdOrdered(pipelineId), TraceIdHolder.get());
    }

    @PostMapping("/api/pipelines/{pipelineId}/stages")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<Boolean> createStage(@PathVariable Long pipelineId, @RequestBody PipelineStage body) {
        return ApiResponse.success(pipelineStageService.createForPipeline(pipelineId, body), TraceIdHolder.get());
    }

    @PutMapping("/api/pipeline-stages/{stageId}")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<Boolean> updateStage(@PathVariable Long stageId, @RequestBody PipelineStage body) {
        return ApiResponse.success(pipelineStageService.updateStage(stageId, body), TraceIdHolder.get());
    }

    @DeleteMapping("/api/pipeline-stages/{stageId}")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<Boolean> deleteStage(@PathVariable Long stageId) {
        return ApiResponse.success(pipelineStageService.deleteStage(stageId), TraceIdHolder.get());
    }

    @GetMapping("/api/pipeline-stages/{stageId}/steps")
    public ApiResponse<List<PipelineStep>> listSteps(@PathVariable Long stageId) {
        access.requireReadableStage(stageId);
        var steps = pipelineStepService.listByStageOrdered(stageId);
        if (ApplicationAccess.isAdmin()) return ApiResponse.success(steps, TraceIdHolder.get());
        return ApiResponse.success(steps.stream().map(step -> {
            var summary = new PipelineStep();
            summary.setId(step.getId());
            summary.setStageId(step.getStageId());
            summary.setStepName(step.getStepName());
            summary.setStepType(step.getStepType());
            summary.setExecutorType(step.getExecutorType());
            summary.setStepOrder(step.getStepOrder());
            summary.setTimeoutSeconds(step.getTimeoutSeconds());
            summary.setRetryTimes(step.getRetryTimes());
            summary.setContinueOnFail(step.getContinueOnFail());
            summary.setCreatedAt(step.getCreatedAt());
            return summary;
        }).toList(), TraceIdHolder.get());
    }

    @PostMapping("/api/pipeline-stages/{stageId}/steps")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<Boolean> createStep(@PathVariable Long stageId, @RequestBody PipelineStep body) {
        return ApiResponse.success(pipelineStepService.createForStage(stageId, body), TraceIdHolder.get());
    }
}

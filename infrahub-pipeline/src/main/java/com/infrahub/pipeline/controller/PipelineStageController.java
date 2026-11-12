package com.infrahub.pipeline.controller;

import com.infrahub.common.api.ApiResponse;
import com.infrahub.common.support.TraceIdHolder;
import com.infrahub.pipeline.entity.PipelineStage;
import com.infrahub.pipeline.entity.PipelineStep;
import com.infrahub.pipeline.service.PipelineStageService;
import com.infrahub.pipeline.service.PipelineStepService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@Validated
@RequiredArgsConstructor
@PreAuthorize("hasAnyAuthority('PIPELINE_EXECUTE','SYSTEM_ADMIN')")
public class PipelineStageController {

    private final PipelineStageService pipelineStageService;
    private final PipelineStepService pipelineStepService;

    @GetMapping("/api/pipelines/{pipelineId}/stages")
    public ApiResponse<List<PipelineStage>> listStages(@PathVariable Long pipelineId) {
        return ApiResponse.success(pipelineStageService.listByPipelineIdOrdered(pipelineId), TraceIdHolder.get());
    }

    @PostMapping("/api/pipelines/{pipelineId}/stages")
    public ApiResponse<Boolean> createStage(@PathVariable Long pipelineId, @RequestBody PipelineStage body) {
        return ApiResponse.success(pipelineStageService.createForPipeline(pipelineId, body), TraceIdHolder.get());
    }

    @PutMapping("/api/pipeline-stages/{stageId}")
    public ApiResponse<Boolean> updateStage(@PathVariable Long stageId, @RequestBody PipelineStage body) {
        return ApiResponse.success(pipelineStageService.updateStage(stageId, body), TraceIdHolder.get());
    }

    @DeleteMapping("/api/pipeline-stages/{stageId}")
    public ApiResponse<Boolean> deleteStage(@PathVariable Long stageId) {
        return ApiResponse.success(pipelineStageService.deleteStage(stageId), TraceIdHolder.get());
    }

    @GetMapping("/api/pipeline-stages/{stageId}/steps")
    public ApiResponse<List<PipelineStep>> listSteps(@PathVariable Long stageId) {
        return ApiResponse.success(pipelineStepService.listByStageOrdered(stageId), TraceIdHolder.get());
    }

    @PostMapping("/api/pipeline-stages/{stageId}/steps")
    public ApiResponse<Boolean> createStep(@PathVariable Long stageId, @RequestBody PipelineStep body) {
        return ApiResponse.success(pipelineStepService.createForStage(stageId, body), TraceIdHolder.get());
    }
}

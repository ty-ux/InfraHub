package com.infrahub.pipeline.controller;

import com.infrahub.common.api.ApiResponse;
import com.infrahub.common.support.TraceIdHolder;
import com.infrahub.pipeline.entity.PipelineStep;
import com.infrahub.pipeline.service.PipelineStepService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/api/pipeline-steps")
@PreAuthorize("hasAnyAuthority('PIPELINE_EXECUTE','SYSTEM_ADMIN')")
public class PipelineStepController {

    private final PipelineStepService pipelineStepService;

    @PutMapping("/{stepId}")
    public ApiResponse<Boolean> updateStep(@PathVariable Long stepId, @RequestBody PipelineStep body) {
        return ApiResponse.success(pipelineStepService.updateStep(stepId, body), TraceIdHolder.get());
    }

    @DeleteMapping("/{stepId}")
    public ApiResponse<Boolean> deleteStep(@PathVariable Long stepId) {
        return ApiResponse.success(pipelineStepService.deleteStep(stepId), TraceIdHolder.get());
    }
}

package com.infrahub.pipeline.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.infrahub.common.api.ApiResponse;
import com.infrahub.common.api.PageQuery;
import com.infrahub.common.api.PageResponse;
import com.infrahub.common.support.TraceIdHolder;
import com.infrahub.pipeline.dto.RunPipelineRequest;
import com.infrahub.pipeline.dto.RunPipelineResponse;
import com.infrahub.pipeline.entity.PipelineDef;
import com.infrahub.pipeline.entity.PipelineRun;
import com.infrahub.pipeline.service.PipelineDefService;
import com.infrahub.pipeline.service.PipelineRunService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/api/pipelines")
public class PipelineController {

    private final PipelineDefService pipelineDefService;
    private final PipelineRunService pipelineRunService;

    @GetMapping
    @PreAuthorize("hasAnyAuthority('PIPELINE_EXECUTE','SYSTEM_ADMIN')")
    public ApiResponse<PageResponse<PipelineDef>> page(@Valid PageQuery query,
                                                       @RequestParam(defaultValue = "false") boolean includeDeleted,
                                                       @RequestParam(required = false) @Positive Long appId) {
        Page<PipelineDef> page = pipelineDefService.lambdaQuery()
                .eq(!includeDeleted, PipelineDef::getIsDeleted, false)
                .eq(appId != null, PipelineDef::getAppId, appId)
                .orderByAsc(PipelineDef::getId)
                .page(new Page<>(query.getPageNum(), query.getPageSize()));
        return ApiResponse.success(PageResponse.from(page), TraceIdHolder.get());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('PIPELINE_EXECUTE','SYSTEM_ADMIN')")
    public ApiResponse<PipelineDef> detail(@PathVariable Long id) {
        return ApiResponse.success(pipelineDefService.getById(id), TraceIdHolder.get());
    }

    @PostMapping
    @PreAuthorize("hasAnyAuthority('PIPELINE_EXECUTE','SYSTEM_ADMIN')")
    public ApiResponse<Boolean> create(@RequestBody PipelineDef body) {
        return ApiResponse.success(pipelineDefService.save(body), TraceIdHolder.get());
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<Boolean> delete(@PathVariable Long id) {
        return ApiResponse.success(pipelineDefService.deleteDefinition(id), TraceIdHolder.get());
    }

    @PostMapping("/{id}/run")
    @PreAuthorize("hasAnyAuthority('PIPELINE_EXECUTE','SYSTEM_ADMIN')")
    public ApiResponse<RunPipelineResponse> run(@PathVariable Long id, @RequestBody(required = false) RunPipelineRequest body) {
        RunPipelineRequest request = body == null ? new RunPipelineRequest() : body;
        PipelineRun run = pipelineRunService.startPipeline(id, request.getEnvId(), request.getVersionId(), request.getReleaseId(), request.getServiceId());
        return ApiResponse.success(
                RunPipelineResponse.builder()
                        .pipelineRunId(run.getId())
                        .runStatus(run.getRunStatus())
                        .build(),
                TraceIdHolder.get()
        );
    }
}

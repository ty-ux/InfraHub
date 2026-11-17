package com.infrahub.pipeline.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.infrahub.common.api.ApiResponse;
import com.infrahub.common.api.PageQuery;
import com.infrahub.common.api.PageResponse;
import com.infrahub.common.context.ApplicationAccess;
import com.infrahub.common.error.ErrorCode;
import com.infrahub.common.exception.BizException;
import com.infrahub.common.support.TraceIdHolder;
import com.infrahub.pipeline.dto.RunPipelineRequest;
import com.infrahub.pipeline.dto.RunPipelineResponse;
import com.infrahub.pipeline.entity.PipelineDef;
import com.infrahub.pipeline.entity.PipelineRun;
import com.infrahub.pipeline.service.PipelineDefService;
import com.infrahub.pipeline.service.PipelineRunService;
import com.infrahub.pipeline.service.PipelineAccessService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.infrahub.pipeline.service.PipelineStageService;
import com.infrahub.pipeline.service.PipelineStepService;

@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/api/pipelines")
public class PipelineController {

    private final PipelineDefService pipelineDefService;
    private final PipelineRunService pipelineRunService;
    private final PipelineAccessService access;
    private final PipelineStageService stages;
    private final PipelineStepService steps;
    private final ObjectMapper objectMapper;

    @GetMapping
    @PreAuthorize("hasAnyAuthority('PIPELINE_VIEW','PIPELINE_EXECUTE','SYSTEM_ADMIN')")
    public ApiResponse<PageResponse<PipelineDef>> page(@Valid PageQuery query,
                                                       @RequestParam(defaultValue = "false") boolean includeDeleted,
                                                       @RequestParam(required = false) @Positive Long appId) {
        var filter = Wrappers.<PipelineDef>lambdaQuery()
                .eq(!includeDeleted, PipelineDef::getIsDeleted, false)
                .eq(appId != null, PipelineDef::getAppId, appId)
                .orderByAsc(PipelineDef::getId);
        ApplicationAccess.restrict(filter, PipelineDef::getAppId, access.readableApps());
        Page<PipelineDef> page = pipelineDefService.page(new Page<>(query.getPageNum(), query.getPageSize()), filter);
        return ApiResponse.success(PageResponse.from(page), TraceIdHolder.get());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('PIPELINE_VIEW','PIPELINE_EXECUTE','SYSTEM_ADMIN')")
    public ApiResponse<PipelineDef> detail(@PathVariable Long id) {
        PipelineDef pipeline = pipelineDefService.getById(id);
        if (pipeline != null) ApplicationAccess.require(pipeline.getAppId(), "PIPELINE_VIEW", "PIPELINE_EXECUTE");
        return ApiResponse.success(pipeline, TraceIdHolder.get());
    }

    @PostMapping
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
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
        PipelineDef pipeline = pipelineDefService.getById(id);
        if (pipeline == null) throw new BizException(ErrorCode.VALIDATION_ERROR, "流水线不存在");
        ApplicationAccess.require(pipeline.getAppId(), "PIPELINE_EXECUTE");
        if (!ApplicationAccess.isAdmin() && request.getReleaseId() != null) {
            throw new BizException(ErrorCode.FORBIDDEN, "只有系统管理员可关联旧发布单运行");
        }
        PipelineRun run = pipelineRunService.startPipeline(id, request.getEnvId(), request.getVersionId(), request.getReleaseId(), request.getServiceId());
        return ApiResponse.success(
                RunPipelineResponse.builder()
                        .pipelineRunId(run.getId())
                        .runStatus(run.getRunStatus())
                        .build(),
                TraceIdHolder.get()
        );
    }

    @GetMapping("/{id}/compose-scope")
    @PreAuthorize("hasAnyAuthority('PIPELINE_EXECUTE','SYSTEM_ADMIN')")
    public ApiResponse<ComposeScope> composeScope(@PathVariable Long id) {
        PipelineDef pipeline = pipelineDefService.getById(id);
        if (pipeline == null) throw new BizException(ErrorCode.VALIDATION_ERROR, "流水线不存在");
        ApplicationAccess.require(pipeline.getAppId(), "PIPELINE_EXECUTE");
        var stageIds = stages.listByPipelineIdOrdered(id).stream().map(stage -> stage.getId()).toList();
        var step = steps.listByStageIdsOrdered(stageIds).stream()
                .filter(item -> "COMPOSE_TEMPLATE".equals(item.getExecutorType())).findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.VALIDATION_ERROR, "流水线没有 Compose 模板"));
        try {
            var spec = objectMapper.readTree(step.getScriptContent()).path("spec");
            return ApiResponse.success(new ComposeScope(pipeline.getAppId(), spec.path("envId").asLong(),
                    spec.path("serviceId").asLong()), TraceIdHolder.get());
        } catch (JsonProcessingException exception) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "Compose 模板配置无法读取");
        }
    }

    public record ComposeScope(Long appId, long envId, long serviceId) {
    }
}

package com.infrahub.pipeline.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.infrahub.common.api.ApiResponse;
import com.infrahub.common.api.PageQuery;
import com.infrahub.common.api.PageResponse;
import com.infrahub.common.error.ErrorCode;
import com.infrahub.common.exception.BizException;
import com.infrahub.common.support.TraceIdHolder;
import com.infrahub.pipeline.entity.PipelineRun;
import com.infrahub.pipeline.entity.PipelineStepRun;
import com.infrahub.pipeline.service.PipelineStepLogStore;
import com.infrahub.pipeline.service.PipelineStepRunService;
import com.infrahub.pipeline.service.PipelineAccessService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;

import java.io.IOException;
import java.util.Objects;

@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/api/pipeline-step-runs")
@PreAuthorize("hasAnyAuthority('PIPELINE_VIEW','PIPELINE_EXECUTE','SYSTEM_ADMIN')")
public class PipelineStepRunController {

    private final PipelineStepRunService pipelineStepRunService;
    private final PipelineAccessService access;
    private final PipelineStepLogStore pipelineStepLogStore;

    @GetMapping("/{id}/log")
    public ApiResponse<PipelineStepLogStore.LogChunk> log(@PathVariable @Positive Long id,
                                                         @RequestParam @Positive Long pipelineRunId,
                                                         @RequestParam(defaultValue = "0") @PositiveOrZero long offset,
                                                         HttpServletResponse response) throws IOException {
        PipelineStepRun step = pipelineStepRunService.getById(id);
        if (step == null || !Objects.equals(pipelineRunId, step.getPipelineRunId())) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "流水线步骤运行记录不存在");
        }
        PipelineRun run = access.requireReadableRun(pipelineRunId);
        if (run == null) throw new BizException(ErrorCode.VALIDATION_ERROR, "流水线运行记录不存在");
        boolean terminal = !"RUNNING".equals(step.getRunStatus()) || !"RUNNING".equals(run.getRunStatus());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        return ApiResponse.success(pipelineStepLogStore.read(step, offset, terminal), TraceIdHolder.get());
    }

    @GetMapping
    public ApiResponse<PageResponse<PipelineStepRun>> page(@Valid PageQuery query,
                                                           @RequestParam(required = false) Long pipelineRunId) {
        Page<PipelineStepRun> page = new Page<>(query.getPageNum(), query.getPageSize());
        var filter = Wrappers.<PipelineStepRun>lambdaQuery();
        if (pipelineRunId != null) {
            if (access.requireReadableRun(pipelineRunId) == null) {
                return ApiResponse.success(PageResponse.empty(query), TraceIdHolder.get());
            }
            filter.eq(PipelineStepRun::getPipelineRunId, pipelineRunId).orderByAsc(PipelineStepRun::getId);
        } else {
            var apps = access.readableApps();
            if (apps != null) {
                if (apps.isEmpty()) return ApiResponse.success(PageResponse.empty(query), TraceIdHolder.get());
                var ids = apps.toArray();
                String parameters = java.util.stream.IntStream.range(0, ids.length)
                        .mapToObj(index -> "{" + index + "}")
                        .collect(java.util.stream.Collectors.joining(","));
                filter.apply("pipeline_run_id IN (SELECT id FROM pipeline_run WHERE app_id IN (" + parameters + "))", ids);
            }
        }
        return ApiResponse.success(PageResponse.from(pipelineStepRunService.page(page, filter)), TraceIdHolder.get());
    }
}

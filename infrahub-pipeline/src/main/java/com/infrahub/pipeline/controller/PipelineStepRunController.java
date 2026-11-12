package com.infrahub.pipeline.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.infrahub.common.api.ApiResponse;
import com.infrahub.common.api.PageQuery;
import com.infrahub.common.api.PageResponse;
import com.infrahub.common.error.ErrorCode;
import com.infrahub.common.exception.BizException;
import com.infrahub.common.support.TraceIdHolder;
import com.infrahub.pipeline.entity.PipelineRun;
import com.infrahub.pipeline.entity.PipelineStepRun;
import com.infrahub.pipeline.service.PipelineRunService;
import com.infrahub.pipeline.service.PipelineStepLogStore;
import com.infrahub.pipeline.service.PipelineStepRunService;
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
@PreAuthorize("hasAnyAuthority('PIPELINE_EXECUTE','SYSTEM_ADMIN')")
public class PipelineStepRunController {

    private final PipelineStepRunService pipelineStepRunService;
    private final PipelineRunService pipelineRunService;
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
        boolean terminal = !"RUNNING".equals(step.getRunStatus());
        if (!terminal) {
            PipelineRun run = pipelineRunService.getById(pipelineRunId);
            terminal = run == null || !"RUNNING".equals(run.getRunStatus());
        }
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        return ApiResponse.success(pipelineStepLogStore.read(step, offset, terminal), TraceIdHolder.get());
    }

    @GetMapping
    public ApiResponse<PageResponse<PipelineStepRun>> page(@Valid PageQuery query,
                                                           @RequestParam(required = false) Long pipelineRunId) {
        Page<PipelineStepRun> page = new Page<>(query.getPageNum(), query.getPageSize());
        if (pipelineRunId == null) {
            return ApiResponse.success(PageResponse.from(pipelineStepRunService.page(page)), TraceIdHolder.get());
        }
        return ApiResponse.success(
                PageResponse.from(
                        pipelineStepRunService.lambdaQuery()
                                .eq(PipelineStepRun::getPipelineRunId, pipelineRunId)
                                .orderByAsc(PipelineStepRun::getId)
                                .page(page)
                ),
                TraceIdHolder.get()
        );
    }
}

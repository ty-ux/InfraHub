package com.infrahub.pipeline.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.infrahub.common.api.ApiResponse;
import com.infrahub.common.api.PageQuery;
import com.infrahub.common.api.PageResponse;
import com.infrahub.common.context.ApplicationAccess;
import com.infrahub.common.support.TraceIdHolder;
import com.infrahub.pipeline.entity.PipelineRun;
import com.infrahub.pipeline.service.PipelineRunService;
import com.infrahub.pipeline.service.PipelineAccessService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/api/pipeline-runs")
@PreAuthorize("hasAnyAuthority('PIPELINE_VIEW','PIPELINE_EXECUTE','SYSTEM_ADMIN')")
public class PipelineRunController {

    private final PipelineRunService pipelineRunService;
    private final PipelineAccessService access;

    @GetMapping("/{id}")
    public ApiResponse<PipelineRun> detail(@org.springframework.web.bind.annotation.PathVariable Long id) {
        PipelineRun run = access.requireReadableRun(id);
        if (run != null) pipelineRunService.fillVersionOutputs(List.of(run));
        return ApiResponse.success(run, TraceIdHolder.get());
    }

    @GetMapping
    public ApiResponse<PageResponse<PipelineRun>> page(@Valid PageQuery query,
                                                       @RequestParam(required = false) Long releaseId,
                                                       @RequestParam(required = false) @Positive Long pipelineId) {
        var filter = Wrappers.<PipelineRun>lambdaQuery()
                .eq(releaseId != null, PipelineRun::getReleaseId, releaseId)
                .eq(pipelineId != null, PipelineRun::getPipelineId, pipelineId)
                .orderByDesc(PipelineRun::getCreatedAt, PipelineRun::getId);
        ApplicationAccess.restrict(filter, PipelineRun::getAppId, access.readableApps());
        Page<PipelineRun> page = pipelineRunService.page(new Page<>(query.getPageNum(), query.getPageSize()), filter);
        pipelineRunService.fillVersionOutputs(page.getRecords());
        return ApiResponse.success(PageResponse.from(page), TraceIdHolder.get());
    }
}

package com.infrahub.release.controller;

import com.infrahub.common.api.ApiResponse;
import com.infrahub.common.support.TraceIdHolder;
import com.infrahub.release.pipeline.ComposePipelineSpec;
import com.infrahub.release.pipeline.ComposePipelineTemplateService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/pipelines/templates")
@RequiredArgsConstructor
public class ComposePipelineTemplateController {
    private final ComposePipelineTemplateService service;

    @PostMapping("/compose")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<Created> create(@Valid @RequestBody ComposePipelineSpec spec) {
        return ApiResponse.success(new Created(service.create(spec)), TraceIdHolder.get());
    }

    @GetMapping("/compose/build-contexts")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<List<String>> buildContexts() {
        return ApiResponse.success(service.buildContexts(), TraceIdHolder.get());
    }

    public record Created(Long pipelineId) {}
}

package com.infrahub.release.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.infrahub.common.api.ApiResponse;
import com.infrahub.common.api.PageQuery;
import com.infrahub.common.api.PageResponse;
import com.infrahub.common.support.TraceIdHolder;
import com.infrahub.release.dto.ReleaseArtifactUploadResponse;
import com.infrahub.release.entity.ReleaseArtifact;
import com.infrahub.release.service.ReleaseArtifactUploadService;
import com.infrahub.release.service.ReleaseArtifactService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/api/artifacts")
@PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
public class ArtifactController {

    private final ReleaseArtifactService releaseArtifactService;
    private final ReleaseArtifactUploadService releaseArtifactUploadService;

    @GetMapping
    public ApiResponse<PageResponse<ReleaseArtifact>> page(@Valid PageQuery query) {
        return ApiResponse.success(PageResponse.from(releaseArtifactService.page(new Page<>(query.getPageNum(), query.getPageSize()))), TraceIdHolder.get());
    }

    @PostMapping("/upload")
    public ApiResponse<ReleaseArtifactUploadResponse> upload(
            @RequestParam Long runtimeServiceInstanceId,
            @RequestParam MultipartFile file,
            @RequestParam(required = false) String artifactName
    ) {
        return ApiResponse.success(releaseArtifactUploadService.upload(runtimeServiceInstanceId, file, artifactName), TraceIdHolder.get());
    }
}

package com.infrahub.release.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.infrahub.common.api.ApiResponse;
import com.infrahub.common.api.PageQuery;
import com.infrahub.common.api.PageResponse;
import com.infrahub.common.context.ApplicationAccess;
import com.infrahub.common.support.TraceIdHolder;
import com.infrahub.release.dto.CreateReleaseVersionRequest;
import com.infrahub.release.entity.ReleaseVersion;
import com.infrahub.release.service.ReleaseVersionCommandService;
import com.infrahub.release.service.ReleaseVersionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/api/versions")
public class VersionController {

    private final ReleaseVersionService releaseVersionService;
    private final ReleaseVersionCommandService releaseVersionCommandService;

    @GetMapping
    @PreAuthorize("hasAnyAuthority('RELEASE_VIEW','PIPELINE_EXECUTE','SYSTEM_ADMIN')")
    public ApiResponse<PageResponse<ReleaseVersion>> page(@Valid PageQuery query) {
        var filter = Wrappers.<ReleaseVersion>lambdaQuery().orderByDesc(ReleaseVersion::getId);
        ApplicationAccess.restrict(filter, ReleaseVersion::getAppId, ApplicationAccess.allowedApps("RELEASE_VIEW", "PIPELINE_EXECUTE"));
        return ApiResponse.success(PageResponse.from(releaseVersionService.page(new Page<>(query.getPageNum(), query.getPageSize()), filter)), TraceIdHolder.get());
    }

    @PostMapping
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<ReleaseVersion> create(@Valid @RequestBody CreateReleaseVersionRequest request) {
        return ApiResponse.success(releaseVersionCommandService.create(request), TraceIdHolder.get());
    }
}

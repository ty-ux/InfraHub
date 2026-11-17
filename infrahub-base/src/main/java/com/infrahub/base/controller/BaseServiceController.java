package com.infrahub.base.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.infrahub.base.entity.BaseServiceEntity;
import com.infrahub.base.service.BaseServiceEntityService;
import com.infrahub.common.api.ApiResponse;
import com.infrahub.common.api.PageQuery;
import com.infrahub.common.api.PageResponse;
import com.infrahub.common.context.ApplicationAccess;
import com.infrahub.common.support.TraceIdHolder;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/api/services")
public class BaseServiceController {

    private final BaseServiceEntityService baseServiceEntityService;

    @GetMapping
    @PreAuthorize("hasAnyAuthority('APP_VIEW','PIPELINE_EXECUTE','SYSTEM_ADMIN')")
    public ApiResponse<PageResponse<BaseServiceEntity>> page(@Valid PageQuery query) {
        var filter = Wrappers.<BaseServiceEntity>lambdaQuery().orderByAsc(BaseServiceEntity::getId);
        ApplicationAccess.restrict(filter, BaseServiceEntity::getAppId, ApplicationAccess.allowedApps("APP_VIEW", "PIPELINE_EXECUTE"));
        return ApiResponse.success(PageResponse.from(baseServiceEntityService.page(new Page<>(query.getPageNum(), query.getPageSize()), filter)), TraceIdHolder.get());
    }

    @PostMapping
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<Boolean> create(@RequestBody BaseServiceEntity body) {
        return ApiResponse.success(baseServiceEntityService.save(body), TraceIdHolder.get());
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<Boolean> update(@PathVariable Long id, @RequestBody BaseServiceEntity body) {
        body.setId(id);
        return ApiResponse.success(baseServiceEntityService.updateById(body), TraceIdHolder.get());
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<Boolean> delete(@PathVariable Long id) {
        return ApiResponse.success(baseServiceEntityService.removeById(id), TraceIdHolder.get());
    }
}

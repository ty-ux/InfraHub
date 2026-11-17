package com.infrahub.base.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.infrahub.base.entity.BaseEnv;
import com.infrahub.base.service.BaseEnvService;
import com.infrahub.common.api.ApiResponse;
import com.infrahub.common.api.PageQuery;
import com.infrahub.common.api.PageResponse;
import com.infrahub.common.support.TraceIdHolder;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/api/envs")
public class BaseEnvController {

    private final BaseEnvService baseEnvService;

    @GetMapping
    @PreAuthorize("hasAnyAuthority('APP_VIEW','PIPELINE_EXECUTE','SYSTEM_ADMIN')")
    public ApiResponse<PageResponse<BaseEnv>> page(@Valid PageQuery query) {
        return ApiResponse.success(PageResponse.from(baseEnvService.page(new Page<>(query.getPageNum(), query.getPageSize()))), TraceIdHolder.get());
    }

    @PostMapping
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<Boolean> create(@RequestBody BaseEnv body) {
        return ApiResponse.success(baseEnvService.save(body), TraceIdHolder.get());
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<Boolean> update(@PathVariable Long id, @RequestBody BaseEnv body) {
        body.setId(id);
        return ApiResponse.success(baseEnvService.updateById(body), TraceIdHolder.get());
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<Boolean> delete(@PathVariable Long id) {
        return ApiResponse.success(baseEnvService.removeById(id), TraceIdHolder.get());
    }
}

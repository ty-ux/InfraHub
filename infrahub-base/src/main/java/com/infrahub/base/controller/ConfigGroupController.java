package com.infrahub.base.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.infrahub.base.entity.ConfigGroup;
import com.infrahub.base.service.ConfigGroupService;
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
@RequestMapping("/api/config-groups")
@PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
public class ConfigGroupController {

    private final ConfigGroupService configGroupService;

    @GetMapping
    public ApiResponse<PageResponse<ConfigGroup>> page(@Valid PageQuery query) {
        return ApiResponse.success(PageResponse.from(configGroupService.page(new Page<>(query.getPageNum(), query.getPageSize()))), TraceIdHolder.get());
    }

    @PostMapping
    public ApiResponse<Boolean> create(@RequestBody ConfigGroup body) {
        return ApiResponse.success(configGroupService.save(body), TraceIdHolder.get());
    }

    @PutMapping("/{id}")
    public ApiResponse<Boolean> update(@PathVariable Long id, @RequestBody ConfigGroup body) {
        body.setId(id);
        return ApiResponse.success(configGroupService.updateById(body), TraceIdHolder.get());
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Boolean> delete(@PathVariable Long id) {
        return ApiResponse.success(configGroupService.removeById(id), TraceIdHolder.get());
    }
}

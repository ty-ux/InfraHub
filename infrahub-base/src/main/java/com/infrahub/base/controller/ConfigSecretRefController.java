package com.infrahub.base.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.infrahub.base.entity.ConfigSecretRef;
import com.infrahub.base.service.ConfigSecretRefService;
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
@RequestMapping("/api/secret-refs")
@PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
public class ConfigSecretRefController {

    private final ConfigSecretRefService configSecretRefService;

    @GetMapping
    public ApiResponse<PageResponse<ConfigSecretRef>> page(@Valid PageQuery query) {
        return ApiResponse.success(PageResponse.from(configSecretRefService.page(new Page<>(query.getPageNum(), query.getPageSize()))), TraceIdHolder.get());
    }

    @PostMapping
    public ApiResponse<Boolean> create(@RequestBody ConfigSecretRef body) {
        return ApiResponse.success(configSecretRefService.save(body), TraceIdHolder.get());
    }

    @PutMapping("/{id}")
    public ApiResponse<Boolean> update(@PathVariable Long id, @RequestBody ConfigSecretRef body) {
        body.setId(id);
        return ApiResponse.success(configSecretRefService.updateById(body), TraceIdHolder.get());
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Boolean> delete(@PathVariable Long id) {
        return ApiResponse.success(configSecretRefService.removeById(id), TraceIdHolder.get());
    }
}

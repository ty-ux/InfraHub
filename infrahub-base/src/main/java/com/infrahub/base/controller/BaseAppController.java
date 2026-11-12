package com.infrahub.base.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.infrahub.base.entity.BaseApp;
import com.infrahub.base.service.BaseAppService;
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
@RequestMapping("/api/apps")
public class BaseAppController {

    private final BaseAppService baseAppService;

    @GetMapping
    @PreAuthorize("hasAnyAuthority('APP_VIEW','SYSTEM_ADMIN')")
    public ApiResponse<PageResponse<BaseApp>> page(@Valid PageQuery query) {
        return ApiResponse.success(PageResponse.from(baseAppService.page(new Page<>(query.getPageNum(), query.getPageSize()))), TraceIdHolder.get());
    }

    @PostMapping
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<Boolean> create(@RequestBody BaseApp body) {
        return ApiResponse.success(baseAppService.save(body), TraceIdHolder.get());
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<Boolean> update(@PathVariable Long id, @RequestBody BaseApp body) {
        body.setId(id);
        return ApiResponse.success(baseAppService.updateById(body), TraceIdHolder.get());
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<Boolean> delete(@PathVariable Long id) {
        return ApiResponse.success(baseAppService.removeById(id), TraceIdHolder.get());
    }
}

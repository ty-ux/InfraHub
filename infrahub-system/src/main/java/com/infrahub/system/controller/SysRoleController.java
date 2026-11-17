package com.infrahub.system.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.infrahub.common.api.ApiResponse;
import com.infrahub.common.api.PageQuery;
import com.infrahub.common.api.PageResponse;
import com.infrahub.common.support.TraceIdHolder;
import com.infrahub.system.entity.SysRole;
import com.infrahub.system.service.SysRoleService;
import com.infrahub.system.service.SysAccessService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/api/roles")
@PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
public class SysRoleController {

    private final SysRoleService sysRoleService;
    private final SysAccessService access;

    @GetMapping
    public ApiResponse<PageResponse<SysRole>> page(@Valid PageQuery query) {
        return ApiResponse.success(PageResponse.from(sysRoleService.page(new Page<>(query.getPageNum(), query.getPageSize()))), TraceIdHolder.get());
    }

    @GetMapping("/access-catalog")
    public ApiResponse<SysAccessService.Catalog> catalog() {
        return ApiResponse.success(access.catalog(), TraceIdHolder.get());
    }

    @PostMapping
    public ApiResponse<Long> create(@Valid @RequestBody SysAccessService.NewRole body) {
        return ApiResponse.success(access.createRole(body), TraceIdHolder.get());
    }

    @GetMapping("/{id}/grants")
    public ApiResponse<SysAccessService.Grants> grants(@PathVariable Long id) {
        return ApiResponse.success(access.roleGrants(id), TraceIdHolder.get());
    }

    @PutMapping("/{id}/grants")
    public ApiResponse<Boolean> saveGrants(@PathVariable Long id, @Valid @RequestBody SysAccessService.Grants body) {
        access.saveRoleGrants(id, body);
        return ApiResponse.success(true, TraceIdHolder.get());
    }
}

package com.infrahub.system.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.infrahub.common.api.ApiResponse;
import com.infrahub.common.api.PageQuery;
import com.infrahub.common.api.PageResponse;
import com.infrahub.common.support.TraceIdHolder;
import com.infrahub.system.entity.SysUser;
import com.infrahub.system.service.SysUserService;
import com.infrahub.system.service.SysAccessService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Set;

@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/api/users")
@PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
public class SysUserController {

    private final SysUserService sysUserService;
    private final SysAccessService access;

    @GetMapping
    public ApiResponse<PageResponse<SysUser>> page(@Valid PageQuery query) {
        return ApiResponse.success(PageResponse.from(sysUserService.page(new Page<>(query.getPageNum(), query.getPageSize()))), TraceIdHolder.get());
    }

    @GetMapping("/{id}/roles")
    public ApiResponse<Set<Long>> roles(@PathVariable Long id) {
        return ApiResponse.success(access.userRoles(id), TraceIdHolder.get());
    }

    @PutMapping("/{id}/roles")
    public ApiResponse<Boolean> saveRoles(@PathVariable Long id, @Valid @RequestBody SysAccessService.UserRoles body) {
        access.saveUserRoles(id, body.roleIds());
        return ApiResponse.success(true, TraceIdHolder.get());
    }
}

package com.infrahub.system.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.infrahub.common.api.ApiResponse;
import com.infrahub.common.api.PageQuery;
import com.infrahub.common.api.PageResponse;
import com.infrahub.common.support.TraceIdHolder;
import com.infrahub.system.entity.SysRole;
import com.infrahub.system.service.SysRoleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/api/roles")
@PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
public class SysRoleController {

    private final SysRoleService sysRoleService;

    @GetMapping
    public ApiResponse<PageResponse<SysRole>> page(@Valid PageQuery query) {
        return ApiResponse.success(PageResponse.from(sysRoleService.page(new Page<>(query.getPageNum(), query.getPageSize()))), TraceIdHolder.get());
    }
}

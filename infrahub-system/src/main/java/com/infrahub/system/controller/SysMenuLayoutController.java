package com.infrahub.system.controller;

import com.infrahub.common.api.ApiResponse;
import com.infrahub.common.support.TraceIdHolder;
import com.infrahub.system.service.SysMenuLayoutService;
import com.infrahub.system.service.SysMenuLayoutService.State;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/system/menu-layout")
public class SysMenuLayoutController {

    private final SysMenuLayoutService service;

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<State> get() {
        return ApiResponse.success(service.get(), TraceIdHolder.get());
    }

    @PutMapping
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ResponseEntity<ApiResponse<State>> save(@Valid @RequestBody State state) {
        return service.save(state)
                .map(saved -> ResponseEntity.ok(ApiResponse.success(saved, TraceIdHolder.get())))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(ApiResponse.failure("MENU_LAYOUT_CONFLICT", "菜单配置已被修改，请读取最新配置后重试", TraceIdHolder.get())));
    }
}

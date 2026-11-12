package com.infrahub.release.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.infrahub.common.api.ApiResponse;
import com.infrahub.common.api.PageResponse;
import com.infrahub.common.support.TraceIdHolder;
import com.infrahub.release.dto.AuditLogQuery;
import com.infrahub.release.entity.SysAuditLog;
import com.infrahub.release.mapper.SysAuditLogMapper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller for audit log query API.
 * Provides read-only access to system audit logs for SYSTEM_ADMIN.
 */
@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/api/audit")
public class SystemAuditController {

    private final SysAuditLogMapper sysAuditLogMapper;

    /**
     * Query audit logs with filters.
     * Only SYSTEM_ADMIN has access to audit logs.
     */
    @GetMapping("/logs")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<PageResponse<SysAuditLog>> queryLogs(@Valid AuditLogQuery query) {
        Page<SysAuditLog> page = new Page<>(query.getPageNum(), query.getPageSize());
        sysAuditLogMapper.queryAuditLogs(page,
                query.getBizType(),
                query.getBizId(),
                query.getAction(),
                query.getResult(),
                query.getOperatorId(),
                query.getStartTime(),
                query.getEndTime());
        return ApiResponse.success(PageResponse.from(page), TraceIdHolder.get());
    }
}
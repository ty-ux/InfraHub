package com.infrahub.base.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.infrahub.base.entity.ConfigEnvConfig;
import com.infrahub.base.service.ConfigEnvConfigService;
import com.infrahub.base.service.EnvConfigDocumentService;
import com.infrahub.common.api.ApiResponse;
import com.infrahub.common.api.PageQuery;
import com.infrahub.common.api.PageResponse;
import com.infrahub.common.support.TraceIdHolder;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/api/env-configs")
@PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
public class ConfigEnvConfigController {

    private final ConfigEnvConfigService configEnvConfigService;
    private final EnvConfigDocumentService documentService;

    @GetMapping("/documents/ungrouped-scopes")
    public ApiResponse<List<EnvConfigDocumentService.UngroupedScope>> ungroupedScopes() {
        return ApiResponse.success(documentService.listUngroupedScopes(), TraceIdHolder.get());
    }

    @GetMapping("/documents")
    public ApiResponse<EnvConfigDocumentService.Document> document(@RequestParam(required = false) Long groupId,
            @RequestParam(required = false) Long envId, @RequestParam(required = false) Long appId) {
        return ApiResponse.success(documentService.read(groupId, envId, appId), TraceIdHolder.get());
    }

    @PutMapping("/documents")
    public ApiResponse<EnvConfigDocumentService.Document> saveDocument(@RequestParam(required = false) Long groupId,
            @RequestParam(required = false) Long envId, @RequestParam(required = false) Long appId,
            @RequestBody EnvConfigDocumentService.Document body) {
        return ApiResponse.success(documentService.save(groupId, envId, appId, body), TraceIdHolder.get());
    }

    @GetMapping
    public ApiResponse<PageResponse<ConfigEnvConfig>> page(@Valid PageQuery query) {
        return ApiResponse.success(PageResponse.from(configEnvConfigService.page(new Page<>(query.getPageNum(), query.getPageSize()))), TraceIdHolder.get());
    }

    @PostMapping
    public ApiResponse<Boolean> create(@RequestBody ConfigEnvConfig body) {
        return ApiResponse.success(configEnvConfigService.save(body), TraceIdHolder.get());
    }

    @PutMapping("/{id}")
    public ApiResponse<Boolean> update(@PathVariable Long id, @RequestBody ConfigEnvConfig body) {
        body.setId(id);
        return ApiResponse.success(configEnvConfigService.updateById(body), TraceIdHolder.get());
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Boolean> delete(@PathVariable Long id) {
        return ApiResponse.success(configEnvConfigService.removeById(id), TraceIdHolder.get());
    }
}

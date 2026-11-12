package com.infrahub.release.controller;

import com.infrahub.common.api.ApiResponse;
import com.infrahub.common.error.ErrorCode;
import com.infrahub.common.exception.BizException;
import com.infrahub.common.support.TraceIdHolder;
import com.infrahub.release.dto.CollectionStateListResponse;
import com.infrahub.release.service.CollectionStateQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 采集状态只读接口。
 *
 * <p><b>只读</b>：本接口不触发任何 SSH 探测、不改写任何状态，只把后端调度器已记录的结果读出来。
 * 界面轮询它不会造成真实主机被扫描、也不会写库（契约 §6.2）。
 */
@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/api/monitor/collection-states")
public class MonitorCollectionStateController {

    /** 单次批量查询的目标数上限，避免超长 IN 列表 */
    private static final int MAX_TARGET_IDS = 200;

    private final CollectionStateQueryService collectionStateQueryService;

    /**
     * 按目标批量读取采集状态。
     *
     * @param targetType 目标类型：HOST / SERVICE_INSTANCE
     * @param targetIds  目标主键，逗号分隔
     */
    @GetMapping
    @PreAuthorize("hasAnyAuthority('MONITOR_VIEW','RELEASE_VIEW','SYSTEM_ADMIN')")
    public ApiResponse<CollectionStateListResponse> listStates(@RequestParam String targetType,
                                                               @RequestParam List<Long> targetIds) {
        if (targetIds.size() > MAX_TARGET_IDS) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "一次最多查询 " + MAX_TARGET_IDS + " 个目标");
        }
        return ApiResponse.success(
                collectionStateQueryService.listStates(targetType, targetIds), TraceIdHolder.get());
    }
}
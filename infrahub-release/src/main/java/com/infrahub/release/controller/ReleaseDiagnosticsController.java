package com.infrahub.release.controller;

import com.infrahub.common.api.ApiResponse;
import com.infrahub.common.support.TraceIdHolder;
import com.infrahub.release.dto.DatabaseSequenceHealth;
import com.infrahub.release.dto.TestDataCleanupSqlResponse;
import com.infrahub.release.dto.TestDataPreviewGroup;
import com.infrahub.release.service.DatabaseSequenceHealthService;
import com.infrahub.release.service.TestDataCleanupSqlService;
import com.infrahub.release.service.TestDataPreviewService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 发布诊断控制器
 * V1.8: 提供序列健康诊断和测试数据治理预览功能
 * V1.9: 新增安全清理SQL生成功能
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/release-diagnostics")
@PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
@Slf4j
public class ReleaseDiagnosticsController {

    private final DatabaseSequenceHealthService databaseSequenceHealthService;
    private final TestDataPreviewService testDataPreviewService;
    private final TestDataCleanupSqlService testDataCleanupSqlService;

    /**
     * 获取数据库序列健康状态
     * 检查release_artifact, release_version等表的序列是否正常
     */
    @GetMapping("/sequences")
    public ApiResponse<List<DatabaseSequenceHealth>> sequenceHealth() {
        log.info("Fetching database sequence health status");
        return ApiResponse.success(databaseSequenceHealthService.checkDefaultSequences(), TraceIdHolder.get());
    }

    /**
     * 获取测试数据治理预览
     * 展示V1.4/V1.5/V1.6真实证据链和E2E临时数据
     * V1.8: 只读预览，不提供删除功能
     */
    @GetMapping("/test-data-preview")
    public ApiResponse<List<TestDataPreviewGroup>> testDataPreview() {
        log.info("Fetching test data governance preview");
        return ApiResponse.success(testDataPreviewService.getTestDataPreview(), TraceIdHolder.get());
    }

    /**
     * 生成测试数据清理SQL脚本
     * V1.9: 生成安全的清理SQL，包含ROLLBACK，不自动执行
     * 只包含E2E临时数据，不包含真实证据链
     */
    @GetMapping("/test-data-cleanup-sql")
    public ApiResponse<TestDataCleanupSqlResponse> generateCleanupSql() {
        log.info("Generating test data cleanup SQL");
        return ApiResponse.success(testDataCleanupSqlService.generateCleanupSql(), TraceIdHolder.get());
    }
}

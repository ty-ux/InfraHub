package com.infrahub.release.controller;

import com.infrahub.common.api.ApiResponse;
import com.infrahub.common.support.TraceIdHolder;
import com.infrahub.release.dto.GovernanceReportGenerateRequest;
import com.infrahub.release.dto.GovernanceReportPageResponse;
import com.infrahub.release.dto.GovernanceReportQuery;
import com.infrahub.release.dto.GovernanceReportResponse;
import com.infrahub.release.service.GovernanceReportService;
import com.infrahub.common.context.UserContextHolder;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * 治理运营报告 API 控制器
 * Contract: V2/M section 2.3
 *
 * API 路径冻结（Contract section 2.3）：
 * - GET /api/assets/governance-ops/reports - 报告列表查询 (M-B)
 * - GET /api/assets/governance-ops/reports/{id} - 报告详情查询 (M-B)
 * - POST /api/assets/governance-ops/reports/generate - 手动触发报告生成 (M-B)
 *
 * 边界约束（Contract section 1.3）：
 * - generate 为显式 POST 手动触发，不是自动定时任务
 * - 不生成 PDF
 * - 不发邮件
 * - 不做自动调度
 *
 * GET 查询无写副作用（Contract section 8.4）。
 */
@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/api/assets/governance-ops/reports")
public class GovernanceReportController {

    private final GovernanceReportService reportService;

    /**
     * 报告列表查询
     * Contract: GET /api/assets/governance-ops/reports
     *
     * GET 查询无写副作用。
     *
     * @param query 查询参数
     * @return 分页响应
     */
    @GetMapping
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<GovernanceReportPageResponse> listReports(@Valid GovernanceReportQuery query) {
        GovernanceReportPageResponse response = reportService.queryReports(query);
        return ApiResponse.success(response, TraceIdHolder.get());
    }

    /**
     * 报告详情查询
     * Contract: GET /api/assets/governance-ops/reports/{id}
     *
     * GET 查询无写副作用。
     *
     * @param id 报告ID
     * @return 报告响应
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<GovernanceReportResponse> getReport(@PathVariable Long id) {
        GovernanceReportResponse response = reportService.getReportById(id);
        if (response == null) {
            return ApiResponse.failure("NOT_FOUND", "Report not found", TraceIdHolder.get());
        }
        return ApiResponse.success(response, TraceIdHolder.get());
    }

    /**
     * 手动触发报告生成
     * Contract: POST /api/assets/governance-ops/reports/generate
     *
     * 只写报告记录，不触发任何自动动作。
     * 不生成 PDF，不发邮件，不做自动调度。
     *
     * @param request 生成请求
     * @return 报告响应
     */
    @PostMapping("/generate")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<GovernanceReportResponse> generateReport(@Valid @RequestBody GovernanceReportGenerateRequest request) {
        Long userId = UserContextHolder.getUserIdOrDefault(1L);
        GovernanceReportResponse response = reportService.generateReport(request, userId);
        return ApiResponse.success(response, TraceIdHolder.get());
    }
}
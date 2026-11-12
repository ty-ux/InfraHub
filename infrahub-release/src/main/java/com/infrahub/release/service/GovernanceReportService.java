package com.infrahub.release.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.infrahub.release.dto.*;
import com.infrahub.release.entity.GovernanceReport;
import com.infrahub.release.mapper.GovernanceReportMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 治理运营报告服务
 * Contract: v2-stage-m-contract.md
 * M-B: 周期报告生成与查询 API 后端实现
 *
 * 边界约束：
 * - generate 是显式手动触发，不做自动调度
 * - 不生成 PDF
 * - 不发邮件
 * - 不修改 J/K/L 状态机
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GovernanceReportService {

    private final GovernanceReportMapper reportMapper;
    private final GovernanceOpsService governanceOpsService;
    private final GovernanceOpsSlaService slaService;
    private final GovernanceOpsEffectivenessService effectivenessService;
    private final GovernanceOpsReviewItemService reviewItemService;
    private final ObjectMapper objectMapper;

    /**
     * 生成治理运营报告
     * Contract: POST /api/assets/governance-ops/reports/generate
     *
     * 只写报告记录，不触发任何自动动作。
     *
     * @param request 生成请求
     * @param userId 当前用户ID
     * @return 报告响应
     */
    @Transactional
    public GovernanceReportResponse generateReport(GovernanceReportGenerateRequest request, Long userId) {
        log.info("Generating governance report: type={}, startTime={}, endTime={}, userId={}",
                request.getReportType(), request.getStartTime(), request.getEndTime(), userId);

        // 构建查询参数
        GovernanceOpsQuery opsQuery = buildOpsQuery(request);
        GovernanceOpsSlaQuery slaQuery = buildSlaQuery(request);
        GovernanceOpsEffectivenessQuery effectivenessQuery = buildEffectivenessQuery(request);
        GovernanceReviewItemQuery reviewItemQuery = buildReviewItemQuery(request);

        // 聚合各阶段数据
        Object overviewPayload = safeGetOverview(opsQuery);
        Object trendPayload = safeGetTrends(opsQuery);
        Object slaPayload = safeGetSla(slaQuery);
        Object effectivenessPayload = safeGetEffectiveness(effectivenessQuery);
        Object reviewItemPayload = safeGetReviewItems(reviewItemQuery);
        Object alertPayload = buildEmptyAlertPayload();

        // 计算汇总数据
        Object summaryPayload = buildSummaryPayload(
                overviewPayload, trendPayload, slaPayload,
                effectivenessPayload, reviewItemPayload, alertPayload);

        // 生成报告编号
        String reportNo = generateReportNo();

        // 构建报告标题
        String title = buildReportTitle(request);

        // 创建报告实体
        GovernanceReport report = new GovernanceReport();
        report.setReportNo(reportNo);
        report.setReportType(request.getReportType());
        report.setTitle(title);
        report.setStartTime(request.getStartTime());
        report.setEndTime(request.getEndTime());
        report.setEnvId(request.getEnvId());
        report.setResourcePoolId(request.getResourcePoolId());
        report.setServiceId(request.getServiceId());
        report.setSummaryPayload(summaryPayload);
        report.setOverviewPayload(overviewPayload);
        report.setTrendPayload(trendPayload);
        report.setSlaPayload(slaPayload);
        report.setEffectivenessPayload(effectivenessPayload);
        report.setReviewItemPayload(reviewItemPayload);
        report.setAlertPayload(alertPayload);
        report.setGeneratedAt(OffsetDateTime.now());
        report.setGeneratedBy(userId);
        report.setCreatedAt(OffsetDateTime.now());

        reportMapper.insert(report);

        log.info("Report generated successfully: id={}, reportNo={}", report.getId(), reportNo);

        return toResponse(report);
    }

    /**
     * 查询报告列表
     * Contract: GET /api/assets/governance-ops/reports
     *
     * GET 查询无写副作用。
     *
     * @param query 查询参数
     * @return 分页响应
     */
    @Transactional(readOnly = true)
    public GovernanceReportPageResponse queryReports(GovernanceReportQuery query) {
        int pageNum = query.getPageNum() != null ? query.getPageNum() : 1;
        int pageSize = query.getPageSize() != null ? query.getPageSize() : 20;

        Page<GovernanceReport> page = new Page<>(pageNum, pageSize);
        Page<GovernanceReport> result = reportMapper.queryReports(
                page,
                query.getReportType(),
                query.getEnvId(),
                query.getResourcePoolId(),
                query.getServiceId(),
                query.getStartTime(),
                query.getEndTime());

        List<GovernanceReportResponse> records = result.getRecords().stream()
                .map(this::toResponse)
                .toList();

        GovernanceReportPageResponse response = new GovernanceReportPageResponse();
        response.setRecords(records);
        response.setTotal(result.getTotal());
        response.setPageNum(pageNum);
        response.setPageSize(pageSize);
        return response;
    }

    /**
     * 查询报告详情
     * Contract: GET /api/assets/governance-ops/reports/{id}
     *
     * GET 查询无写副作用。
     *
     * @param id 报告ID
     * @return 报告响应，不存在返回null
     */
    @Transactional(readOnly = true)
    public GovernanceReportResponse getReportById(Long id) {
        GovernanceReport report = reportMapper.selectById(id);
        if (report == null) {
            return null;
        }
        return toResponse(report);
    }

    // ==================== Private Helper Methods ====================

    private GovernanceOpsQuery buildOpsQuery(GovernanceReportGenerateRequest request) {
        GovernanceOpsQuery query = new GovernanceOpsQuery();
        query.setEnvId(request.getEnvId());
        query.setResourcePoolId(request.getResourcePoolId());
        query.setServiceId(request.getServiceId());
        query.setStartTime(request.getStartTime());
        query.setEndTime(request.getEndTime());
        return query;
    }

    private GovernanceOpsSlaQuery buildSlaQuery(GovernanceReportGenerateRequest request) {
        GovernanceOpsSlaQuery query = new GovernanceOpsSlaQuery();
        query.setEnvId(request.getEnvId());
        query.setResourcePoolId(request.getResourcePoolId());
        query.setStartTime(request.getStartTime());
        query.setEndTime(request.getEndTime());
        return query;
    }

    private GovernanceOpsEffectivenessQuery buildEffectivenessQuery(GovernanceReportGenerateRequest request) {
        GovernanceOpsEffectivenessQuery query = new GovernanceOpsEffectivenessQuery();
        query.setStartTime(request.getStartTime());
        query.setEndTime(request.getEndTime());
        return query;
    }

    private GovernanceReviewItemQuery buildReviewItemQuery(GovernanceReportGenerateRequest request) {
        GovernanceReviewItemQuery query = new GovernanceReviewItemQuery();
        query.setEnvId(request.getEnvId());
        query.setResourcePoolId(request.getResourcePoolId());
        query.setServiceId(request.getServiceId());
        return query;
    }

    private Object safeGetOverview(GovernanceOpsQuery query) {
        try {
            return governanceOpsService.queryOverview(query);
        } catch (Exception e) {
            log.warn("Failed to get overview payload: {}", e.getMessage());
            return buildEmptyOverviewPayload();
        }
    }

    private Object safeGetTrends(GovernanceOpsQuery query) {
        try {
            return governanceOpsService.queryTrends(query);
        } catch (Exception e) {
            log.warn("Failed to get trend payload: {}", e.getMessage());
            return List.of();
        }
    }

    private Object safeGetSla(GovernanceOpsSlaQuery query) {
        try {
            return slaService.calculateSla(query);
        } catch (Exception e) {
            log.warn("Failed to get SLA payload: {}", e.getMessage());
            return buildEmptySlaPayload();
        }
    }

    private Object safeGetEffectiveness(GovernanceOpsEffectivenessQuery query) {
        try {
            return effectivenessService.calculateEffectiveness(query);
        } catch (Exception e) {
            log.warn("Failed to get effectiveness payload: {}", e.getMessage());
            return List.of();
        }
    }

    private Object safeGetReviewItems(GovernanceReviewItemQuery query) {
        try {
            return reviewItemService.queryReviewItems(query);
        } catch (Exception e) {
            log.warn("Failed to get review item payload: {}", e.getMessage());
            return buildEmptyReviewItemPayload();
        }
    }

    private Object buildSummaryPayload(Object overview, Object trends, Object sla,
                                        Object effectiveness, Object reviewItems, Object alerts) {
        Map<String, Object> summary = new HashMap<>();

        // 从 overview 提取关键指标
        if (overview instanceof GovernanceOpsOverviewResponse overviewResp) {
            summary.put("totalRiskCount", overviewResp.getTotalRiskCount());
            summary.put("criticalRiskCount", overviewResp.getCriticalRiskCount());
            summary.put("warningRiskCount", overviewResp.getWarningRiskCount());
            summary.put("openSuggestionCount", overviewResp.getOpenSuggestionCount());
            summary.put("failedExecutionCount", overviewResp.getFailedExecutionCount());
            summary.put("impactRecordedCount", overviewResp.getImpactRecordedCount());
        }

        // 从 SLA 提取关键指标
        if (sla instanceof GovernanceSlaResponse slaResp) {
            summary.put("slaHitRate", slaResp.getSlaHitRate());
            summary.put("overdueSuggestionCount", slaResp.getOverdueSuggestionCount());
            summary.put("overduePlanCount", slaResp.getOverduePlanCount());
        }

        // 告警统计（空数据时为0）
        summary.put("alertCount", 0);

        summary.put("generatedAt", OffsetDateTime.now().toString());
        summary.put("manualOnly", true);

        return summary;
    }

    private Object buildEmptyOverviewPayload() {
        GovernanceOpsOverviewResponse response = new GovernanceOpsOverviewResponse();
        response.setTotalRiskCount(0L);
        response.setCriticalRiskCount(0L);
        response.setWarningRiskCount(0L);
        response.setOpenSuggestionCount(0L);
        response.setConfirmedPlanCount(0L);
        response.setRunningExecutionCount(0L);
        response.setCompletedExecutionCount(0L);
        response.setFailedExecutionCount(0L);
        response.setImpactRecordedCount(0L);
        response.setAvgCloseDurationMs(0.0);
        response.setManualOnly(true);
        return response;
    }

    private Object buildEmptySlaPayload() {
        GovernanceSlaResponse response = new GovernanceSlaResponse();
        response.setAvgSuggestionAckDurationMs(0.0);
        response.setAvgPlanConfirmDurationMs(0.0);
        response.setAvgExecutionCompleteDurationMs(0.0);
        response.setOverdueSuggestionCount(0L);
        response.setOverduePlanCount(0L);
        response.setOverdueExecutionCount(0L);
        response.setSlaTargetMs(0L);
        response.setSlaHitRate(0.0);
        return response;
    }

    private Object buildEmptyReviewItemPayload() {
        GovernanceReviewItemPageResponse response = new GovernanceReviewItemPageResponse();
        response.setRecords(List.of());
        response.setTotal(0L);
        response.setPageNum(1);
        response.setPageSize(20);
        return response;
    }

    private Object buildEmptyAlertPayload() {
        Map<String, Object> alertPayload = new HashMap<>();
        alertPayload.put("totalAlertCount", 0);
        alertPayload.put("openAlertCount", 0);
        alertPayload.put("ackedAlertCount", 0);
        alertPayload.put("resolvedAlertCount", 0);
        alertPayload.put("alerts", List.of());
        return alertPayload;
    }

    private String generateReportNo() {
        // 使用数据库函数生成报告编号
        // 格式: GR + YYYYMMDD + 6位序号
        String datePart = OffsetDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        long count = reportMapper.selectCount(
                new LambdaQueryWrapper<GovernanceReport>()
                        .likeRight(GovernanceReport::getReportNo, "GR" + datePart))
                + 1;
        return "GR" + datePart + String.format("%06d", count);
    }

    private String buildReportTitle(GovernanceReportGenerateRequest request) {
        if (request.getTitle() != null && !request.getTitle().isEmpty()) {
            return request.getTitle();
        }

        String typeLabel;
        if ("DAILY".equals(request.getReportType())) {
            typeLabel = "日报";
        } else if ("WEEKLY".equals(request.getReportType())) {
            typeLabel = "周报";
        } else if ("MONTHLY".equals(request.getReportType())) {
            typeLabel = "月报";
        } else if ("CUSTOM".equals(request.getReportType())) {
            typeLabel = "自定义报告";
        } else {
            typeLabel = "报告";
        }

        String dateRange = request.getStartTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
                + " ~ " + request.getEndTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));

        return "治理运营" + typeLabel + " (" + dateRange + ")";
    }

    private GovernanceReportResponse toResponse(GovernanceReport report) {
        GovernanceReportResponse response = new GovernanceReportResponse();
        response.setId(report.getId());
        response.setReportNo(report.getReportNo());
        response.setReportType(report.getReportType());
        response.setTitle(report.getTitle());
        response.setStartTime(report.getStartTime());
        response.setEndTime(report.getEndTime());
        response.setSummaryPayload(report.getSummaryPayload());
        response.setOverviewPayload(report.getOverviewPayload());
        response.setTrendPayload(report.getTrendPayload());
        response.setSlaPayload(report.getSlaPayload());
        response.setEffectivenessPayload(report.getEffectivenessPayload());
        response.setReviewItemPayload(report.getReviewItemPayload());
        response.setAlertPayload(report.getAlertPayload());
        response.setGeneratedAt(report.getGeneratedAt());
        response.setGeneratedBy(report.getGeneratedBy());
        return response;
    }
}

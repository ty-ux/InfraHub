package com.infrahub.release.controller;

import com.infrahub.common.api.ApiResponse;
import com.infrahub.common.support.TraceIdHolder;
import com.infrahub.release.dto.GovernanceEffectivenessResponse;
import com.infrahub.release.dto.GovernanceOpsEffectivenessQuery;
import com.infrahub.release.dto.GovernanceOpsOverviewResponse;
import com.infrahub.release.dto.GovernanceOpsQuery;
import com.infrahub.release.dto.GovernanceOpsSlaQuery;
import com.infrahub.release.dto.GovernanceReviewItemPageResponse;
import com.infrahub.release.dto.GovernanceReviewItemQuery;
import com.infrahub.release.dto.GovernanceSlaResponse;
import com.infrahub.release.dto.GovernanceTrendPoint;
import com.infrahub.release.service.GovernanceOpsEffectivenessService;
import com.infrahub.release.service.GovernanceOpsReviewItemService;
import com.infrahub.release.service.GovernanceOpsService;
import com.infrahub.release.service.GovernanceOpsSlaService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 治理运营 API 控制器.
 * Contract: V2/L section 2.
 *
 * API 路径冻结（Contract section 2.1）：
 * - GET /api/assets/governance-ops/overview - 治理运营总览 (L-A)
 * - GET /api/assets/governance-ops/trends - 治理趋势数据 (L-A)
 * - GET /api/assets/governance-ops/sla - SLA 闭环效率统计 (L-B)
 * - GET /api/assets/governance-ops/effectiveness - 治理效果评估 (L-B)
 * - GET /api/assets/governance-ops/review-items - 复盘条目列表 (L-C)
 *
 * 只读 API（Contract section 2.2）：
 * 所有 L 阶段 API 均为 GET 请求，无写副作用。
 */
@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/api/assets/governance-ops")
public class GovernanceOpsController {

    private final GovernanceOpsService governanceOpsService;
    private final GovernanceOpsSlaService slaService;
    private final GovernanceOpsEffectivenessService effectivenessService;
    private final GovernanceOpsReviewItemService reviewItemService;

    /**
     * 治理运营总览.
     * Contract: GET /api/assets/governance-ops/overview
     *
     * @param query 查询参数
     * @return 总览响应
     */
    @GetMapping("/overview")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<GovernanceOpsOverviewResponse> getOverview(@Valid GovernanceOpsQuery query) {
        GovernanceOpsOverviewResponse response = governanceOpsService.queryOverview(query);
        return ApiResponse.success(response, TraceIdHolder.get());
    }

    /**
     * 治理趋势数据.
     * Contract: GET /api/assets/governance-ops/trends
     *
     * @param query 查询参数
     * @return 趋势数据点列表
     */
    @GetMapping("/trends")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<List<GovernanceTrendPoint>> getTrends(@Valid GovernanceOpsQuery query) {
        List<GovernanceTrendPoint> response = governanceOpsService.queryTrends(query);
        return ApiResponse.success(response, TraceIdHolder.get());
    }

    /**
     * SLA 闭环效率统计.
     * Contract: GET /api/assets/governance-ops/sla
     *
     * @param query 查询参数
     * @return SLA 响应
     */
    @GetMapping("/sla")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<GovernanceSlaResponse> getSla(GovernanceOpsSlaQuery query) {
        GovernanceSlaResponse response = slaService.calculateSla(query);
        return ApiResponse.success(response, TraceIdHolder.get());
    }

    /**
     * 治理效果评估.
     * Contract: GET /api/assets/governance-ops/effectiveness
     *
     * @param query 查询参数
     * @return 效果评估响应列表
     */
    @GetMapping("/effectiveness")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<List<GovernanceEffectivenessResponse>> getEffectiveness(GovernanceOpsEffectivenessQuery query) {
        List<GovernanceEffectivenessResponse> response = effectivenessService.calculateEffectiveness(query);
        return ApiResponse.success(response, TraceIdHolder.get());
    }

    /**
     * 复盘条目列表.
     * Contract: GET /api/assets/governance-ops/review-items
     *
     * @param query 查询参数
     * @return 复盘条目分页响应
     */
    @GetMapping("/review-items")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<GovernanceReviewItemPageResponse> getReviewItems(GovernanceReviewItemQuery query) {
        GovernanceReviewItemPageResponse response = reviewItemService.queryReviewItems(query);
        return ApiResponse.success(response, TraceIdHolder.get());
    }
}

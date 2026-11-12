package com.infrahub.release.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.infrahub.common.api.ApiResponse;
import com.infrahub.common.api.PageResponse;
import com.infrahub.common.support.TraceIdHolder;
import com.infrahub.pipeline.dto.RunPipelineResponse;
import com.infrahub.pipeline.service.PipelineRunService;
import com.infrahub.release.dto.*;
import com.infrahub.release.dto.ExecuteResult;
import com.infrahub.release.dto.CanaryPlanResponse;
import com.infrahub.release.dto.CreateCanaryPlanRequest;
import com.infrahub.release.entity.ReleaseCanaryBatch;
import com.infrahub.release.entity.ReleaseCanaryPlan;
import com.infrahub.release.entity.ReleaseOrder;
import com.infrahub.release.entity.RuntimeDeployRecord;
import com.infrahub.release.mapper.ReleaseCanaryBatchMapper;
import com.infrahub.release.mapper.ReleaseCanaryPlanMapper;
import com.infrahub.release.service.DeployFailureCategorizer;
import com.infrahub.release.service.ReleaseCanaryService;
import com.infrahub.release.service.ReleaseCanaryGateEvaluationService;
import com.infrahub.release.service.ReleaseOrderService;
import com.infrahub.release.service.ReleasePipelineService;
import com.infrahub.release.service.ReleaseExecutorConfigService;
import com.infrahub.release.service.RuntimeDeployRecordService;
import com.infrahub.release.service.RollbackCandidateService;
import com.infrahub.release.service.PrecheckSummaryService;
import com.infrahub.release.service.ExecutionContextService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;

import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/api/releases")
public class ReleaseController {

    private final ReleaseOrderService releaseOrderService;
    private final ReleasePipelineService releasePipelineService;
    private final RuntimeDeployRecordService runtimeDeployRecordService;
    private final PipelineRunService pipelineRunService;
    private final DeployFailureCategorizer deployFailureCategorizer;
    private final ReleaseCanaryService releaseCanaryService;
    private final ReleaseCanaryGateEvaluationService gateEvaluationService;
    private final ReleaseCanaryPlanMapper releaseCanaryPlanMapper;
    private final ReleaseCanaryBatchMapper releaseCanaryBatchMapper;
    private final ReleaseExecutorConfigService releaseExecutorConfigService;
    private final RollbackCandidateService rollbackCandidateService;
    private final PrecheckSummaryService precheckSummaryService;
    private final ExecutionContextService executionContextService;

    @GetMapping
    @PreAuthorize("hasAnyAuthority('RELEASE_VIEW','SYSTEM_ADMIN')")
    public ApiResponse<PageResponse<ReleaseOrderListVo>> page(@Valid ReleaseOrderQuery query) {
        LambdaQueryWrapper<ReleaseOrder> wrapper = new LambdaQueryWrapper<>();

        if (query.getKeyword() != null && !query.getKeyword().isBlank()) {
            wrapper.like(ReleaseOrder::getOrderNo, query.getKeyword());
        }
        if (query.getStatus() != null && !query.getStatus().isBlank()) {
            String statusValue = query.getStatus().trim();
            if (statusValue.contains(",")) {
                List<String> statuses = java.util.Arrays.stream(statusValue.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .toList();
                wrapper.in(ReleaseOrder::getStatus, statuses);
            } else {
                wrapper.eq(ReleaseOrder::getStatus, statusValue);
            }
        }
        if (query.getAppId() != null) {
            wrapper.eq(ReleaseOrder::getAppId, query.getAppId());
        }
        if (query.getServiceId() != null) {
            wrapper.eq(ReleaseOrder::getServiceId, query.getServiceId());
        }
        if (query.getEnvId() != null) {
            wrapper.eq(ReleaseOrder::getEnvId, query.getEnvId());
        }
        if (query.getCreatedAtStart() != null) {
            wrapper.ge(ReleaseOrder::getCreatedAt, query.getCreatedAtStart());
        }
        if (query.getCreatedAtEnd() != null) {
            wrapper.le(ReleaseOrder::getCreatedAt, query.getCreatedAtEnd());
        }
        if (query.getHasFailureHint() != null) {
            if (Boolean.TRUE.equals(query.getHasFailureHint())) {
                wrapper.apply("EXISTS (SELECT 1 FROM runtime_deploy_record WHERE release_order_id = id AND deploy_status = 'FAILED')");
            } else {
                wrapper.apply("NOT EXISTS (SELECT 1 FROM runtime_deploy_record WHERE release_order_id = id AND deploy_status = 'FAILED')");
            }
        }
        if (query.getHasPipelineRun() != null) {
            if (Boolean.TRUE.equals(query.getHasPipelineRun())) {
                wrapper.apply("EXISTS (SELECT 1 FROM pipeline_run WHERE release_id = id)");
            } else {
                wrapper.apply("NOT EXISTS (SELECT 1 FROM pipeline_run WHERE release_id = id)");
            }
        }

        // Apply sorting based on sortMode and orderBy parameters
        if ("focus".equals(query.getSortMode())) {
            // V1.19: Focus mode sorting - prioritize active/failed orders
            // Priority: RUNNING (0) > FAILED (1) > hasDeployRecords (2) > others (3), then by createdAt desc
            wrapper.last("""
                ORDER BY
                    CASE
                        WHEN status = 'RUNNING' THEN 0
                        WHEN status = 'FAILED' THEN 1
                        WHEN EXISTS (SELECT 1 FROM runtime_deploy_record WHERE release_order_id = release_order.id) THEN 2
                        ELSE 3
                    END,
                    created_at DESC
                """);
        } else if ("status".equals(query.getOrderBy())) {
            if ("asc".equalsIgnoreCase(query.getOrderDirection())) {
                wrapper.orderByAsc(ReleaseOrder::getStatus);
            } else {
                // For failure focus: use CASE expression to sort FAILED first, then by status
                wrapper.last("ORDER BY CASE WHEN status = 'FAILED' THEN 0 ELSE 1 END, status DESC");
            }
        } else {
            wrapper.orderByDesc(ReleaseOrder::getCreatedAt);
        }

        Page<ReleaseOrder> page = releaseOrderService.page(
                new Page<>(query.getPageNum(), query.getPageSize()),
                wrapper
        );

        List<ReleaseOrderListVo> voList = page.getRecords().stream()
                .map(this::toVoWithEnrichment)
                .toList();

        return ApiResponse.success(
                PageResponse.<ReleaseOrderListVo>builder()
                        .pageNum(page.getCurrent())
                        .pageSize(page.getSize())
                        .total(page.getTotal())
                        .records(voList)
                        .build(),
                TraceIdHolder.get()
        );
    }

    private ReleaseOrderListVo toVoWithEnrichment(ReleaseOrder entity) {
        ReleaseOrderListVo vo = ReleaseOrderListVo.fromEntity(entity);

        // 查询部署记录存在性
        boolean hasDeployRecords = runtimeDeployRecordService.existsByReleaseOrderId(entity.getId());
        vo.setHasDeployRecords(hasDeployRecords);

        // 查询最近部署记录状态
        RuntimeDeployRecord latestRecord = runtimeDeployRecordService.getLatestByReleaseOrderId(entity.getId());
        if (latestRecord != null) {
            vo.setLatestDeployStatus(latestRecord.getDeployStatus());

            // 如果有失败记录，生成失败提示
            if ("FAILED".equals(latestRecord.getDeployStatus()) && latestRecord.getDeployLog() != null) {
                String hint = deployFailureCategorizer.categorize(latestRecord.getDeployLog());
                vo.setLatestFailureHint(hint);
            }
        }

        // 查询是否有关联流水线
        boolean hasPipelineRun = pipelineRunService.existsByReleaseId(entity.getId());
        vo.setHasPipelineRun(hasPipelineRun);

        // E1: 查询灰度计划状态（仅 CANARY 类型）
        if ("CANARY".equals(entity.getReleaseType())) {
            ReleaseCanaryPlan canaryPlan = releaseCanaryPlanMapper.findByReleaseOrderId(entity.getId());
            if (canaryPlan != null) {
                String planStatus = canaryPlan.getStatus();
                // 推导 WAITING_NEXT 状态：RUNNING + 有成功批次 + 还有待执行批次
                if ("RUNNING".equals(planStatus)) {
                    List<ReleaseCanaryBatch> batches = releaseCanaryBatchMapper.findByPlanId(canaryPlan.getId());
                    boolean hasSuccessBatch = batches.stream().anyMatch(b -> "SUCCESS".equals(b.getStatus()));
                    boolean hasPendingBatch = batches.stream().anyMatch(b -> "PENDING".equals(b.getStatus()));
                    if (hasSuccessBatch && hasPendingBatch) {
                        // E2: 门禁启用时，还需检查上一成功批次的门禁状态
                        boolean gatePassed = true;
                        if (Boolean.TRUE.equals(canaryPlan.getQualityGateEnabled())) {
                            // 找到最新成功批次
                            ReleaseCanaryBatch latestSuccess = batches.stream()
                                    .filter(b -> "SUCCESS".equals(b.getStatus()))
                                    .max((a, b) -> Integer.compare(a.getBatchNo(), b.getBatchNo()))
                                    .orElse(null);
                            if (latestSuccess != null &&
                                !ReleaseCanaryBatch.GATE_STATUS_PASSED.equals(latestSuccess.getQualityGateStatus())) {
                                gatePassed = false;
                            }
                        }
                        if (gatePassed) {
                            planStatus = "WAITING_NEXT";
                        }
                    }
                }
                vo.setCanaryPlanStatus(planStatus);
            }
        }

        return vo;
    }

    @PostMapping
    @PreAuthorize("hasAnyAuthority('RELEASE_CREATE','SYSTEM_ADMIN')")
    public ApiResponse<Boolean> create(@RequestBody ReleaseOrder body) {
        return ApiResponse.success(releaseOrderService.createReleaseOrder(body), TraceIdHolder.get());
    }

    @PostMapping("/{id}/execute")
    @PreAuthorize("hasAnyAuthority('RELEASE_EXECUTE','SYSTEM_ADMIN')")
    public ApiResponse<ExecuteResult> execute(@PathVariable Long id) {
        return ApiResponse.success(releaseOrderService.executeReleaseOrderWithApproval(id), TraceIdHolder.get());
    }

    @PostMapping("/{id}/precheck")
    @PreAuthorize("hasAnyAuthority('RELEASE_EXECUTE','SYSTEM_ADMIN')")
    public ApiResponse<List<ReleasePrecheckInstanceResponse>> precheck(@PathVariable Long id) {
        return ApiResponse.success(releaseOrderService.precheckReleaseOrder(id), TraceIdHolder.get());
    }

    @PostMapping("/{id}/execute-pipeline")
    @PreAuthorize("hasAnyAuthority('RELEASE_EXECUTE','SYSTEM_ADMIN')")
    public ApiResponse<RunPipelineResponse> executePipeline(@PathVariable Long id,
                                                            @RequestBody ExecuteReleasePipelineRequest request) {
        return ApiResponse.success(releasePipelineService.executeReleaseViaPipeline(id, request), TraceIdHolder.get());
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAnyAuthority('RELEASE_CANCEL','SYSTEM_ADMIN')")
    public ApiResponse<Boolean> cancel(@PathVariable Long id) {
        return ApiResponse.success(releaseOrderService.cancelReleaseOrder(id), TraceIdHolder.get());
    }

    @GetMapping("/{id}/deploy-records")
    @PreAuthorize("hasAnyAuthority('RELEASE_VIEW','SYSTEM_ADMIN')")
    public ApiResponse<List<RuntimeDeployRecord>> deployRecords(@PathVariable Long id) {
        return ApiResponse.success(runtimeDeployRecordService.listByReleaseOrderId(id), TraceIdHolder.get());
    }

    @GetMapping("/{id}/detail")
    @PreAuthorize("hasAnyAuthority('RELEASE_VIEW','SYSTEM_ADMIN')")
    public ApiResponse<ReleaseOrderDetailResponse> detail(@PathVariable Long id) {
        return ApiResponse.success(releaseOrderService.getDetail(id), TraceIdHolder.get());
    }

    @PostMapping("/{id}/canary-plan")
    @PreAuthorize("hasAnyAuthority('RELEASE_EXECUTE','SYSTEM_ADMIN')")
    public ApiResponse<CanaryPlanResponse> createCanaryPlan(
            @PathVariable Long id,
            @RequestBody @Valid CreateCanaryPlanRequest request) {
        return ApiResponse.success(releaseCanaryService.createPlan(id, request), TraceIdHolder.get());
    }

    @GetMapping("/{id}/canary-plan")
    @PreAuthorize("hasAnyAuthority('RELEASE_VIEW','SYSTEM_ADMIN')")
    public ApiResponse<CanaryPlanResponse> getCanaryPlan(@PathVariable Long id) {
        CanaryPlanResponse plan = releaseCanaryService.getPlan(id);
        return ApiResponse.success(plan, TraceIdHolder.get());
    }

    @PostMapping("/{id}/canary/start")
    @PreAuthorize("hasAnyAuthority('RELEASE_EXECUTE','SYSTEM_ADMIN')")
    public ApiResponse<CanaryPlanResponse> startCanary(@PathVariable Long id) {
        return ApiResponse.success(releaseCanaryService.start(id), TraceIdHolder.get());
    }

    @PostMapping("/{id}/canary/continue")
    @PreAuthorize("hasAnyAuthority('RELEASE_EXECUTE','SYSTEM_ADMIN')")
    public ApiResponse<CanaryPlanResponse> continueCanary(@PathVariable Long id) {
        return ApiResponse.success(releaseCanaryService.continueNext(id), TraceIdHolder.get());
    }

    @PostMapping("/{id}/canary/pause")
    @PreAuthorize("hasAnyAuthority('RELEASE_EXECUTE','SYSTEM_ADMIN')")
    public ApiResponse<CanaryPlanResponse> pauseCanary(@PathVariable Long id) {
        return ApiResponse.success(releaseCanaryService.pause(id), TraceIdHolder.get());
    }

    // E2: 手动重评估质量门禁
    @PostMapping("/{id}/canary/batches/{batchId}/evaluate-gate")
    @PreAuthorize("hasAnyAuthority('RELEASE_EXECUTE','SYSTEM_ADMIN')")
    public ApiResponse<CanaryPlanResponse> evaluateBatchGate(
            @PathVariable Long id,
            @PathVariable Long batchId) {
        return ApiResponse.success(releaseCanaryService.evaluateGate(id, batchId), TraceIdHolder.get());
    }

    // E3.1: 查询门禁评估历史
    @GetMapping("/{id}/canary/batches/{batchId}/gate-evaluations")
    @PreAuthorize("hasAnyAuthority('RELEASE_VIEW','SYSTEM_ADMIN')")
    public ApiResponse<List<GateEvaluationHistoryResponse>> getBatchGateEvaluations(
            @PathVariable Long id,
            @PathVariable Long batchId) {
        return ApiResponse.success(gateEvaluationService.getBatchEvaluations(id, batchId), TraceIdHolder.get());
    }

    // ============ F-A: 执行器配置管理 API ============

    @GetMapping("/executor-configs")
    @PreAuthorize("hasAnyAuthority('RELEASE_VIEW','SYSTEM_ADMIN')")
    public ApiResponse<List<ReleaseExecutorConfigResponse>> listExecutorConfigs(
            @RequestParam(required = false) Long envId) {
        List<com.infrahub.release.entity.ReleaseExecutorConfig> configs;
        if (envId != null) {
            configs = releaseExecutorConfigService.findEnabledByEnvId(envId);
        } else {
            configs = releaseExecutorConfigService.lambdaQuery()
                    .orderByAsc(com.infrahub.release.entity.ReleaseExecutorConfig::getEnvId)
                    .list();
        }
        List<ReleaseExecutorConfigResponse> responses = configs.stream()
                .map(ReleaseExecutorConfigResponse::fromEntity)
                .toList();
        return ApiResponse.success(responses, TraceIdHolder.get());
    }

    @GetMapping("/executor-configs/{id}")
    @PreAuthorize("hasAnyAuthority('RELEASE_VIEW','SYSTEM_ADMIN')")
    public ApiResponse<ReleaseExecutorConfigResponse> getExecutorConfig(@PathVariable Long id) {
        com.infrahub.release.entity.ReleaseExecutorConfig config = releaseExecutorConfigService.getById(id);
        if (config == null) {
            return ApiResponse.success(null, TraceIdHolder.get());
        }
        return ApiResponse.success(ReleaseExecutorConfigResponse.fromEntity(config), TraceIdHolder.get());
    }

    @PostMapping("/executor-configs")
    @PreAuthorize("hasAnyAuthority('RELEASE_CREATE','SYSTEM_ADMIN')")
    public ApiResponse<ReleaseExecutorConfigResponse> createExecutorConfig(
            @RequestBody @Valid CreateReleaseExecutorConfigRequest request) {
        com.infrahub.release.entity.ReleaseExecutorConfig config = releaseExecutorConfigService.createConfig(request);
        return ApiResponse.success(ReleaseExecutorConfigResponse.fromEntity(config), TraceIdHolder.get());
    }

    @PutMapping("/executor-configs/{id}")
    @PreAuthorize("hasAnyAuthority('RELEASE_CREATE','SYSTEM_ADMIN')")
    public ApiResponse<ReleaseExecutorConfigResponse> updateExecutorConfig(
            @PathVariable Long id,
            @RequestBody @Valid CreateReleaseExecutorConfigRequest request) {
        com.infrahub.release.entity.ReleaseExecutorConfig config = releaseExecutorConfigService.updateConfig(id, request);
        return ApiResponse.success(ReleaseExecutorConfigResponse.fromEntity(config), TraceIdHolder.get());
    }

    @PostMapping("/executor-configs/{id}/toggle")
    @PreAuthorize("hasAnyAuthority('RELEASE_CREATE','SYSTEM_ADMIN')")
    public ApiResponse<Boolean> toggleExecutorConfig(
            @PathVariable Long id,
            @RequestParam boolean enabled) {
        releaseExecutorConfigService.toggleEnabled(id, enabled);
        return ApiResponse.success(true, TraceIdHolder.get());
    }

    // ============ T-B: 回滚候选版本查询 API ============

    /**
     * 查询指定发布单的回滚候选版本列表。
     *
     * Contract Section 2.2: GET /api/releases/{releaseOrderId}/rollback-candidates
     *
     * 此 API 为只读查询，不创建回滚单、不修改发布单、不触发回滚执行。
     */
    @GetMapping("/{id}/rollback-candidates")
    @PreAuthorize("hasAnyAuthority('RELEASE_VIEW','SYSTEM_ADMIN')")
    public ApiResponse<RollbackCandidatePageResponse> rollbackCandidates(
            @PathVariable Long id,
            @RequestParam(required = false) Integer pageNum,
            @RequestParam(required = false) Integer pageSize) {
        return ApiResponse.success(rollbackCandidateService.getRollbackCandidates(id, pageNum, pageSize), TraceIdHolder.get());
    }

    // ============ V-A: Precheck Summary API ============

    @GetMapping("/{id}/precheck-summary")
    @PreAuthorize("hasAnyAuthority('RELEASE_VIEW','SYSTEM_ADMIN')")
    public ApiResponse<PrecheckSummaryResponse> precheckSummary(@PathVariable Long id) {
        return ApiResponse.success(precheckSummaryService.getReleasePrecheckSummary(id), TraceIdHolder.get());
    }

    // ============ V-A: Execution Context API ============

    @GetMapping("/{id}/execution-context")
    @PreAuthorize("hasAnyAuthority('RELEASE_VIEW','SYSTEM_ADMIN')")
    public ApiResponse<ExecutionContextResponse> executionContext(@PathVariable Long id) {
        return ApiResponse.success(executionContextService.getReleaseExecutionContext(id), TraceIdHolder.get());
    }
}

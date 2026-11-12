package com.infrahub.release.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.infrahub.common.api.ApiResponse;
import com.infrahub.common.api.PageResponse;
import com.infrahub.common.support.TraceIdHolder;
import com.infrahub.base.service.BaseServiceEntityService;
import com.infrahub.release.dto.RollbackOrderDetailResponse;
import com.infrahub.release.dto.ApplicationRollbackRequest;
import com.infrahub.release.dto.RollbackOrderQuery;
import com.infrahub.release.dto.ReleasePrecheckInstanceResponse;
import com.infrahub.release.dto.ReleaseRollbackOrderVo;
import com.infrahub.release.dto.UpdateRollbackTargetRequest;
import com.infrahub.release.dto.ExecuteResult;
import com.infrahub.release.dto.PrecheckSummaryResponse;
import com.infrahub.release.dto.ExecutionContextResponse;
import com.infrahub.release.entity.ReleaseOrder;
import com.infrahub.release.entity.ReleaseRollbackOrder;
import com.infrahub.release.entity.RuntimeDeployRecord;
import com.infrahub.release.entity.RuntimeServiceInstance;
import com.infrahub.release.service.DeployFailureCategorizer;
import com.infrahub.release.service.ReleaseOrderService;
import com.infrahub.release.service.ReleaseRollbackOrderService;
import com.infrahub.release.service.RollbackExecutorSupportService;
import com.infrahub.release.service.RuntimeDeployRecordService;
import com.infrahub.release.service.PrecheckSummaryService;
import com.infrahub.release.service.ExecutionContextService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/api/rollbacks")
public class RollbackController {

    private final ReleaseRollbackOrderService releaseRollbackOrderService;
    private final RuntimeDeployRecordService runtimeDeployRecordService;
    private final DeployFailureCategorizer deployFailureCategorizer;
    private final PrecheckSummaryService precheckSummaryService;
    private final ExecutionContextService executionContextService;
    private final ReleaseOrderService releaseOrderService;
    private final RollbackExecutorSupportService rollbackExecutorSupportService;
    private final BaseServiceEntityService baseServiceEntityService;

    @GetMapping
    @PreAuthorize("hasAnyAuthority('ROLLBACK_VIEW','SYSTEM_ADMIN')")
    public ApiResponse<PageResponse<ReleaseRollbackOrderVo>> page(@Valid RollbackOrderQuery query) {
        LambdaQueryWrapper<ReleaseRollbackOrder> wrapper = new LambdaQueryWrapper<>();

        if (query.getKeyword() != null && !query.getKeyword().isBlank()) {
            wrapper.like(ReleaseRollbackOrder::getRollbackNo, query.getKeyword());
        }
        if (query.getReleaseOrderKeyword() != null && !query.getReleaseOrderKeyword().isBlank()) {
            wrapper.apply("release_order_id IN (SELECT id FROM release_order WHERE order_no LIKE {0})", "%" + query.getReleaseOrderKeyword() + "%");
        }
        if (query.getStatus() != null && !query.getStatus().isBlank()) {
            String statusValue = query.getStatus().trim();
            if (statusValue.contains(",")) {
                List<String> statuses = java.util.Arrays.stream(statusValue.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .toList();
                wrapper.in(ReleaseRollbackOrder::getStatus, statuses);
            } else {
                wrapper.eq(ReleaseRollbackOrder::getStatus, statusValue);
            }
        }
        if (query.getAppId() != null) wrapper.apply("(service_id IN (SELECT id FROM base_service WHERE app_id = {0}) "
                + "OR release_order_id IN (SELECT id FROM release_order WHERE app_id = {0}))", query.getAppId());
        if (query.getServiceId() != null) wrapper.apply("(service_id = {0} OR release_order_id IN (SELECT id FROM release_order WHERE service_id = {0}))", query.getServiceId());
        if (query.getEnvId() != null) wrapper.apply("(env_id = {0} OR release_order_id IN (SELECT id FROM release_order WHERE env_id = {0}))", query.getEnvId());
        if (query.getCreatedAtStart() != null) {
            wrapper.ge(ReleaseRollbackOrder::getCreatedAt, query.getCreatedAtStart());
        }
        if (query.getCreatedAtEnd() != null) {
            wrapper.le(ReleaseRollbackOrder::getCreatedAt, query.getCreatedAtEnd());
        }
        if (query.getHasFailureHint() != null) {
            if (Boolean.TRUE.equals(query.getHasFailureHint())) {
                wrapper.apply("EXISTS (SELECT 1 FROM runtime_deploy_record WHERE rollback_order_id = id AND deploy_status = 'FAILED')");
            } else {
                wrapper.apply("NOT EXISTS (SELECT 1 FROM runtime_deploy_record WHERE rollback_order_id = id AND deploy_status = 'FAILED')");
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
                        WHEN EXISTS (SELECT 1 FROM runtime_deploy_record WHERE rollback_order_id = release_rollback_order.id) THEN 2
                        ELSE 3
                    END,
                    created_at DESC
                """);
        } else if ("status".equals(query.getOrderBy())) {
            if ("asc".equalsIgnoreCase(query.getOrderDirection())) {
                wrapper.orderByAsc(ReleaseRollbackOrder::getStatus);
            } else {
                // For failure focus: use CASE expression to sort FAILED first, then by status
                wrapper.last("ORDER BY CASE WHEN status = 'FAILED' THEN 0 ELSE 1 END, status DESC");
            }
        } else {
            wrapper.orderByDesc(ReleaseRollbackOrder::getCreatedAt);
        }

        Page<ReleaseRollbackOrder> page = releaseRollbackOrderService.page(
                new Page<>(query.getPageNum(), query.getPageSize()),
                wrapper
        );

        List<ReleaseRollbackOrderVo> voList = page.getRecords().stream()
                .map(this::toVoWithEnrichment)
                .toList();

        return ApiResponse.success(
                PageResponse.<ReleaseRollbackOrderVo>builder()
                        .pageNum(page.getCurrent())
                        .pageSize(page.getSize())
                        .total(page.getTotal())
                        .records(voList)
                        .build(),
                TraceIdHolder.get()
        );
    }

    private ReleaseRollbackOrderVo toVoWithEnrichment(ReleaseRollbackOrder entity) {
        ReleaseRollbackOrderVo vo = ReleaseRollbackOrderVo.fromEntity(entity);

        applyRollbackSupport(vo, entity);

        // 查询部署记录存在性
        boolean hasDeployRecords = runtimeDeployRecordService.existsByRollbackOrderId(entity.getId());
        vo.setHasDeployRecords(hasDeployRecords);

        // 查询最近部署记录状态
        RuntimeDeployRecord latestRecord = runtimeDeployRecordService.getLatestByRollbackOrderId(entity.getId());
        if (latestRecord != null) {
            vo.setLatestDeployStatus(latestRecord.getDeployStatus());

            // 如果有失败记录，生成失败提示
            if ("FAILED".equals(latestRecord.getDeployStatus()) && latestRecord.getDeployLog() != null) {
                String hint = deployFailureCategorizer.categorize(latestRecord.getDeployLog());
                vo.setLatestFailureHint(hint);
            }
        }

        return vo;
    }

    /**
     * 回滚列表必须给出原发布单的执行器类型与回滚支持判定，不得把非 SSH 发布展示为 SSH。
     * 发布单缺失或类型无法确定时字段留空，前端按“无法判定”展示，最终仍由执行入口的后端门禁裁定。
     */
    private void applyRollbackSupport(ReleaseRollbackOrderVo vo, ReleaseRollbackOrder entity) {
        Long releaseOrderId = entity.getReleaseOrderId();
        if (releaseOrderId == null) {
            var service = baseServiceEntityService.getById(entity.getServiceId());
            if (service != null) vo.setAppId(service.getAppId());
            vo.setExecutorType("SSH");
            vo.setRollbackSupported(true);
            return;
        }
        ReleaseOrder releaseOrder = releaseOrderService.getById(releaseOrderId);
        if (releaseOrder == null) {
            return;
        }
        RollbackExecutorSupportService.RollbackSupport support = rollbackExecutorSupportService.resolve(releaseOrder);
        vo.setExecutorType(support.executorType());
        vo.setRollbackSupported(support.supported());
        vo.setRollbackUnsupportedReason(support.supported() ? null : support.message());
    }

    @PostMapping
    @PreAuthorize("hasAnyAuthority('ROLLBACK_CREATE','SYSTEM_ADMIN')")
    public ApiResponse<Boolean> create(@RequestBody ReleaseRollbackOrder body) {
        return ApiResponse.success(releaseRollbackOrderService.createRollbackOrder(body), TraceIdHolder.get());
    }

    @PostMapping("/application")
    @PreAuthorize("hasAnyAuthority('ROLLBACK_EXECUTE','SYSTEM_ADMIN')")
    public ApiResponse<ReleaseRollbackOrderService.ApplicationRollbackResult> rollbackApplication(
            @Valid @RequestBody ApplicationRollbackRequest request) {
        return ApiResponse.success(releaseRollbackOrderService.rollbackApplication(request), TraceIdHolder.get());
    }

    @GetMapping("/application/options")
    @PreAuthorize("hasAnyAuthority('ROLLBACK_VIEW','ROLLBACK_EXECUTE','SYSTEM_ADMIN')")
    public ApiResponse<ReleaseRollbackOrderService.ApplicationRollbackOptions> applicationRollbackOptions() {
        return ApiResponse.success(releaseRollbackOrderService.applicationRollbackOptions(), TraceIdHolder.get());
    }

    @GetMapping("/application/instances")
    @PreAuthorize("hasAnyAuthority('ROLLBACK_VIEW','ROLLBACK_EXECUTE','SYSTEM_ADMIN')")
    public ApiResponse<List<RuntimeServiceInstance>> applicationRollbackInstances(@RequestParam @Positive Long serviceId,
                                                                                   @RequestParam @Positive Long envId) {
        return ApiResponse.success(releaseRollbackOrderService.applicationRollbackInstances(serviceId, envId), TraceIdHolder.get());
    }

    // T-D: 最小目标更新接口，仅接受 toVersionId；全部前置检查与拒绝语义在服务层
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('ROLLBACK_EXECUTE','SYSTEM_ADMIN')")
    public ApiResponse<Boolean> updateTarget(@PathVariable Long id, @Valid @RequestBody UpdateRollbackTargetRequest request) {
        return ApiResponse.success(releaseRollbackOrderService.updateRollbackTarget(id, request.getToVersionId()), TraceIdHolder.get());
    }

    @PostMapping("/{id}/execute")
    @PreAuthorize("hasAnyAuthority('ROLLBACK_EXECUTE','SYSTEM_ADMIN')")
    public ApiResponse<ExecuteResult> execute(@PathVariable Long id) {
        return ApiResponse.success(releaseRollbackOrderService.executeRollbackOrderWithApproval(id), TraceIdHolder.get());
    }

    @PostMapping("/{id}/precheck")
    @PreAuthorize("hasAnyAuthority('ROLLBACK_EXECUTE','SYSTEM_ADMIN')")
    public ApiResponse<List<ReleasePrecheckInstanceResponse>> precheck(@PathVariable Long id) {
        return ApiResponse.success(releaseRollbackOrderService.precheckRollbackOrder(id), TraceIdHolder.get());
    }

    @GetMapping("/{id}/deploy-records")
    @PreAuthorize("hasAnyAuthority('ROLLBACK_VIEW','SYSTEM_ADMIN')")
    public ApiResponse<List<RuntimeDeployRecord>> deployRecords(@PathVariable Long id) {
        return ApiResponse.success(runtimeDeployRecordService.listByRollbackOrderId(id), TraceIdHolder.get());
    }

    @GetMapping("/{id}/detail")
    @PreAuthorize("hasAnyAuthority('ROLLBACK_VIEW','SYSTEM_ADMIN')")
    public ApiResponse<RollbackOrderDetailResponse> detail(@PathVariable Long id) {
        return ApiResponse.success(releaseRollbackOrderService.getDetail(id), TraceIdHolder.get());
    }

    // ============ V-A: Precheck Summary API ============

    @GetMapping("/{id}/precheck-summary")
    @PreAuthorize("hasAnyAuthority('ROLLBACK_VIEW','SYSTEM_ADMIN')")
    public ApiResponse<PrecheckSummaryResponse> precheckSummary(@PathVariable Long id) {
        return ApiResponse.success(precheckSummaryService.getRollbackPrecheckSummary(id), TraceIdHolder.get());
    }

    // ============ V-A: Execution Context API ============

    @GetMapping("/{id}/execution-context")
    @PreAuthorize("hasAnyAuthority('ROLLBACK_VIEW','SYSTEM_ADMIN')")
    public ApiResponse<ExecutionContextResponse> executionContext(@PathVariable Long id) {
        return ApiResponse.success(executionContextService.getRollbackExecutionContext(id), TraceIdHolder.get());
    }
}

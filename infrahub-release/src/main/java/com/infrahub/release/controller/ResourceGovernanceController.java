package com.infrahub.release.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.infrahub.base.entity.BaseEnv;
import com.infrahub.base.entity.BaseServiceEntity;
import com.infrahub.base.service.BaseEnvService;
import com.infrahub.base.service.BaseServiceEntityService;
import com.infrahub.common.api.ApiResponse;
import com.infrahub.common.api.PageResponse;
import com.infrahub.common.support.TraceIdHolder;
import com.infrahub.release.dto.*;
import com.infrahub.release.entity.AssetHost;
import com.infrahub.release.entity.ReleaseOrder;
import com.infrahub.release.entity.ReleaseRollbackOrder;
import com.infrahub.release.entity.ReleaseVersion;
import com.infrahub.release.entity.ResourceGovernanceRecord;
import com.infrahub.release.entity.ResourcePool;
import com.infrahub.release.entity.RuntimeServiceInstance;
import com.infrahub.release.service.AssetHostService;
import com.infrahub.release.service.ReleaseOrderService;
import com.infrahub.release.service.ReleaseRollbackOrderService;
import com.infrahub.release.service.ReleaseVersionService;
import com.infrahub.release.service.ResourceGovernanceRecordService;
import com.infrahub.release.service.ResourcePoolService;
import com.infrahub.release.service.RuntimeServiceInstanceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/api/assets/resource-governance")
public class ResourceGovernanceController {

    private final ResourceGovernanceRecordService resourceGovernanceRecordService;
    private final ResourcePoolService resourcePoolService;
    private final AssetHostService assetHostService;
    private final RuntimeServiceInstanceService runtimeServiceInstanceService;
    private final ReleaseOrderService releaseOrderService;
    private final ReleaseRollbackOrderService releaseRollbackOrderService;
    private final BaseEnvService baseEnvService;
    private final BaseServiceEntityService baseServiceEntityService;
    private final ReleaseVersionService releaseVersionService;

    @GetMapping("/records")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<PageResponse<ResourceGovernanceRecordResponse>> getRecords(@Valid ResourceGovernanceRecordQuery query) {
        Page<ResourceGovernanceRecord> page = resourceGovernanceRecordService.query(
                query.getResourcePoolId(),
                query.getHostId(),
                query.getRuntimeInstanceId(),
                query.getReleaseOrderId(),
                query.getRollbackOrderId(),
                query.getEnvId(),
                query.getServiceId(),
                query.getEventType(),
                query.getResultStatus(),
                query.getSubjectType(),
                query.getSubjectId(),
                (int) query.getPageNum(),
                (int) query.getPageSize()
        );

        List<ResourceGovernanceRecordResponse> records = page.getRecords().stream()
                .map(this::enrichResponse)
                .toList();

        PageResponse<ResourceGovernanceRecordResponse> response = new PageResponse<>();
        response.setRecords(records);
        response.setTotal(page.getTotal());
        response.setPageNum(page.getCurrent());
        response.setPageSize(page.getSize());

        return ApiResponse.success(response, TraceIdHolder.get());
    }

    /**
     * 时间线查询 - Contract: GET /api/assets/resource-governance/records/timeline
     */
    @GetMapping("/records/timeline")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<TimelineResponse> getTimeline(@Valid TimelineQueryParams params) {
        Page<ResourceGovernanceRecord> page = resourceGovernanceRecordService.queryTimeline(params);

        TimelineResponse response = resourceGovernanceRecordService.buildTimelineResponse(
                params.getSubjectType(),
                params.getSubjectId(),
                params.getActionPlanNo(),
                page.getRecords()
        );

        return ApiResponse.success(response, TraceIdHolder.get());
    }

    /**
     * 汇总统计 - Contract: GET /api/assets/resource-governance/records/summary
     */
    @GetMapping("/records/summary")
    @PreAuthorize("hasAuthority('SYSTEM_ADMIN')")
    public ApiResponse<GovernanceRecordSummaryResponse> getSummary(
            Long resourcePoolId,
            String eventType,
            String resultStatus,
            String subjectType) {

        GovernanceRecordSummaryResponse response = resourceGovernanceRecordService.querySummary(
                resourcePoolId, eventType, resultStatus, subjectType);

        if (response != null && resourcePoolId != null) {
            ResourcePool pool = resourcePoolService.getById(resourcePoolId);
            if (pool != null) {
                response.setResourcePoolName(pool.getPoolName());
            }
        }

        return ApiResponse.success(response, TraceIdHolder.get());
    }

    private ResourceGovernanceRecordResponse enrichResponse(ResourceGovernanceRecord entity) {
        ResourceGovernanceRecordResponse response = ResourceGovernanceRecordResponse.fromEntity(entity);
        if (response == null) {
            return null;
        }

        if (entity.getResourcePoolId() != null) {
            ResourcePool pool = resourcePoolService.getById(entity.getResourcePoolId());
            if (pool != null) {
                response.setResourcePoolName(pool.getPoolName());
            }
        }

        if (entity.getHostId() != null) {
            AssetHost host = assetHostService.getById(entity.getHostId());
            if (host != null) {
                response.setHostname(host.getHostname());
            }
        }

        if (entity.getRuntimeInstanceId() != null) {
            RuntimeServiceInstance instance = runtimeServiceInstanceService.getById(entity.getRuntimeInstanceId());
            if (instance != null) {
                response.setInstanceName(instance.getInstanceName());
            }
        }

        if (entity.getReleaseOrderId() != null) {
            ReleaseOrder order = releaseOrderService.getById(entity.getReleaseOrderId());
            if (order != null) {
                response.setReleaseOrderNo(order.getOrderNo());
            }
        }

        if (entity.getRollbackOrderId() != null) {
            ReleaseRollbackOrder rollbackOrder = releaseRollbackOrderService.getById(entity.getRollbackOrderId());
            if (rollbackOrder != null) {
                response.setRollbackOrderNo(rollbackOrder.getRollbackNo());
            }
        }

        if (entity.getEnvId() != null) {
            BaseEnv env = baseEnvService.getById(entity.getEnvId());
            if (env != null) {
                response.setEnvName(env.getEnvName());
            }
        }

        if (entity.getServiceId() != null) {
            BaseServiceEntity service = baseServiceEntityService.getById(entity.getServiceId());
            if (service != null) {
                response.setServiceName(service.getServiceName());
            }
        }

        if (entity.getVersionId() != null) {
            ReleaseVersion version = releaseVersionService.getById(entity.getVersionId());
            if (version != null) {
                response.setVersionNo(version.getVersionNo());
            }
        }

        return response;
    }
}
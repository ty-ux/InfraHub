package com.infrahub.release.dto;

import com.infrahub.release.entity.ResourceGovernanceRecord;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 资源治理记录响应 DTO
 * 包含治理事件基本信息及关联实体的名称enrichment
 */
@Data
public class ResourceGovernanceRecordResponse {

    private Long id;
    private String eventType;

    // 资源池信息
    private Long resourcePoolId;
    private String resourcePoolName;

    // 主机信息
    private Long hostId;
    private String hostname;

    // 运行实例信息
    private Long runtimeInstanceId;
    private String instanceName;

    // 发布单信息
    private Long releaseOrderId;
    private String releaseOrderNo;

    // 回滚单信息
    private Long rollbackOrderId;
    private String rollbackOrderNo;

    // 执行器信息
    private String executorType;

    // 环境信息
    private Long envId;
    private String envName;

    // 服务信息
    private Long serviceId;
    private String serviceName;

    // 版本信息
    private Long versionId;
    private String versionNo;

    private String subjectType;
    private Long subjectId;
    private String actionPlanNo;
    private String actionExecutionNo;
    private String suggestionNo;
    private String beforePayload;
    private String afterPayload;
    private String resultStatus;
    private String reason;
    private Long createdBy;
    private OffsetDateTime createdAt;

    public static ResourceGovernanceRecordResponse fromEntity(ResourceGovernanceRecord entity) {
        if (entity == null) {
            return null;
        }
        ResourceGovernanceRecordResponse response = new ResourceGovernanceRecordResponse();
        response.setId(entity.getId());
        response.setEventType(entity.getEventType());
        response.setResourcePoolId(entity.getResourcePoolId());
        response.setHostId(entity.getHostId());
        response.setRuntimeInstanceId(entity.getRuntimeInstanceId());
        response.setReleaseOrderId(entity.getReleaseOrderId());
        response.setRollbackOrderId(entity.getRollbackOrderId());
        response.setExecutorType(entity.getExecutorType());
        response.setEnvId(entity.getEnvId());
        response.setServiceId(entity.getServiceId());
        response.setVersionId(entity.getVersionId());
        response.setSubjectType(entity.getSubjectType());
        response.setSubjectId(entity.getSubjectId());
        response.setActionPlanNo(entity.getActionPlanNo());
        response.setActionExecutionNo(entity.getActionExecutionNo());
        response.setSuggestionNo(entity.getSuggestionNo());
        response.setBeforePayload(entity.getBeforePayload());
        response.setAfterPayload(entity.getAfterPayload());
        response.setResultStatus(entity.getResultStatus());
        response.setReason(entity.getReason());
        response.setCreatedBy(entity.getCreatedBy());
        response.setCreatedAt(entity.getCreatedAt());
        return response;
    }
}
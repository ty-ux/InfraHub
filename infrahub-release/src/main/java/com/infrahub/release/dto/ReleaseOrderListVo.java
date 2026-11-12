package com.infrahub.release.dto;

import com.infrahub.release.entity.ReleaseOrder;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 发布单列表展示 VO，包含只读 enrichment 字段。
 */
@Data
public class ReleaseOrderListVo {

    private Long id;
    private String orderNo;
    private Long appId;
    private Long serviceId;
    private Long envId;
    private Long versionId;
    private String releaseType;
    private String status;
    private Long createdBy;
    private OffsetDateTime createdAt;

    /**
     * 最近执行状态，取最新部署记录状态。
     */
    private String latestDeployStatus;

    /**
     * 最近失败提示，仅当存在 FAILED 部署记录时返回。
     */
    private String latestFailureHint;

    /**
     * 是否存在部署记录。
     */
    private Boolean hasDeployRecords;

    /**
     * 是否有关联流水线运行。
     */
    private Boolean hasPipelineRun;

    /**
     * 灰度计划状态，仅 releaseType='CANARY' 时有值。
     */
    private String canaryPlanStatus;

    private String executorType;
    private Long executorConfigId;

    public static ReleaseOrderListVo fromEntity(ReleaseOrder entity) {
        ReleaseOrderListVo vo = new ReleaseOrderListVo();
        vo.setId(entity.getId());
        vo.setOrderNo(entity.getOrderNo());
        vo.setAppId(entity.getAppId());
        vo.setServiceId(entity.getServiceId());
        vo.setEnvId(entity.getEnvId());
        vo.setVersionId(entity.getVersionId());
        vo.setReleaseType(entity.getReleaseType());
        vo.setStatus(entity.getStatus());
        vo.setCreatedBy(entity.getCreatedBy());
        vo.setCreatedAt(entity.getCreatedAt());
        vo.setExecutorType(entity.getExecutorType() != null ? entity.getExecutorType() : "SSH");
        vo.setExecutorConfigId(entity.getExecutorConfigId());
        return vo;
    }
}

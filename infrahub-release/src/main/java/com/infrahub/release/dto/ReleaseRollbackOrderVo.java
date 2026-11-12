package com.infrahub.release.dto;

import com.infrahub.release.entity.ReleaseRollbackOrder;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 回滚单列表展示 VO，包含只读 enrichment 字段。
 */
@Data
public class ReleaseRollbackOrderVo {

    private Long id;
    private String rollbackNo;
    private Long releaseOrderId;
    private Long appId;
    private Long serviceId;
    private Long envId;
    private Long fromVersionId;
    private Long toVersionId;
    private String reason;
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
     * 关联发布单解析出的执行器类型；无法确定时为 null。
     */
    private String executorType;

    /**
     * 该回滚单是否可按既有 SSH 回滚路径执行；null 表示无法判定，由后端门禁最终裁定。
     */
    private Boolean rollbackSupported;

    /**
     * rollbackSupported=false 时的原因，与后端拒绝理由一致。
     */
    private String rollbackUnsupportedReason;

    public static ReleaseRollbackOrderVo fromEntity(ReleaseRollbackOrder entity) {
        ReleaseRollbackOrderVo vo = new ReleaseRollbackOrderVo();
        vo.setId(entity.getId());
        vo.setRollbackNo(entity.getRollbackNo());
        vo.setReleaseOrderId(entity.getReleaseOrderId());
        vo.setServiceId(entity.getServiceId());
        vo.setEnvId(entity.getEnvId());
        vo.setFromVersionId(entity.getFromVersionId());
        vo.setToVersionId(entity.getToVersionId());
        vo.setReason(entity.getReason());
        vo.setStatus(entity.getStatus());
        vo.setCreatedBy(entity.getCreatedBy());
        vo.setCreatedAt(entity.getCreatedAt());
        return vo;
    }
}

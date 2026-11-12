package com.infrahub.release.dto;

import com.infrahub.release.entity.ReleaseExecutionRunClaim;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 发布执行认领的只读视图。
 *
 * <p>页面必须照下面的事实表述：{@code status=HELD} 表示这次执行仍由平台持有执行权（后续执行会被它挡住）；
 * {@code releaseOutcome=ABANDONED} 表示操作人放弃了认领，**不代表远端已停止**，也不代表结果已确定。
 * 这里不包含命令、远端输出或凭据。
 */
@Data
public class ReleaseExecutionRunClaimResponse {

    private Long id;
    private Long releaseOrderId;
    private Long serviceId;
    private Long envId;
    private String claimPurpose;
    private String holderKind;
    private Long pipelineRunId;
    private Long pipelineStepRunId;
    private Long rollbackOrderId;
    private String status;
    private String releaseOutcome;
    private String releaseReason;
    private Long releasedBy;
    private OffsetDateTime claimedAt;
    private OffsetDateTime releasedAt;
    /** 认领仍为 HELD：该发布单的部署执行权没有收口，新的部署会被拒绝。 */
    private boolean blocking;

    public static ReleaseExecutionRunClaimResponse fromEntity(ReleaseExecutionRunClaim row) {
        ReleaseExecutionRunClaimResponse response = new ReleaseExecutionRunClaimResponse();
        response.setId(row.getId());
        response.setReleaseOrderId(row.getReleaseOrderId());
        response.setServiceId(row.getServiceId());
        response.setEnvId(row.getEnvId());
        response.setClaimPurpose(row.getClaimPurpose());
        response.setHolderKind(row.getHolderKind());
        response.setPipelineRunId(row.getPipelineRunId());
        response.setPipelineStepRunId(row.getPipelineStepRunId());
        response.setRollbackOrderId(row.getRollbackOrderId());
        response.setStatus(row.getStatus());
        response.setReleaseOutcome(row.getReleaseOutcome());
        response.setReleaseReason(row.getReleaseReason());
        response.setReleasedBy(row.getReleasedBy());
        response.setClaimedAt(row.getClaimedAt());
        response.setReleasedAt(row.getReleasedAt());
        response.setBlocking(ReleaseExecutionRunClaim.STATUS_HELD.equals(row.getStatus()));
        return response;
    }
}

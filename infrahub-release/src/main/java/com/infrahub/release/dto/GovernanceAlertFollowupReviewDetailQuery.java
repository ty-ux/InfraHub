package com.infrahub.release.dto;

import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 复盘明细列表查询参数 DTO
 * Contract: v2-stage-s-contract.md Section 4.7
 */
@Data
public class GovernanceAlertFollowupReviewDetailQuery {

    private OffsetDateTime startTime;

    private OffsetDateTime endTime;

    private Long alertRecordId;

    private Long notificationRecordId;

    private Long targetUserId;

    private Long createdBy;

    private String followUpType;

    private String followUpStatus;

    private String receiptStatus;

    private Integer pageNum = 1;

    private Integer pageSize = 20;

    public Integer getPageNum() {
        return pageNum != null ? pageNum : 1;
    }

    public Integer getPageSize() {
        return Math.min(pageSize != null ? pageSize : 20, 200);
    }
}

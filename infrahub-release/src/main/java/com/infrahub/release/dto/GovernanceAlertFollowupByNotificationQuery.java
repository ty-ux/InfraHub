package com.infrahub.release.dto;

import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 按通知维度分析查询参数 DTO
 * Contract: v2-stage-s-contract.md Section 4.6
 */
@Data
public class GovernanceAlertFollowupByNotificationQuery {

    private OffsetDateTime startTime;

    private OffsetDateTime endTime;

    private Long notificationRecordId;

    private Long alertRecordId;

    private Long targetUserId;

    private String channelType;

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

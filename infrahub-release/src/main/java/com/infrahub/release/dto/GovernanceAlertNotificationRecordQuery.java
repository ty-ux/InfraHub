package com.infrahub.release.dto;

import lombok.Data;

/**
 * 通知记录查询参数 DTO
 * Contract: v2-stage-q-contract.md Section 4.2
 */
@Data
public class GovernanceAlertNotificationRecordQuery {

    private Long alertRecordId;

    private String channelType;

    private String notificationStatus;

    private Long targetUserId;

    private Integer pageNum = 1;

    private Integer pageSize = 20;
}
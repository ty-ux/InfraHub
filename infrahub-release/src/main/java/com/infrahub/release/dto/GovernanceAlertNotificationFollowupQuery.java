package com.infrahub.release.dto;

import lombok.Data;

/**
 * 跟进记录查询参数 DTO
 * Contract: v2-stage-r-contract.md Section 3.5
 */
@Data
public class GovernanceAlertNotificationFollowupQuery {

    private Long notificationRecordId;

    private String followUpType;

    private String receiptStatus;

    private String followUpStatus;

    private Long alertRecordId;

    private Integer pageNum = 1;

    private Integer pageSize = 20;

    /**
     * 获取页码
     */
    public Integer getPageNum() {
        return pageNum != null ? pageNum : 1;
    }

    /**
     * 获取页面大小，最大限制为200
     */
    public Integer getPageSize() {
        return Math.min(pageSize != null ? pageSize : 20, 200);
    }
}

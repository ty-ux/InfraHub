package com.infrahub.release.dto;

import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 按告警维度分析查询参数 DTO
 * Contract: v2-stage-s-contract.md Section 4.5
 */
@Data
public class GovernanceAlertFollowupByAlertQuery {

    private OffsetDateTime startTime;

    private OffsetDateTime endTime;

    private Long alertRecordId;

    private String alertLevel;

    private String alertStatus;

    private Integer pageNum = 1;

    private Integer pageSize = 20;

    public Integer getPageNum() {
        return pageNum != null ? pageNum : 1;
    }

    public Integer getPageSize() {
        return Math.min(pageSize != null ? pageSize : 20, 200);
    }
}

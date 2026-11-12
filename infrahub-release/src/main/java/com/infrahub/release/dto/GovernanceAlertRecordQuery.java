package com.infrahub.release.dto;

import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 告警记录查询参数
 * Contract: v2-stage-m-contract.md Section 3.1-3.2
 */
@Data
public class GovernanceAlertRecordQuery {

    private Long envId;

    private Long resourcePoolId;

    private Long serviceId;

    private String metricType;

    private String alertLevel;

    private String alertStatus;

    private OffsetDateTime startTime;

    private OffsetDateTime endTime;

    private Integer pageNum;

    private Integer pageSize;

    public Integer getPageNum() {
        return pageNum != null && pageNum > 0 ? pageNum : 1;
    }

    public Integer getPageSize() {
        return pageSize != null && pageSize > 0 && pageSize <= 100 ? pageSize : 20;
    }
}
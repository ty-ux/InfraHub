package com.infrahub.release.dto;

import lombok.Data;

/**
 * 我的待办告警查询参数
 * Contract: v2-stage-p-contract.md Section 4.3
 */
@Data
public class GovernanceAlertMyAssignedQuery {

    /**
     * 页码（默认 1）
     */
    private Integer pageNum = 1;

    /**
     * 每页数量（默认 20）
     */
    private Integer pageSize = 20;

    /**
     * 告警状态筛选（OPEN/ACKED/RESOLVED）
     */
    private String status;

    /**
     * 告警等级筛选（INFO/WARNING/CRITICAL）
     */
    private String level;

    /**
     * 仅显示逾期告警
     */
    private Boolean overdueOnly;
}
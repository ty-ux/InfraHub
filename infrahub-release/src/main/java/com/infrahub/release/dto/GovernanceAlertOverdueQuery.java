package com.infrahub.release.dto;

import lombok.Data;

/**
 * 逾期告警查询参数
 * Contract: v2-stage-p-contract.md Section 4.1
 */
@Data
public class GovernanceAlertOverdueQuery {

    /**
     * 页码（默认 1）
     */
    private Integer pageNum = 1;

    /**
     * 每页数量（默认 20）
     */
    private Integer pageSize = 20;

    /**
     * 分派人 ID 筛选
     */
    private Long assigneeId;

    /**
     * 告警等级筛选（INFO/WARNING/CRITICAL）
     */
    private String level;

    /**
     * 是否已升级筛选
     */
    private Boolean escalated;
}
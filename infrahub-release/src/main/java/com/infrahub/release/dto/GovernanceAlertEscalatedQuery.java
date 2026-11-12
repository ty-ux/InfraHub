package com.infrahub.release.dto;

import lombok.Data;

/**
 * 已升级告警查询参数
 * Contract: v2-stage-p-contract.md Section 4.2
 */
@Data
public class GovernanceAlertEscalatedQuery {

    /**
     * 页码（默认 1）
     */
    private Integer pageNum = 1;

    /**
     * 每页数量（默认 20）
     */
    private Integer pageSize = 20;

    /**
     * 升级级别筛选（L1/L2/L3）
     */
    private String escalationLevel;

    /**
     * 告警状态筛选（OPEN/ACKED/RESOLVED）
     */
    private String status;
}
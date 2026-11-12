package com.infrahub.release.dto;

import lombok.Data;

/**
 * 复盘条目查询参数
 * Contract: v2-stage-l-contract.md Section 3.1, 3.2
 */
@Data
public class GovernanceReviewItemQuery {

    private Long envId;

    private Long resourcePoolId;

    private Long serviceId;

    private String riskLevel;

    private String suggestionStatus;

    private String actionPlanStatus;

    private String executionStatus;

    private String impactType;

    private Integer pageNum = 1;

    private Integer pageSize = 20;
}
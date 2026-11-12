package com.infrahub.release.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 告警规则响应 DTO
 * Contract: v2-stage-m-contract.md Section 4.2
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GovernanceAlertRuleResponse {

    private Long id;

    private String ruleName;

    private String metricType;

    private String operator;

    private Double thresholdValue;

    private String alertLevel;

    private Long envId;

    private Long resourcePoolId;

    private Long serviceId;

    private Boolean enabled;

    private String description;

    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;
}
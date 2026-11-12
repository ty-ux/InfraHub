package com.infrahub.release.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 告警记录响应 DTO
 * Contract: v2-stage-m-contract.md Section 4.4
 * N 阶段新增字段: v2-stage-n-contract.md Section 3.2
 * O 阶段新增字段: v2-stage-o-contract.md Section 3.9
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GovernanceAlertRecordResponse {

    private Long id;

    private String alertNo;

    private Long ruleId;

    private String ruleName;

    private String metricType;

    private Double currentValue;

    private Double thresholdValue;

    private String operator;

    private String alertLevel;

    private String alertStatus;

    private Long envId;

    private Long resourcePoolId;

    private Long serviceId;

    private String reason;

    private OffsetDateTime createdAt;

    private OffsetDateTime acknowledgedAt;

    private OffsetDateTime resolvedAt;

    // N 阶段新增字段
    private Long acknowledgedBy;

    private String acknowledgedByName;

    private Long resolvedBy;

    private String resolvedByName;

    private String handleNote;

    private Map<String, Object> evidencePayload;

    // O 阶段新增字段
    private Long assigneeId;

    private String assigneeName;

    private OffsetDateTime assignedAt;

    private OffsetDateTime assignmentDueAt;

    private Boolean escalated;

    private String escalationLevel;

    private OffsetDateTime escalatedAt;
}
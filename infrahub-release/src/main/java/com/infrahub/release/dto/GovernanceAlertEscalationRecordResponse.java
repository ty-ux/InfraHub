package com.infrahub.release.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 告警升级记录响应 DTO
 * Contract: v2-stage-o-contract.md Section 3.7
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GovernanceAlertEscalationRecordResponse {

    private Long id;

    private Long alertId;

    private String alertNo;

    private String escalationLevel;

    private Long escalatedToId;

    private String escalatedToName;

    private String escalateReason;

    private Map<String, Object> evidencePayload;

    private Long operatorId;

    private String operatorName;

    private OffsetDateTime createdAt;
}
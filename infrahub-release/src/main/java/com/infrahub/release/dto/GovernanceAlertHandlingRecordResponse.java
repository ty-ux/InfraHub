package com.infrahub.release.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 告警处理历史响应 DTO
 * Contract: v2-stage-n-contract.md Section 3.3
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GovernanceAlertHandlingRecordResponse {

    private Long id;

    private Long alertId;

    private String alertNo;

    private String fromStatus;

    private String toStatus;

    private String action;

    private String handleNote;

    private Map<String, Object> evidencePayload;

    private Long operatorId;

    private String operatorName;

    private OffsetDateTime createdAt;
}

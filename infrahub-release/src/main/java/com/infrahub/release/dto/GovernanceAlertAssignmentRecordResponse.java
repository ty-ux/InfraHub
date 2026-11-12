package com.infrahub.release.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 分派历史记录响应 DTO
 * Contract: v2-stage-o-contract.md Section 3.5
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GovernanceAlertAssignmentRecordResponse {

    private Long id;

    private Long alertId;

    private String alertNo;

    private String action;

    private Long fromAssigneeId;

    private String fromAssigneeName;

    private Long toAssigneeId;

    private String toAssigneeName;

    private String reason;

    private OffsetDateTime dueAt;

    private Map<String, Object> evidencePayload;

    private Long operatorId;

    private String operatorName;

    private OffsetDateTime createdAt;
}

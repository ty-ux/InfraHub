package com.infrahub.release.dto;

import lombok.Data;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 告警转派请求 DTO
 * Contract: v2-stage-o-contract.md Section 3.2
 */
@Data
public class GovernanceAlertReassignRequest {

    private Long fromAssigneeId;

    private Long toAssigneeId;

    private String toAssigneeName;

    private String reassignReason;

    private OffsetDateTime dueAt;

    private Map<String, Object> evidencePayload;

    private Long operatorId;

    private String operatorName;
}

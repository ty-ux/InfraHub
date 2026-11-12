package com.infrahub.release.dto;

import lombok.Data;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 告警分派请求 DTO
 * Contract: v2-stage-o-contract.md Section 3.1
 */
@Data
public class GovernanceAlertAssignRequest {

    private Long assigneeId;

    private String assigneeName;

    private String assignReason;

    private OffsetDateTime dueAt;

    private Map<String, Object> evidencePayload;

    private Long operatorId;

    private String operatorName;
}

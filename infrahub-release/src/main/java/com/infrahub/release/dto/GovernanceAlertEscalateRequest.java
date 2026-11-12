package com.infrahub.release.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 告警升级请求 DTO
 * Contract: v2-stage-o-contract.md Section 3.4
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GovernanceAlertEscalateRequest {

    @NotNull(message = "escalationLevel is required")
    private String escalationLevel;

    @NotNull(message = "escalatedToId is required")
    private Long escalatedToId;

    @NotNull(message = "escalatedToName is required")
    private String escalatedToName;

    private String escalateReason;

    private Map<String, Object> evidencePayload;

    private Long operatorId;

    private String operatorName;
}
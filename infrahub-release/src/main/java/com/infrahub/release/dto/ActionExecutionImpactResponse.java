package com.infrahub.release.dto;

import com.infrahub.release.enums.ImpactType;
import com.infrahub.release.enums.ResultStatus;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * Response DTO for governance action execution impact.
 */
@Data
@Builder
public class ActionExecutionImpactResponse {

    private Long executionId;

    private String executionNo;

    private ImpactType impactType;

    private String impactSummary;

    private Object impactPayload;

    private Object beforePayload;

    private Object afterPayload;

    private Object evidencePayload;

    private ResultStatus resultStatus;

    private Long operatorId;

    private String operatorName;

    private OffsetDateTime recordedAt;
}
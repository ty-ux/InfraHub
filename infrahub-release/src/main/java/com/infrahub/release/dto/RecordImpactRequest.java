package com.infrahub.release.dto;

import com.infrahub.release.enums.ImpactType;
import com.infrahub.release.enums.ResultStatus;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Request DTO for recording governance action execution impact.
 * Field names defined in V2/K Contract section 3.3.
 */
@Data
public class RecordImpactRequest {

    @NotNull
    private ImpactType impactType;

    @NotNull
    private String impactSummary;

    private Object beforePayload;

    private Object afterPayload;

    private Object impactPayload;

    private Object evidencePayload;

    @NotNull
    private ResultStatus resultStatus;

    private Long operatorId;

    private String operatorName;
}
package com.infrahub.release.dto;

import com.infrahub.release.enums.ActionExecutionStatus;
import com.infrahub.release.enums.ActionType;
import com.infrahub.release.enums.ExecutionMode;
import com.infrahub.release.enums.TargetType;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@Builder
public class ResourceGovernanceActionExecutionResponse {

    private Long id;
    private String executionNo;
    private Long planId;
    private Long suggestionId;
    private ActionType actionType;
    private TargetType targetType;
    private Long targetId;
    private ActionExecutionStatus status;
    private ExecutionMode executionMode;
    private Long operatorId;
    private OffsetDateTime startedAt;
    private OffsetDateTime finishedAt;
    private String resultSummary;
    private String failureReason;
    private Object evidencePayload;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
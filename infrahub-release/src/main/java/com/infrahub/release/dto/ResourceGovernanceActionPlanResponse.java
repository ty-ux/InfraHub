package com.infrahub.release.dto;

import com.infrahub.release.enums.ActionPlanStatus;
import com.infrahub.release.enums.ActionType;
import com.infrahub.release.enums.RiskLevel;
import com.infrahub.release.enums.TargetType;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@Builder
public class ResourceGovernanceActionPlanResponse {

    private Long id;
    private String planNo;
    private Long suggestionId;
    private String suggestionNo;
    private ActionType actionType;
    private Long resourcePoolId;
    private String resourcePoolName;
    private Long envId;
    private String envName;
    private TargetType targetType;
    private Long targetId;
    private String planPayload;
    private RiskLevel riskLevel;
    private ActionPlanStatus status;
    private String reason;
    private Long createdBy;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private Long confirmedBy;
    private OffsetDateTime confirmedAt;
    private Long cancelledBy;
    private OffsetDateTime cancelledAt;
    private String cancelReason;
    private OffsetDateTime executedAt;
    private String resultStatus;
    private String resultMessage;
}

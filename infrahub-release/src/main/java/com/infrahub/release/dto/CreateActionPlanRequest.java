package com.infrahub.release.dto;

import com.infrahub.release.enums.ActionType;
import com.infrahub.release.enums.RiskLevel;
import com.infrahub.release.enums.TargetType;
import lombok.Data;

@Data
public class CreateActionPlanRequest {

    private ActionType actionType;
    private TargetType targetType;
    private Long targetId;
    private String planPayload;
    private RiskLevel riskLevel;
    private String reason;
}

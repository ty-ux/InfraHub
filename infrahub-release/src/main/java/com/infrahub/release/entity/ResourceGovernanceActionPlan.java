package com.infrahub.release.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.infrahub.release.enums.ActionPlanStatus;
import com.infrahub.release.enums.ActionType;
import com.infrahub.release.enums.RiskLevel;
import com.infrahub.release.enums.TargetType;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName("release_resource_governance_action_plan")
public class ResourceGovernanceActionPlan {

    private Long id;
    private String planNo;
    private Long suggestionId;
    private String suggestionNo;
    private ActionType actionType;
    private Long resourcePoolId;
    private Long envId;
    private TargetType targetType;
    private Long targetId;
    private String planPayload;
    private RiskLevel riskLevel;
    private ActionPlanStatus status;
    private String reason;

    @TableField(fill = FieldFill.INSERT)
    private Long createdBy;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
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

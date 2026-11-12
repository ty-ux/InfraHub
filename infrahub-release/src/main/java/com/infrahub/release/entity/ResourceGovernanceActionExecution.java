package com.infrahub.release.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.infrahub.release.enums.ActionExecutionStatus;
import com.infrahub.release.enums.ActionType;
import com.infrahub.release.enums.ExecutionMode;
import com.infrahub.release.enums.ImpactType;
import com.infrahub.release.enums.TargetType;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName("release_resource_governance_action_execution")
public class ResourceGovernanceActionExecution {

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

    @TableField(typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private Object evidencePayload;

    // Impact fields (K-A)
    private ImpactType impactType;
    private String impactSummary;

    @TableField(typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private Object impactPayload;

    private OffsetDateTime impactRecordedAt;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;
}
package com.infrahub.release.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName("release_resource_governance_record")
public class ResourceGovernanceRecord {

    private Long id;
    private String eventType;
    private Long resourcePoolId;
    private Long hostId;
    private Long runtimeInstanceId;
    private Long releaseOrderId;
    private Long rollbackOrderId;
    private String executorType;
    private Long envId;
    private Long serviceId;
    private Long versionId;
    private String subjectType;
    private Long subjectId;
    private String actionPlanNo;
    private String actionExecutionNo;
    private String suggestionNo;
    private String beforePayload;
    private String afterPayload;
    private String resultStatus;
    private String reason;
    @TableField(fill = FieldFill.INSERT)
    private Long createdBy;
    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
}

package com.infrahub.release.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 告警分派记录实体
 * Contract: v2-stage-o-contract.md Section 8.2
 */
@Data
@TableName(value = "release_governance_alert_assignment_record", autoResultMap = true)
public class GovernanceAlertAssignmentRecord {

    private Long id;

    private Long alertId;

    private String alertNo;

    private String action;

    private Long fromAssigneeId;

    private String fromAssigneeName;

    private Long toAssigneeId;

    private String toAssigneeName;

    private String reason;

    private OffsetDateTime dueAt;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> evidencePayload;

    private Long operatorId;

    private String operatorName;

    private OffsetDateTime createdAt;
}

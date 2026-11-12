package com.infrahub.release.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.infrahub.release.enums.AlertOperator;
import com.infrahub.release.enums.GovernanceAlertLevel;
import com.infrahub.release.enums.GovernanceAlertStatus;
import com.infrahub.release.enums.GovernanceOpsMetricType;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 治理运营告警记录实体
 * Contract: v2-stage-m-contract.md Section 4.4
 * N 阶段新增字段: v2-stage-n-contract.md Section 8.1
 * O 阶段新增字段: v2-stage-o-contract.md Section 8.1
 */
@Data
@TableName(value = "release_governance_alert_record", autoResultMap = true)
public class GovernanceAlertRecord {

    private Long id;

    private String alertNo;

    private Long ruleId;

    private String ruleName;

    private GovernanceOpsMetricType metricType;

    private Double currentValue;

    private Double thresholdValue;

    private AlertOperator operator;

    private GovernanceAlertLevel alertLevel;

    private GovernanceAlertStatus alertStatus;

    private Long envId;

    private Long resourcePoolId;

    private Long serviceId;

    private String reason;

    private OffsetDateTime createdAt;

    private OffsetDateTime acknowledgedAt;

    private OffsetDateTime resolvedAt;

    // N 阶段新增字段
    private Long acknowledgedBy;

    private String acknowledgedByName;

    private Long resolvedBy;

    private String resolvedByName;

    private String handleNote;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> evidencePayload;

    // O 阶段新增字段
    private Long assigneeId;

    private String assigneeName;

    private OffsetDateTime assignedAt;

    private OffsetDateTime assignmentDueAt;

    private Boolean escalated;

    private String escalationLevel;

    private OffsetDateTime escalatedAt;
}
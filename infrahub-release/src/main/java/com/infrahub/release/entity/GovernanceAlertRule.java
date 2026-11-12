package com.infrahub.release.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.infrahub.release.enums.AlertOperator;
import com.infrahub.release.enums.GovernanceAlertLevel;
import com.infrahub.release.enums.GovernanceOpsMetricType;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 治理运营告警规则实体
 * Contract: v2-stage-m-contract.md Section 4.1-4.2
 */
@Data
@TableName("release_governance_alert_rule")
public class GovernanceAlertRule {

    private Long id;

    private String ruleName;

    private GovernanceOpsMetricType metricType;

    private AlertOperator operator;

    private Double thresholdValue;

    private GovernanceAlertLevel alertLevel;

    private Long envId;

    private Long resourcePoolId;

    private Long serviceId;

    private Boolean enabled;

    private String description;

    @TableField(fill = FieldFill.INSERT)
    private Long createdBy;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;
}
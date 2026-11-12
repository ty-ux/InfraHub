package com.infrahub.release.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 告警升级记录实体
 * Contract: v2-stage-o-contract.md Section 8.3
 */
@Data
@TableName(value = "release_governance_alert_escalation_record", autoResultMap = true)
public class GovernanceAlertEscalationRecord {

    private Long id;

    private Long alertId;

    private String alertNo;

    private String escalationLevel;

    private Long escalatedToId;

    private String escalatedToName;

    private String escalateReason;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> evidencePayload;

    private Long operatorId;

    private String operatorName;

    private OffsetDateTime createdAt;
}
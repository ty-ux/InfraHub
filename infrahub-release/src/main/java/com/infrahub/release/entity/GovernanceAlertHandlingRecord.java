package com.infrahub.release.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 告警处理记录实体
 * Contract: v2-stage-n-contract.md Section 8.2
 */
@Data
@TableName(value = "release_governance_alert_handling_record", autoResultMap = true)
public class GovernanceAlertHandlingRecord {

    private Long id;

    private Long alertId;

    private String alertNo;

    private String fromStatus;

    private String toStatus;

    private String action;

    private String handleNote;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> evidencePayload;

    private Long operatorId;

    private String operatorName;

    private OffsetDateTime createdAt;
}

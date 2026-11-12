package com.infrahub.release.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 治理运营周期报告实体
 * Contract: v2-stage-m-contract.md Section 9.2
 */
@Data
@TableName("release_governance_report")
public class GovernanceReport {

    private Long id;

    private String reportNo;

    private String reportType;

    private String title;

    private OffsetDateTime startTime;

    private OffsetDateTime endTime;

    private Long envId;

    private Long resourcePoolId;

    private Long serviceId;

    @TableField(typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private Object summaryPayload;

    @TableField(typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private Object overviewPayload;

    @TableField(typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private Object trendPayload;

    @TableField(typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private Object slaPayload;

    @TableField(typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private Object effectivenessPayload;

    @TableField(typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private Object reviewItemPayload;

    @TableField(typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private Object alertPayload;

    private OffsetDateTime generatedAt;

    private Long generatedBy;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
}
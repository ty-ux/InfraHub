package com.infrahub.release.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 治理运营报告响应
 * Contract: v2-stage-m-contract.md Section 4.6
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GovernanceReportResponse {

    /**
     * 报告ID
     */
    private Long id;

    /**
     * 报告编号
     */
    private String reportNo;

    /**
     * 报告类型: DAILY/WEEKLY/MONTHLY/CUSTOM
     */
    private String reportType;

    /**
     * 报告标题
     */
    private String title;

    /**
     * 统计开始时间
     */
    private OffsetDateTime startTime;

    /**
     * 统计结束时间
     */
    private OffsetDateTime endTime;

    /**
     * 汇总数据
     */
    private Object summaryPayload;

    /**
     * 运营总览数据
     */
    private Object overviewPayload;

    /**
     * 趋势数据
     */
    private Object trendPayload;

    /**
     * SLA数据
     */
    private Object slaPayload;

    /**
     * 效果评估数据
     */
    private Object effectivenessPayload;

    /**
     * 复盘条目数据
     */
    private Object reviewItemPayload;

    /**
     * 告警数据
     */
    private Object alertPayload;

    /**
     * 生成时间
     */
    private OffsetDateTime generatedAt;

    /**
     * 生成人ID
     */
    private Long generatedBy;
}
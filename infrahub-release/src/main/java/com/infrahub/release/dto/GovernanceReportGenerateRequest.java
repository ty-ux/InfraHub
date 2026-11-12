package com.infrahub.release.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 治理运营报告生成请求
 * Contract: v2-stage-m-contract.md Section 4.5
 */
@Data
public class GovernanceReportGenerateRequest {

    /**
     * 报告类型: DAILY/WEEKLY/MONTHLY/CUSTOM
     * Contract: required
     */
    @NotBlank(message = "reportType is required")
    private String reportType;

    /**
     * 统计开始时间
     * Contract: required
     */
    @NotNull(message = "startTime is required")
    private OffsetDateTime startTime;

    /**
     * 统计结束时间
     * Contract: required
     */
    @NotNull(message = "endTime is required")
    private OffsetDateTime endTime;

    /**
     * 环境ID，null表示全局
     */
    private Long envId;

    /**
     * 资源池ID，null表示全局
     */
    private Long resourcePoolId;

    /**
     * 服务ID，null表示全局
     */
    private Long serviceId;

    /**
     * 报告标题，默认自动生成
     */
    private String title;

    @AssertTrue(message = "reportType must be one of: DAILY, WEEKLY, MONTHLY, CUSTOM")
    public boolean isValidReportType() {
        if (reportType == null || reportType.isEmpty()) {
            return true;
        }
        return switch (reportType) {
            case "DAILY", "WEEKLY", "MONTHLY", "CUSTOM" -> true;
            default -> false;
        };
    }

    @AssertTrue(message = "startTime must be before or equal to endTime")
    public boolean isValidTimeRange() {
        if (startTime == null || endTime == null) {
            return true;
        }
        return !startTime.isAfter(endTime);
    }
}
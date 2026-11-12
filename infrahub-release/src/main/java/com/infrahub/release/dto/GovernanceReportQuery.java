package com.infrahub.release.dto;

import jakarta.validation.constraints.AssertTrue;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 治理运营报告查询参数
 * Contract: v2-stage-m-contract.md Section 3
 */
@Data
public class GovernanceReportQuery {

    /**
     * 报告类型: DAILY/WEEKLY/MONTHLY/CUSTOM
     */
    private String reportType;

    /**
     * 环境ID
     */
    private Long envId;

    /**
     * 资源池ID
     */
    private Long resourcePoolId;

    /**
     * 服务ID
     */
    private Long serviceId;

    /**
     * 开始时间
     */
    private OffsetDateTime startTime;

    /**
     * 结束时间
     */
    private OffsetDateTime endTime;

    /**
     * 页码，默认1
     */
    private Integer pageNum;

    /**
     * 每页数量，默认20
     */
    private Integer pageSize;

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
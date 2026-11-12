package com.infrahub.release.dto;

import com.infrahub.release.enums.RiskLevel;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 单个指标的风险详情
 */
@Data
@Builder
public class CapacityRiskItemResponse {

    /**
     * 指标类型: CPU, MEMORY, DISK, OFFLINE_HOST, UNKNOWN_HOST
     */
    private String metricType;

    /**
     * 当前值（使用率百分比或主机数量）
     */
    private BigDecimal currentValue;

    /**
     * 触发的阈值（warning 或 critical）
     */
    private BigDecimal threshold;

    /**
     * 该指标的风险等级
     */
    private RiskLevel riskLevel;

    /**
     * 风险原因描述
     */
    private String reason;
}
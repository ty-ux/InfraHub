package com.infrahub.release.dto;

import com.infrahub.release.enums.RiskLevel;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 资源池风险评估响应
 */
@Data
@Builder
public class ResourcePoolRiskResponse {

    private Long resourcePoolId;
    private String poolCode;
    private String poolName;
    private String resourcePoolName;

    private Long envId;
    private String envName;

    /**
     * 综合风险等级（取所有指标中最高等级）
     */
    private RiskLevel riskLevel;

    /**
     * 风险原因列表（WARNING/CRITICAL 指标的原因摘要）
     */
    private List<String> riskReasons;

    /**
     * CPU 使用率百分比
     */
    private BigDecimal cpuUsagePercent;

    /**
     * 内存使用率百分比
     */
    private BigDecimal memoryUsagePercent;

    /**
     * 磁盘使用率百分比
     */
    private BigDecimal diskUsagePercent;

    /**
     * 在线主机数
     */
    private Long onlineHostCount;

    /**
     * 离线主机数
     */
    private Long offlineHostCount;

    /**
     * 未知状态主机数
     */
    private Long unknownHostCount;

    /**
     * 各指标的风险详情
     */
    private List<CapacityRiskItemResponse> risks;

    /**
     * 评估时间
     */
    private OffsetDateTime evaluatedAt;
}
package com.infrahub.release.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 容量阈值配置
 * 用于定义 CPU/MEMORY/DISK 使用率和主机数量的警告/临界阈值
 */
@Data
@Builder
public class CapacityThreshold {

    // CPU 使用率阈值 (百分比)
    private BigDecimal cpuWarningThreshold;
    private BigDecimal cpuCriticalThreshold;

    // 内存使用率阈值 (百分比)
    private BigDecimal memoryWarningThreshold;
    private BigDecimal memoryCriticalThreshold;

    // 磁盘使用率阈值 (百分比)
    private BigDecimal diskWarningThreshold;
    private BigDecimal diskCriticalThreshold;

    // 离线主机数阈值
    private Long offlineHostWarningThreshold;
    private Long offlineHostCriticalThreshold;

    // 未知状态主机数阈值
    private Long unknownHostWarningThreshold;
    private Long unknownHostCriticalThreshold;

    /**
     * 创建默认阈值配置
     * CPU/MEMORY/DISK: warning=70, critical=85
     * HOST_OFFLINE/UNKNOWN_HOST: warning=1, critical=3
     */
    public static CapacityThreshold defaultThreshold() {
        return CapacityThreshold.builder()
                .cpuWarningThreshold(new BigDecimal("70"))
                .cpuCriticalThreshold(new BigDecimal("85"))
                .memoryWarningThreshold(new BigDecimal("70"))
                .memoryCriticalThreshold(new BigDecimal("85"))
                .diskWarningThreshold(new BigDecimal("70"))
                .diskCriticalThreshold(new BigDecimal("85"))
                .offlineHostWarningThreshold(1L)
                .offlineHostCriticalThreshold(3L)
                .unknownHostWarningThreshold(1L)
                .unknownHostCriticalThreshold(3L)
                .build();
    }
}
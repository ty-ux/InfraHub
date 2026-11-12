package com.infrahub.release.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 治理记录汇总响应 DTO
 * Contract 定义字段: resourcePoolId, resourcePoolName, totalExecutions, successExecutions,
 * failedExecutions, cancelledExecutions, avgDurationMs, lastExecutionAt, impactByType
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GovernanceRecordSummaryResponse {

    /**
     * 资源池 ID
     */
    private Long resourcePoolId;

    /**
     * 资源池名称
     */
    private String resourcePoolName;

    /**
     * 总执行次数
     */
    private Long totalExecutions;

    /**
     * 成功次数
     */
    private Long successExecutions;

    /**
     * 失败次数
     */
    private Long failedExecutions;

    /**
     * 取消次数
     */
    private Long cancelledExecutions;

    /**
     * 平均执行耗时 (毫秒)
     */
    private Double avgDurationMs;

    /**
     * 最近执行时间
     */
    private OffsetDateTime lastExecutionAt;

    /**
     * 按影响类型统计
     */
    private Map<String, Long> impactByType;
}
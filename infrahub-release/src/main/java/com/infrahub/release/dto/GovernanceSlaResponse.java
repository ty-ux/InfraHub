package com.infrahub.release.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * SLA 闭环效率响应 DTO.
 * Field names defined in V2/L Contract section 4.3.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GovernanceSlaResponse {

    /**
     * 平均建议单确认耗时（毫秒）
     */
    private Double avgSuggestionAckDurationMs;

    /**
     * 平均计划确认耗时（毫秒）
     */
    private Double avgPlanConfirmDurationMs;

    /**
     * 平均执行完成耗时（毫秒）
     */
    private Double avgExecutionCompleteDurationMs;

    /**
     * 超时建议单数
     */
    private Long overdueSuggestionCount;

    /**
     * 超时计划数
     */
    private Long overduePlanCount;

    /**
     * 超时执行记录数
     */
    private Long overdueExecutionCount;

    /**
     * SLA 目标值（毫秒）
     */
    private Long slaTargetMs;

    /**
     * SLA 达标率（0.0-1.0）
     */
    private Double slaHitRate;
}

package com.infrahub.release.dto;

import lombok.Data;

import java.time.OffsetDateTime;

/**
 * SLA 查询参数.
 * Field names defined in V2/L Contract section 3.
 */
@Data
public class GovernanceOpsSlaQuery {

    /**
     * 环境 ID 筛选
     */
    private Long envId;

    /**
     * 资源池 ID 筛选
     */
    private Long resourcePoolId;

    /**
     * 开始时间
     */
    private OffsetDateTime startTime;

    /**
     * 结束时间
     */
    private OffsetDateTime endTime;

    /**
     * 建议单确认 SLA 目标（毫秒）
     * 默认值：86400000 (24小时)
     */
    private Long suggestionAckSlaMs;

    /**
     * 计划确认 SLA 目标（毫秒）
     * 默认值：86400000 (24小时)
     */
    private Long planConfirmSlaMs;

    /**
     * 执行完成 SLA 目标（毫秒）
     * 默认值：259200000 (72小时)
     */
    private Long executionCompleteSlaMs;
}

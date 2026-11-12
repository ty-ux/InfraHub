package com.infrahub.release.dto;

import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 效果评估查询参数.
 * Field names defined in V2/L Contract section 3.
 */
@Data
public class GovernanceOpsEffectivenessQuery {

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
     * 影响类型筛选
     */
    private String impactType;
}

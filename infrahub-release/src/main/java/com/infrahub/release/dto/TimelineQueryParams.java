package com.infrahub.release.dto;

import com.infrahub.common.api.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.OffsetDateTime;

/**
 * 时间线查询参数 DTO
 * Contract 定义字段: subjectType, subjectId, actionPlanId, actionExecutionId, suggestionId,
 * eventType, startTime, endTime, pageNum, pageSize
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class TimelineQueryParams extends PageQuery {

    /**
     * 主体类型筛选
     */
    private String subjectType;

    /**
     * 主体 ID 筛选
     */
    private Long subjectId;

    /**
     * 动作计划 ID 筛选
     */
    private Long actionPlanId;

    /**
     * 执行记录 ID 筛选
     */
    private Long actionExecutionId;

    /**
     * 建议单 ID 筛选
     */
    private Long suggestionId;

    /**
     * 事件类型筛选
     */
    private String eventType;

    /**
     * 开始时间
     */
    private OffsetDateTime startTime;

    /**
     * 结束时间
     */
    private OffsetDateTime endTime;

    /**
     * 动作计划编号筛选 (Contract 3.6 关联字段名)
     */
    private String actionPlanNo;

    /**
     * 执行记录编号筛选 (Contract 3.6 关联字段名)
     */
    private String actionExecutionNo;

    /**
     * 建议单编号筛选 (Contract 3.6 关联字段名)
     */
    private String suggestionNo;

    /**
     * 结果状态筛选
     */
    private String resultStatus;
}
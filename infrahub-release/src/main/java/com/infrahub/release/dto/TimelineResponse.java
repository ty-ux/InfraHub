package com.infrahub.release.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 时间线响应 DTO
 * Contract 定义字段: subjectType, subjectId, subjectNo, nodes, totalCount
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TimelineResponse {

    /**
     * 主体类型
     */
    private String subjectType;

    /**
     * 主体 ID
     */
    private Long subjectId;

    /**
     * 主体编号
     */
    private String subjectNo;

    /**
     * 时间线节点列表
     */
    private List<TimelineNodeDto> nodes;

    /**
     * 总节点数
     */
    private Integer totalCount;
}
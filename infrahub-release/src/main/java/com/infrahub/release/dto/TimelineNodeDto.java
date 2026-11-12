package com.infrahub.release.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 时间线节点 DTO
 * Contract 定义字段: key, title, status, time, summary, actionType
 * K-B 扩展: beforePayload, afterPayload, evidencePayload, impactPayload, impactType, impactSummary, resultStatus
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TimelineNodeDto {

    /**
     * 节点唯一标识
     */
    private String key;

    /**
     * 节点显示标题
     */
    private String title;

    /**
     * 节点状态：pending/running/success/failed
     */
    private String status;

    /**
     * 节点发生时间
     */
    private OffsetDateTime time;

    /**
     * 节点摘要描述
     */
    private String summary;

    /**
     * 可触发动作类型
     */
    private String actionType;

    /**
     * 变更前状态 (Contract 3.6 关联字段)
     * IMPACT_RECORDED 事件需要返回此字段
     */
    private Map<String, Object> beforePayload;

    /**
     * 变更后状态 (Contract 3.6 关联字段)
     * IMPACT_RECORDED 事件需要返回此字段
     */
    private Map<String, Object> afterPayload;

    /**
     * 证据详情 (Contract 3.6 关联字段)
     * IMPACT_RECORDED 事件需��返回此字段
     */
    private Map<String, Object> evidencePayload;

    /**
     * 影响详情 (Contract 3.6 关联字段)
     * IMPACT_RECORDED 事件需要返回此字段
     */
    private Map<String, Object> impactPayload;

    /**
     * 影响类型 (Contract 3.6 关联字段)
     * IMPACT_RECORDED 事件需要返回此字段
     */
    private String impactType;

    /**
     * 影响摘要 (Contract 3.6 关联字段)
     * IMPACT_RECORDED 事件需要返回此字段
     */
    private String impactSummary;

    /**
     * 结果状态 (Contract 3.6 关联字段)
     */
    private String resultStatus;
}
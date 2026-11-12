package com.infrahub.release.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 按负责人维度分析响应项 DTO
 * Contract: v2-stage-s-contract.md Section 3.4.1
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GovernanceAlertFollowupByHandlerItem {

    private Long handlerId;

    private String handlerName;

    private Long totalFollowupCount;

    private Long completedCount;

    private Long inProgressCount;

    private Long cancelledCount;

    private Double completionRate;

    private Double averageFollowupMinutes;

    private Long firstFollowupCount;

    private Double averageFirstFollowupMinutes;
}

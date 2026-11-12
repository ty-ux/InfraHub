package com.infrahub.release.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 按通知维度分析分页响应 DTO
 * Contract: v2-stage-s-contract.md Section 3.6
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GovernanceAlertFollowupByNotificationPageResponse {

    private List<GovernanceAlertFollowupByNotificationItem> records;

    private Long total;

    private Integer pageNum;

    private Integer pageSize;
}

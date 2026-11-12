package com.infrahub.release.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 按告警维度分析分页响应 DTO
 * Contract: v2-stage-s-contract.md Section 3.5
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GovernanceAlertFollowupByAlertPageResponse {

    private List<GovernanceAlertFollowupByAlertItem> records;

    private Long total;

    private Integer pageNum;

    private Integer pageSize;
}

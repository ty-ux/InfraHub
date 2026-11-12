package com.infrahub.release.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 按负责人维度分析分页响应 DTO
 * Contract: v2-stage-s-contract.md Section 3.4
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GovernanceAlertFollowupByHandlerPageResponse {

    private List<GovernanceAlertFollowupByHandlerItem> records;

    private Long total;

    private Integer pageNum;

    private Integer pageSize;
}

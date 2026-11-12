package com.infrahub.release.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 复盘明细列表分页响应 DTO
 * Contract: v2-stage-s-contract.md Section 3.7
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GovernanceAlertFollowupReviewDetailPageResponse {

    private List<GovernanceAlertFollowupReviewDetailItem> records;

    private Long total;

    private Integer pageNum;

    private Integer pageSize;
}

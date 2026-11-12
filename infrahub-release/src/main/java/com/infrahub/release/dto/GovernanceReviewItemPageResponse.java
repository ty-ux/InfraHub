package com.infrahub.release.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 复盘条目分页响应
 * Contract: v2-stage-l-contract.md Section 4.6
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GovernanceReviewItemPageResponse {

    private List<GovernanceReviewItemResponse> records;

    private Long total;

    private Integer pageNum;

    private Integer pageSize;
}
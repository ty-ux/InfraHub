package com.infrahub.release.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 分派历史分页响应 DTO
 * Contract: v2-stage-o-contract.md Section 3.6
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GovernanceAlertAssignmentRecordPageResponse {

    private List<GovernanceAlertAssignmentRecordResponse> records;

    private Long total;

    private Integer pageNum;

    private Integer pageSize;
}

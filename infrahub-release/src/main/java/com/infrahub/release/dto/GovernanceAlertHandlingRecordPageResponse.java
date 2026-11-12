package com.infrahub.release.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 告警处理历史分页响应 DTO
 * Contract: v2-stage-n-contract.md Section 3.4
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GovernanceAlertHandlingRecordPageResponse {

    private List<GovernanceAlertHandlingRecordResponse> records;

    private Long total;

    private Integer pageNum;

    private Integer pageSize;
}

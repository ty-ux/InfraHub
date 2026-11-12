package com.infrahub.release.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 告警升级记录分页响应 DTO
 * Contract: v2-stage-o-contract.md Section 3.8
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GovernanceAlertEscalationRecordPageResponse {

    private List<GovernanceAlertEscalationRecordResponse> records;

    private Long total;

    private Integer pageNum;

    private Integer pageSize;
}
package com.infrahub.release.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 跟进记录分页响应 DTO
 * Contract: v2-stage-r-contract.md Section 3.4
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GovernanceAlertNotificationFollowupPageResponse {

    private List<GovernanceAlertNotificationFollowupResponse> records;

    private Long total;

    private Integer pageNum;

    private Integer pageSize;
}

package com.infrahub.release.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 跟进状态分布响应 DTO
 * Contract: v2-stage-s-contract.md Section 3.3
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GovernanceAlertFollowupStatusDistributionResponse {

    private List<GovernanceAlertFollowupStatusItem> byFollowUpStatus;

    private List<GovernanceAlertReceiptStatusItem> byReceiptStatus;

    private List<GovernanceAlertFollowupTypeItem> byFollowUpType;
}

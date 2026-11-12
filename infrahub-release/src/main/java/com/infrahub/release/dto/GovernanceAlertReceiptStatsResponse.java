package com.infrahub.release.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 回执效果统计响应 DTO
 * Contract: v2-stage-s-contract.md Section 3.2
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GovernanceAlertReceiptStatsResponse {

    private List<GovernanceAlertReceiptStatsByChannelItem> byChannelType;

    private List<GovernanceAlertReceiptStatsByUserItem> byTargetUser;

    private GovernanceAlertReceiptStatsOverall overall;
}

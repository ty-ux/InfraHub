package com.infrahub.release.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 按渠道类型回执统计项 DTO
 * Contract: v2-stage-s-contract.md Section 3.2.1
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GovernanceAlertReceiptStatsByChannelItem {

    private String channelType;

    private Long totalCount;

    private Long confirmedCount;

    private Long failedCount;

    private Long noResponseCount;

    private Double confirmationRate;
}

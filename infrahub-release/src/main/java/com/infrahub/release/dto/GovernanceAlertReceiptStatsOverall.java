package com.infrahub.release.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 回执效果整体汇总 DTO
 * Contract: v2-stage-s-contract.md Section 3.2.3
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GovernanceAlertReceiptStatsOverall {

    private Long totalCount;

    private Long confirmedCount;

    private Long failedCount;

    private Long noResponseCount;

    private Double confirmationRate;
}

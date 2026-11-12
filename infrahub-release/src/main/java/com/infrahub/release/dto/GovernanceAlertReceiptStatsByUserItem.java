package com.infrahub.release.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 按目标用户回执统计项 DTO
 * Contract: v2-stage-s-contract.md Section 3.2.2
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GovernanceAlertReceiptStatsByUserItem {

    private Long targetUserId;

    private String targetUserName;

    private Long totalCount;

    private Long confirmedCount;

    private Long failedCount;

    private Long noResponseCount;

    private Double confirmationRate;
}

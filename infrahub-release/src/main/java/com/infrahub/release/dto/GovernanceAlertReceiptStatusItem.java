package com.infrahub.release.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 按回执状态统计项 DTO
 * Contract: v2-stage-s-contract.md Section 3.3.2
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GovernanceAlertReceiptStatusItem {

    private String receiptStatus;

    private Long count;

    private Double percentage;
}

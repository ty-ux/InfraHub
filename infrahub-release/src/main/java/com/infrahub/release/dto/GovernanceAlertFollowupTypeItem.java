package com.infrahub.release.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 按跟进类型统计项 DTO
 * Contract: v2-stage-s-contract.md Section 3.3.3
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GovernanceAlertFollowupTypeItem {

    private String followUpType;

    private Long count;

    private Double percentage;
}

package com.infrahub.release.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 按跟进状态统计项 DTO
 * Contract: v2-stage-s-contract.md Section 3.3.1
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GovernanceAlertFollowupStatusItem {

    private String followUpStatus;

    private Long count;

    private Double percentage;
}

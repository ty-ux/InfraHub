package com.infrahub.release.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 复盘条目响应
 * Contract: v2-stage-l-contract.md Section 4.5
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GovernanceReviewItemResponse {

    private String itemType;

    private Long itemId;

    private String itemNo;

    private String title;

    private String riskLevel;

    private String status;

    private String reason;

    private String ownerName;

    private OffsetDateTime createdAt;

    private OffsetDateTime dueAt;

    private String relatedActionPlanNo;

    private String relatedExecutionNo;

    private String relatedSuggestionNo;
}
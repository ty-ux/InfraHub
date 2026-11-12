package com.infrahub.release.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class PrecheckSummaryResponse {

    private Long orderId;
    private String orderType;
    private String overallStatus;
    private Integer totalHosts;
    private Integer successHosts;
    private Integer failedHosts;
    private Integer totalSteps;
    private Integer failedSteps;
    private List<PrecheckHostSummaryItem> hostSummaries;
    private List<PrecheckFailureCategoryItem> failureCategories;
    private List<PrecheckSuggestionItem> suggestions;
    private LocalDateTime generatedAt;
}

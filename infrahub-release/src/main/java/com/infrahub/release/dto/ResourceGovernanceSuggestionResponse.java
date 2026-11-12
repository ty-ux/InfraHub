package com.infrahub.release.dto;

import com.infrahub.release.enums.RiskLevel;
import com.infrahub.release.enums.SourceType;
import com.infrahub.release.enums.SuggestionStatus;
import com.infrahub.release.enums.SuggestionType;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 资源治理建议响应
 */
@Data
@Builder
public class ResourceGovernanceSuggestionResponse {

    private Long id;
    private String suggestionNo;
    private SuggestionType suggestionType;
    private SourceType sourceType;
    private Long sourceId;
    private Long resourcePoolId;
    private String resourcePoolName;
    private Long envId;
    private String envName;
    private RiskLevel riskLevel;
    private String title;
    private String description;
    private SuggestionStatus status;
    private String reason;
    private Long createdBy;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private Long handledBy;
    private OffsetDateTime handledAt;
    private String handleNote;

    /**
     * 该建议单的活跃执行计划（DRAFT或CONFIRMED状态）
     * 前端用于判断是否显示"生成计划"或"查看计划"按钮
     */
    private ResourceGovernanceActionPlanResponse existingPlan;
}

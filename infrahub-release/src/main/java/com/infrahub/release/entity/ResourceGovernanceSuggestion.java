package com.infrahub.release.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.infrahub.release.enums.RiskLevel;
import com.infrahub.release.enums.SourceType;
import com.infrahub.release.enums.SuggestionStatus;
import com.infrahub.release.enums.SuggestionType;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName("release_resource_governance_suggestion")
public class ResourceGovernanceSuggestion {

    private Long id;
    private String suggestionNo;
    private SuggestionType suggestionType;
    private SourceType sourceType;
    private Long sourceId;
    private Long resourcePoolId;
    private Long envId;
    private RiskLevel riskLevel;
    private String title;
    private String description;
    private SuggestionStatus status;
    private String reason;

    @TableField(fill = FieldFill.INSERT)
    private Long createdBy;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;

    private Long handledBy;
    private OffsetDateTime handledAt;
    private String handleNote;
}

package com.infrahub.release.dto;

import com.infrahub.common.api.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 资源治理建议查询参数
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ResourceGovernanceSuggestionQuery extends PageQuery {

    /**
     * 资源池ID筛选
     */
    private Long resourcePoolId;

    /**
     * 环境ID筛选
     */
    private Long envId;

    /**
     * 风险等级筛选
     */
    private String riskLevel;

    /**
     * 建议类型筛选
     */
    private String suggestionType;

    /**
     * 状态筛选
     */
    private String status;
}

package com.infrahub.release.dto;

import com.infrahub.common.api.PageQuery;
import com.infrahub.release.enums.RiskLevel;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 资源池风险列表查询参数
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class RiskQuery extends PageQuery {

    /**
     * 环境ID筛选
     */
    private Long envId;

    /**
     * 风险等级筛选
     */
    private RiskLevel riskLevel;

    /**
     * 资源池ID筛选
     */
    private Long resourcePoolId;
}
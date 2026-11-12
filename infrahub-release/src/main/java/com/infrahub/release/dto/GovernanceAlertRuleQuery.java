package com.infrahub.release.dto;

import lombok.Data;

/**
 * 告警规则查询参数
 */
@Data
public class GovernanceAlertRuleQuery {

    private String metricType;

    private String alertLevel;

    private Long envId;

    private Long resourcePoolId;

    private Long serviceId;

    private Boolean enabled;

    private Integer pageNum;

    private Integer pageSize;

    public Integer getPageNum() {
        return pageNum != null && pageNum > 0 ? pageNum : 1;
    }

    public Integer getPageSize() {
        return pageSize != null && pageSize > 0 && pageSize <= 100 ? pageSize : 20;
    }
}
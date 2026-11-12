package com.infrahub.release.dto;

import com.infrahub.common.api.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class MonitorAlertRuleQuery extends PageQuery {

    private String targetType;
    private Long targetId;
    private String level;
    private Boolean enabled;
    private String keyword;
}

package com.infrahub.release.dto;

import com.infrahub.common.api.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class ResourceGovernanceActionPlanQuery extends PageQuery {

    private Long resourcePoolId;
    private Long envId;
    private String actionType;
    private String status;
}

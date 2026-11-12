package com.infrahub.release.dto;

import com.infrahub.common.api.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class ResourceGovernanceActionExecutionQuery extends PageQuery {

    private Long planId;
    private Long suggestionId;
    private String status;
}
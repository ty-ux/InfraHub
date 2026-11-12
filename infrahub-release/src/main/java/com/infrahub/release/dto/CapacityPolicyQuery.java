package com.infrahub.release.dto;

import com.infrahub.common.api.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Query parameters for capacity threshold policy search.
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class CapacityPolicyQuery extends PageQuery {

    private Long resourcePoolId;
    private Long envId;
    private String metricType;
    private Boolean enabled;
}

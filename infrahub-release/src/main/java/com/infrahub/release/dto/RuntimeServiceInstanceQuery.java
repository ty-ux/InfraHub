package com.infrahub.release.dto;

import com.infrahub.common.api.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class RuntimeServiceInstanceQuery extends PageQuery {

    private String keyword;
    private Long serviceId;
    private Long envId;
    private Long hostId;
    private String instanceStatus;
    private String healthStatus;
}

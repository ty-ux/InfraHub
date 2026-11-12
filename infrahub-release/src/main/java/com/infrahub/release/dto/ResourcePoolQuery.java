package com.infrahub.release.dto;

import com.infrahub.common.api.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class ResourcePoolQuery extends PageQuery {

    private String keyword;
    private String poolType;
    private String status;
}

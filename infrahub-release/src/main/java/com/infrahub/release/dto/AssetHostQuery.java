package com.infrahub.release.dto;

import com.infrahub.common.api.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class AssetHostQuery extends PageQuery {

    private String keyword;
    private String hostStatus;
    private String agentStatus;
    private Long envId;
    private Long resourcePoolId;
}

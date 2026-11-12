package com.infrahub.release.dto;

import com.infrahub.common.api.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Query parameters for resource governance record search.
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ResourceGovernanceRecordQuery extends PageQuery {

    private Long resourcePoolId;
    private Long hostId;
    private Long runtimeInstanceId;
    private Long releaseOrderId;
    private Long rollbackOrderId;
    private Long envId;
    private Long serviceId;
    private String eventType;
    private String resultStatus;
    private String subjectType;
    private Long subjectId;
}

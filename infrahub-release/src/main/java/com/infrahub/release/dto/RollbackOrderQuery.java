package com.infrahub.release.dto;

import com.infrahub.common.api.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.OffsetDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
public class RollbackOrderQuery extends PageQuery {

    private String keyword;
    private String releaseOrderKeyword;
    private String status;
    private Long appId;
    private Long serviceId;
    private Long envId;
    private OffsetDateTime createdAtStart;
    private OffsetDateTime createdAtEnd;
    private Boolean hasFailureHint;

    // V1.19: New field for focus sorting mode
    private String sortMode;
}

package com.infrahub.release.dto;

import com.infrahub.common.api.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.OffsetDateTime;

/**
 * Query parameters for audit log search.
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class AuditLogQuery extends PageQuery {

    private String bizType;
    private Long bizId;
    private String action;
    private String result;
    private Long operatorId;
    private OffsetDateTime startTime;
    private OffsetDateTime endTime;
}

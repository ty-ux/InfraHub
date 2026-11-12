package com.infrahub.release.dto;

import com.infrahub.common.api.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class MonitorSnapshotQuery extends PageQuery {

    private Long snapshotId;
    private String targetType;
    private Long targetId;
    private String source;
}

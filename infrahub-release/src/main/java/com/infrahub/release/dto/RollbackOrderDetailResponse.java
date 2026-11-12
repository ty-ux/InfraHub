package com.infrahub.release.dto;

import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;

@Data
public class RollbackOrderDetailResponse {

    private Long id;
    private String rollbackNo;
    private String status;
    private String reason;
    private OffsetDateTime createdAt;

    private String appName;
    private String serviceName;
    private String envName;

    private String fromVersionNo;
    private String toVersionNo;
    private String releaseOrderNo;

    private String latestDeployStatus;
    private String latestFailureHint;
    private Boolean hasPrecheckRecords;
    private Boolean hasDeployRecords;

    private String currentVersionNo;
    private String currentVersionSourceReleaseOrderNo;
    private String currentVersionSourceRollbackOrderNo;

    private List<TimelineNode> timeline;
    private List<ExecutionHistoryItem> executionHistory;
}

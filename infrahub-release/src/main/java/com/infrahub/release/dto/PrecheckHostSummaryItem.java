package com.infrahub.release.dto;

import lombok.Data;

@Data
public class PrecheckHostSummaryItem {

    private Long hostId;
    private String hostName;
    private Long instanceId;
    private String instanceName;
    private String status;
    private Integer successSteps;
    private Integer failedSteps;
    private String lastErrorMessage;
}

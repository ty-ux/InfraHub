package com.infrahub.release.dto;

import lombok.Data;
import java.time.LocalTime;

@Data
public class ReleaseEnvironmentPolicyDto {
    private Long id;
    private Long envId;
    private String action;
    private String riskLevel;
    private Boolean enabled;
    private Boolean approvalRequired;
    private Boolean precheckRequired;
    private Boolean blocked;
    private Boolean freezeWindowEnabled;
    private LocalTime freezeWindowStart;
    private LocalTime freezeWindowEnd;
    private String freezeWindowDays;
    private String description;
}

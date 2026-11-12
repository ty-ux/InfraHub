package com.infrahub.release.dto;

import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;

@Data
public class DeployConfigCheckResponse {

    private Long appId;
    private String appName;
    private Long envId;
    private String envName;
    private Boolean overallComplete;
    private List<DeployConfigServiceCheckItem> services;
    private List<String> missingKeys;
    private List<String> warnings;
    private OffsetDateTime checkedAt;
}

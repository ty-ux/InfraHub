package com.infrahub.release.dto;

import lombok.Data;

import java.util.List;

@Data
public class DeployConfigServiceCheckItem {

    private Long serviceId;
    private String serviceName;
    private Boolean complete;
    private List<String> missingKeys;
    private List<String> warnings;
    private List<DeployConfigItem> configItems;
}

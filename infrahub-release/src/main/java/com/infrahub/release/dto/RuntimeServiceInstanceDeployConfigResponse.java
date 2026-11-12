package com.infrahub.release.dto;

import lombok.Data;

@Data
public class RuntimeServiceInstanceDeployConfigResponse {

    private Long instanceId;
    private Long serviceId;
    private Long envId;
    private Long hostId;
    private Long versionId;
    private String deployRoot;
    private Integer deployPort;
    private String pidFile;
    private String logFile;
    private String healthUrl;
    private String source;
}

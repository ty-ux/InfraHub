package com.infrahub.release.dto;

import lombok.Data;

import java.time.OffsetDateTime;

@Data
public class RuntimeServiceInstanceStatusResponse {

    private Long instanceId;
    private Long serviceId;
    private Long envId;
    private Long hostId;
    private Long versionId;
    private boolean running;
    private String instanceStatus;
    private String composeProject;
    private String pid;
    private boolean portListening;
    private String healthStatus;
    private String healthBody;
    private OffsetDateTime checkedAt;
    private String stdout;
    private String stderr;
    private String errorMessage;
}

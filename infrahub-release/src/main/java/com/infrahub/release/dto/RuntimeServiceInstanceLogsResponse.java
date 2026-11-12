package com.infrahub.release.dto;

import lombok.Data;

import java.time.OffsetDateTime;

@Data
public class RuntimeServiceInstanceLogsResponse {

    private Long instanceId;
    private Long serviceId;
    private Long envId;
    private Long hostId;
    private Long versionId;
    private boolean success;
    private int lines;
    private String logPath;
    private String content;
    private String stdout;
    private String stderr;
    private String errorMessage;
    private OffsetDateTime fetchedAt;
}

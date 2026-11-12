package com.infrahub.release.dto;

import lombok.Data;

@Data
public class ReleasePrecheckInstanceResponse {

    private boolean success;
    private Long hostId;
    private Long instanceId;
    private String artifactUri;
    private String stdout;
    private String stderr;
    private String errorMessage;
}

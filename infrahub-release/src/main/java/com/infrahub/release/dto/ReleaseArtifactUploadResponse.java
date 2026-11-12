package com.infrahub.release.dto;

import lombok.Data;

@Data
public class ReleaseArtifactUploadResponse {

    private Long artifactId;
    private String artifactName;
    private String artifactUri;
    private String checksum;
    private Long sizeBytes;
    private String repositoryType;
}

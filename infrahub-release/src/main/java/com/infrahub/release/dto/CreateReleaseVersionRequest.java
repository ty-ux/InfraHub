package com.infrahub.release.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CreateReleaseVersionRequest {

    @NotNull
    private Long appId;
    @NotBlank
    private String versionNo;
    private String gitCommit;
    private String branchName;
    private String buildNo;
    @NotNull
    private Long artifactId;
}

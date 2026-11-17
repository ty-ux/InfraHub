package com.infrahub.release.pipeline;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record ComposePipelineSpec(
        @NotNull @Positive Long appId,
        @NotNull @Positive Long envId,
        @NotNull @Positive Long serviceId,
        @NotBlank @Pattern(regexp = "[a-z0-9][a-z0-9_-]{0,63}") String pipelineCode,
        @NotBlank @Size(max = 128) String pipelineName,
        @NotBlank @Size(max = 512) String repositoryUrl,
        @NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._/-]{0,127}") String branch,
        @Positive Long gitSshSecretRefId,
        @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_.-]{0,127}") String dockerContext,
        @NotBlank @Pattern(regexp = "[a-z0-9][a-z0-9.-]*(?::[0-9]{1,5})?") @Size(max = 128) String registry,
        @NotNull @Valid Image backend,
        @NotNull @Valid Image frontend,
        @NotBlank @Size(max = 240) String composeFile,
        @NotBlank @Pattern(regexp = "/[A-Za-z0-9_-][A-Za-z0-9_./-]{0,220}") String deployRoot,
        boolean preflight,
        @Size(max = 512) String healthUrl,
        @Min(5) @Max(30) Integer healthTimeoutSeconds
) {
    public record Image(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_.-]{0,62}") String composeService,
            @NotBlank @Pattern(regexp = "[a-z0-9]+(?:[._-]+[a-z0-9]+)*(?:/[a-z0-9]+(?:[._-]+[a-z0-9]+)*)*")
            @Size(max = 180) String repository,
            @NotBlank @Size(max = 240) String context,
            @NotBlank @Size(max = 240) String dockerfile
    ) {}
}

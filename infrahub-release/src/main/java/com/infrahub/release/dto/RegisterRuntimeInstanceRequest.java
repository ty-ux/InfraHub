package com.infrahub.release.dto;

import jakarta.validation.constraints.*;

public record RegisterRuntimeInstanceRequest(
        @NotNull @Positive Long serviceId,
        @NotNull @Positive Long envId,
        @NotNull @Positive Long hostId,
        @NotBlank @Size(max = 128) String instanceName) {}

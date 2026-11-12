package com.infrahub.release.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.List;

public record ApplicationRollbackRequest(@NotNull @Positive Long appId, @NotNull @Positive Long envId,
                                         @NotNull @Positive Long serviceId, @NotNull @Positive Long versionId,
                                         @NotEmpty List<@NotNull @Positive Long> instanceIds) {
}

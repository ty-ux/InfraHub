package com.infrahub.release.dto;

import jakarta.validation.constraints.*;

/** References a local private-key file, never a password or private-key body. */
public record HostSshSetupRequest(
        @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z_][A-Za-z0-9_.-]*") String username,
        @NotNull @Min(1) @Max(65535) Integer sshPort,
        @NotNull @Positive Long privateKeySecretRefId,
        @NotNull Boolean enabled) {}

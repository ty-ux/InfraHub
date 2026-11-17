package com.infrahub.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.Set;

public final class EmailAuthRequests {
    private EmailAuthRequests() {
    }

    public record Address(@NotBlank @Email @Size(max = 254) String email) {
    }

    public record Code(@NotBlank @Email @Size(max = 254) String email,
                       @NotNull @Pattern(regexp = "[0-9]{6}") String code) {
    }

    public record BindRequest(@NotBlank @Email @Size(max = 254) String email,
                              @NotBlank @Size(max = 72) String password) {
    }

    public record BindConfirm(@NotBlank @Email @Size(max = 254) String email,
                              @NotBlank @Size(max = 72) String password,
                              @NotNull @Pattern(regexp = "[0-9]{6}") String code) {
    }

    public record Invite(@NotBlank @Email @Size(max = 254) String email,
                         @NotNull @Size(max = 250) Set<@NotNull @Positive Long> roleIds) {
    }

    public record Register(@NotNull @Pattern(regexp = "[A-Za-z0-9_-]{43}") String token,
                           @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_.-]*") String username,
                           @NotBlank @Size(max = 128) String displayName,
                           @NotBlank @Size(max = 72) String password) {
    }
}

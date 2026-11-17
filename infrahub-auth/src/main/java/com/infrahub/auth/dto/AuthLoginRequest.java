package com.infrahub.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AuthLoginRequest {

    @NotBlank
    @Size(max = 254)
    private String username;

    @NotBlank
    @Size(max = 72)
    private String password;
}

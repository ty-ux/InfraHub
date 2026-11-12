package com.infrahub.release.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class UpsertApprovalPolicy {
    @NotBlank
    private String bizType;

    @NotBlank
    private String action;

    private Boolean enabled = true;
}

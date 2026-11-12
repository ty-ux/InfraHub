package com.infrahub.release.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class GovernanceEvidenceChainQuery {
    @NotBlank(message = "bizType不能为空")
    private String bizType;

    @NotNull(message = "bizId不能为空")
    private Long bizId;
}

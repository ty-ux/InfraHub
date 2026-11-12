package com.infrahub.release.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ApproveRequest {

    @NotBlank(message = "审批意见不能为空")
    private String approveNote;
}
package com.infrahub.release.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Map;

@Data
public class CreateApprovalRequest {

    @NotBlank(message = "业务类型不能为空")
    private String bizType;

    @NotNull(message = "业务ID不能为空")
    private Long bizId;

    @NotBlank(message = "动作不能为空")
    private String action;

    private String applyNote;

    private Map<String, Object> requestPayload;
}
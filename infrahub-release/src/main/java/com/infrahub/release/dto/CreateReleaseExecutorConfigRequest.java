package com.infrahub.release.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CreateReleaseExecutorConfigRequest {

    @NotBlank(message = "执行器名称不能为空")
    private String executorName;

    @NotBlank(message = "执行器类型不能为空")
    private String executorType;

    @NotNull(message = "环境 ID 不能为空")
    private Long envId;

    private String configPayload;

    private Boolean enabled = true;
}
package com.infrahub.release.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class UpdateHealthCheckRequest {

    @NotBlank(message = "目标类型不能为空")
    private String targetType;

    @NotNull(message = "目标ID不能为空")
    private Long targetId;

    @NotBlank(message = "检查类型不能为空")
    private String checkType;

    private String checkEndpoint;

    private String expectedResult;

    @NotNull(message = "超时时间不能为空")
    private Integer timeoutSeconds;

    @NotNull(message = "启用状态不能为空")
    private Boolean enabled;
}

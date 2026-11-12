package com.infrahub.release.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CreateCanaryPlanRequest {

    @NotNull(message = "批次数量不能为空")
    @Min(value = 2, message = "批次数量至少为2")
    private Integer batchCount;

    @NotNull(message = "批次间隔秒数不能为空")
    @Min(value = 0, message = "批次间隔秒数不能为负数")
    private Integer batchIntervalSeconds;

    @NotNull(message = "自动回滚开关不能为空")
    private Boolean autoRollbackEnabled;

    // E2: 质量门禁配置（可选，有默认值）
    private Boolean qualityGateEnabled = true;
    private Integer maxFiringAlerts = 0;
    private String gateFailureAction = "BLOCK_AND_ROLLBACK";
}
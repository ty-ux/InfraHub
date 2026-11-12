package com.infrahub.release.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CapacityPolicyRequest {

    private Long resourcePoolId;

    private Long envId;

    @NotBlank(message = "策略名称不能为空")
    private String policyName;

    @NotBlank(message = "指标类型不能为空")
    private String metricType;

    @NotNull(message = "警告阈值不能为空")
    @Min(value = 0, message = "警告阈值不能小于0")
    @Max(value = 100, message = "警告阈值不能大于100")
    private Integer warningThreshold;

    @NotNull(message = "严重阈值不能为空")
    @Min(value = 0, message = "严重阈值不能小于0")
    @Max(value = 100, message = "严重阈值不能大于100")
    private Integer criticalThreshold;

    private Boolean enabled;

    private String description;

    /**
     * Validates that warning threshold is less than critical threshold.
     *
     * @return true if warningThreshold < criticalThreshold, false otherwise
     */
    public boolean isValidThresholds() {
        return warningThreshold != null && criticalThreshold != null
                && warningThreshold < criticalThreshold;
    }
}

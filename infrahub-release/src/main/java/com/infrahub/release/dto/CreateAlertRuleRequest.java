package com.infrahub.release.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class CreateAlertRuleRequest {

    @NotBlank(message = "规则名称不能为空")
    private String ruleName;

    @NotBlank(message = "目标类型不能为空")
    private String targetType;

    private Long targetId;

    private String targetGroupCode;

    @NotBlank(message = "指标名称不能为空")
    private String metricName;

    @NotBlank(message = "比较操作符不能为空")
    private String compareOp;

    @NotNull(message = "阈值不能为空")
    private BigDecimal thresholdValue;

    @NotNull(message = "持续时间不能为空")
    private Integer durationSeconds;

    @NotBlank(message = "告警等级不能为空")
    private String level;

    private String notifyChannel;

    @NotNull(message = "启用状态不能为空")
    private Boolean enabled;
}

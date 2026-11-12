package com.infrahub.release.dto;

import lombok.Data;

@Data
public class ExecutionRiskItem {

    private String riskType;
    private String level;
    private String message;
    private String suggestion;
}

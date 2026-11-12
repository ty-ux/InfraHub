package com.infrahub.release.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class ExecutionContextResponse {

    private Long orderId;
    private String orderType;
    private Long appId;
    private String appName;
    private Long envId;
    private String envName;
    private Long serviceId;
    private String serviceName;
    private Long currentVersionId;
    private String currentVersionNo;
    private Long targetVersionId;
    private String targetVersionNo;
    private Boolean deployConfigComplete;
    private String precheckStatus;
    private String riskLevel;
    private List<ExecutionRiskItem> risks;
    private List<VersionCompareItem> versionCompare;
    private LocalDateTime generatedAt;
}

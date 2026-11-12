package com.infrahub.release.dto;

import lombok.Data;

import java.util.Map;

/**
 * 告警处理请求 DTO
 * Contract: v2-stage-n-contract.md Section 3.1
 */
@Data
public class GovernanceAlertHandleRequest {

    private String handleNote;

    private Map<String, Object> evidencePayload;

    private Long operatorId;

    private String operatorName;
}

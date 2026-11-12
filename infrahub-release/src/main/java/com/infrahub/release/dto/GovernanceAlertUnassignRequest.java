package com.infrahub.release.dto;

import lombok.Data;

import java.util.Map;

/**
 * 告警取消分派请求 DTO
 * Contract: v2-stage-o-contract.md Section 3.3
 */
@Data
public class GovernanceAlertUnassignRequest {

    private String unassignReason;

    private Map<String, Object> evidencePayload;

    private Long operatorId;

    private String operatorName;
}

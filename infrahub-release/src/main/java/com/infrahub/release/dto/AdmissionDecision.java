package com.infrahub.release.dto;

import lombok.Data;

@Data
public class AdmissionDecision {
    private boolean allowed;
    private boolean approvalRequired;
    private String blockedReason;
    private Long matchedPolicyId;
    private boolean precheckRequired;
}

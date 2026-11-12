package com.infrahub.release.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 确认收讫请求 DTO
 * Contract: v2-stage-r-contract.md Section 2.2
 */
@Data
public class GovernanceAlertReceiptConfirmRequest {

    @NotBlank(message = "收讫状态不能为空")
    private String receiptStatus;

    private String receiptNote;

    private String evidencePayload;
}

package com.infrahub.release.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Result of an execute operation that may require approval.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecuteResult {
    /**
     * Approval request ID if approval was created.
     * null means direct execution (no approval required).
     */
    private Long approvalId;

    /**
     * Whether the action was executed directly without approval.
     */
    private Boolean directExecuted;

    public static ExecuteResult directExecuted() {
        return ExecuteResult.builder()
                .approvalId(null)
                .directExecuted(true)
                .build();
    }

    public static ExecuteResult pendingApproval(Long approvalId) {
        return ExecuteResult.builder()
                .approvalId(approvalId)
                .directExecuted(false)
                .build();
    }
}
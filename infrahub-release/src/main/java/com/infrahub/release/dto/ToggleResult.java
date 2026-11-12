package com.infrahub.release.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Result of a toggle operation that may require approval.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToggleResult {
    /**
     * Approval request ID if approval was created.
     * null means direct execution (no approval required).
     */
    private Long approvalId;

    /**
     * Whether the action was executed directly without approval.
     */
    private Boolean directExecuted;

    public static ToggleResult directExecuted() {
        return ToggleResult.builder()
                .approvalId(null)
                .directExecuted(true)
                .build();
    }

    public static ToggleResult pendingApproval(Long approvalId) {
        return ToggleResult.builder()
                .approvalId(approvalId)
                .directExecuted(false)
                .build();
    }
}

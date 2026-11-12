package com.infrahub.release.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * Represents a single execution attempt in the history for release/rollback orders.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecutionHistoryItem {

    private Integer attemptNumber;
    private Long deployRecordId;
    private String operationType;
    private String status;
    private OffsetDateTime startedAt;
    private OffsetDateTime endedAt;
    private String summary;
    private String failureHint;
}

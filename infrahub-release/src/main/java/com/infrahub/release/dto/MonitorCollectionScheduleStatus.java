package com.infrahub.release.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * Status DTO for scheduled collection.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MonitorCollectionScheduleStatus {

    /**
     * Whether scheduled collection is enabled.
     */
    private boolean enabled;

    /**
     * Fixed delay in milliseconds between runs.
     */
    private long fixedDelayMs;

    /**
     * Last run timestamp.
     */
    private OffsetDateTime lastRunAt;

    /**
     * Total target count from last run.
     */
    private int lastTotalCount;

    /**
     * Success count from last run.
     */
    private int lastSuccessCount;

    /**
     * Failure count from last run.
     */
    private int lastFailureCount;

    /**
     * Last run message or error.
     */
    private String lastMessage;
}
package com.infrahub.release.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response for monitor target collection.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CollectMonitorTargetResponse {

    /**
     * Created snapshot ID.
     */
    private Long snapshotId;

    /**
     * Target type from the monitor target.
     */
    private String targetType;

    /**
     * Target ID from the monitor target.
     */
    private Long targetId;

    /**
     * Snapshot source, fixed to "COLLECTOR".
     */
    private String source;

    /**
     * Whether collection was successful.
     */
    private boolean success;

    /**
     * Success or failure message.
     */
    private String message;
}

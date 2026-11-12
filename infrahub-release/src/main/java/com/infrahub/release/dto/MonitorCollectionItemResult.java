package com.infrahub.release.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Result for a single monitor target collection.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MonitorCollectionItemResult {

    /**
     * Monitor target ID.
     */
    private Long monitorTargetId;

    /**
     * Target type (HOST / SERVICE / INSTANCE).
     */
    private String targetType;

    /**
     * Target ID (host/service/instance ID).
     */
    private Long targetId;

    /**
     * Whether collection was successful.
     */
    private boolean success;

    /**
     * Created snapshot ID (only when success).
     */
    private Long snapshotId;

    /**
     * Success or failure message.
     */
    private String message;
}

package com.infrahub.release.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Response for run-once monitor collection.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RunOnceMonitorCollectionResponse {

    /**
     * Total number of enabled targets scanned.
     */
    private int totalCount;

    /**
     * Number of successful collections.
     */
    private int successCount;

    /**
     * Number of failed collections.
     */
    private int failureCount;

    /**
     * Detailed results for each target.
     */
    private List<MonitorCollectionItemResult> results;
}

package com.infrahub.release.dto;

import lombok.Data;

/**
 * Request for run-once monitor collection.
 */
@Data
public class RunOnceMonitorCollectionRequest {

    /**
     * Optional filter by target type (HOST / SERVICE / INSTANCE).
     * If null, scan all enabled targets.
     */
    private String targetType;
}

package com.infrahub.release.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * Represents a single node in the execution timeline for release/rollback orders.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TimelineNode {

    /**
     * Unique key for this node (e.g., "created", "precheck", "deploy", "pipeline", "health-check", "final")
     */
    private String key;

    /**
     * Display title for this node
     */
    private String title;

    /**
     * Node status: pending, running, success, failed
     */
    private String status;

    /**
     * Timestamp when this node occurred (may be null if not yet executed)
     */
    private OffsetDateTime time;

    /**
     * Brief summary text for this node
     */
    private String summary;

    /**
     * Action type that can be triggered from this node:
     * PRECHECK, DEPLOY_RECORDS, PIPELINE_RUN, or NONE
     */
    private String actionType;

    public enum ActionType {
        NONE,
        PRECHECK,
        DEPLOY_RECORDS,
        PIPELINE_RUN
    }

    public enum NodeKey {
        CREATED,
        PRECHECK,
        PIPELINE,
        DEPLOY,
        HEALTH_CHECK,
        FINAL
    }

    public enum NodeStatus {
        PENDING,
        RUNNING,
        SUCCESS,
        FAILED
    }
}
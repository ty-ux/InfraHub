package com.infrahub.release.service;

/**
 * Constants for resource governance event types.
 */
public final class GovernanceEventType {

    public static final String RELEASE_RESOURCE_ALLOCATED = "RELEASE_RESOURCE_ALLOCATED";
    public static final String RELEASE_RESOURCE_FAILED = "RELEASE_RESOURCE_FAILED";
    public static final String RELEASE_RESOURCE_RELEASED = "RELEASE_RESOURCE_RELEASED";
    public static final String CANARY_BATCH_RESOURCE_ALLOCATED = "CANARY_BATCH_RESOURCE_ALLOCATED";
    public static final String CANARY_BATCH_RESOURCE_FAILED = "CANARY_BATCH_RESOURCE_FAILED";
    public static final String AUTO_ROLLBACK_RESOURCE_RESTORED = "AUTO_ROLLBACK_RESOURCE_RESTORED";
    public static final String RESOURCE_CAPACITY_RISK_EVALUATED = "RESOURCE_CAPACITY_RISK_EVALUATED";

    // Action execution governance events
    public static final String RESOURCE_GOVERNANCE_ACTION_EXECUTION_STARTED = "RESOURCE_GOVERNANCE_ACTION_EXECUTION_STARTED";
    public static final String RESOURCE_GOVERNANCE_ACTION_EXECUTION_COMPLETED = "RESOURCE_GOVERNANCE_ACTION_EXECUTION_COMPLETED";
    public static final String RESOURCE_GOVERNANCE_ACTION_EXECUTION_FAILED = "RESOURCE_GOVERNANCE_ACTION_EXECUTION_FAILED";
    public static final String RESOURCE_GOVERNANCE_ACTION_EXECUTION_CANCELLED = "RESOURCE_GOVERNANCE_ACTION_EXECUTION_CANCELLED";

    // K-A: Impact recorded event
    public static final String RESOURCE_GOVERNANCE_ACTION_IMPACT_RECORDED = "RESOURCE_GOVERNANCE_ACTION_IMPACT_RECORDED";

    // N-B: Alert handling governance events
    public static final String GOVERNANCE_ALERT_ACKED = "GOVERNANCE_ALERT_ACKED";
    public static final String GOVERNANCE_ALERT_RESOLVED = "GOVERNANCE_ALERT_RESOLVED";
    public static final String GOVERNANCE_ALERT_REOPENED = "GOVERNANCE_ALERT_REOPENED";

    // O-A: Alert assignment governance events
    public static final String GOVERNANCE_ALERT_ASSIGNED = "GOVERNANCE_ALERT_ASSIGNED";
    public static final String GOVERNANCE_ALERT_REASSIGNED = "GOVERNANCE_ALERT_REASSIGNED";
    public static final String GOVERNANCE_ALERT_UNASSIGNED = "GOVERNANCE_ALERT_UNASSIGNED";

    // O-B: Alert escalation governance events
    public static final String GOVERNANCE_ALERT_ESCALATED = "GOVERNANCE_ALERT_ESCALATED";

    // R-A: Alert notification followup governance events
    public static final String GOVERNANCE_ALERT_NOTIFICATION_FOLLOWUP_CREATED = "GOVERNANCE_ALERT_NOTIFICATION_FOLLOWUP_CREATED";
    public static final String GOVERNANCE_ALERT_NOTIFICATION_RECEIPT_CONFIRMED = "GOVERNANCE_ALERT_NOTIFICATION_RECEIPT_CONFIRMED";

    private GovernanceEventType() {
        // Prevent instantiation
    }
}
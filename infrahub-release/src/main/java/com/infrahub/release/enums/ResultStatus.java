package com.infrahub.release.enums;

/**
 * Result status for governance action impact recording.
 * Defined in V2/K Contract section 4.4.
 */
public enum ResultStatus {

    SUCCESS,
    FAILED,
    CANCELLED,
    WARNING,
    CRITICAL,
    INFO
}

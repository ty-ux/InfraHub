package com.infrahub.release.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.infrahub.common.context.UserContextHolder;
import com.infrahub.common.error.ErrorCode;
import com.infrahub.common.exception.BizException;
import com.infrahub.release.mapper.SysAuditLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Audit log service for monitor domain operations.
 * Reuses sys_audit_log table with MONITOR_* bizType constants.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MonitorAuditLogService {

    // BizType constants for monitor domain
    public static final String BIZ_TYPE_ALERT_RULE = "MONITOR_ALERT_RULE";
    public static final String BIZ_TYPE_HEALTH_CHECK = "MONITOR_HEALTH_CHECK";
    public static final String BIZ_TYPE_ALERT_EVENT = "MONITOR_ALERT_EVENT";
    public static final String BIZ_TYPE_COLLECTION = "MONITOR_COLLECTION";
    public static final String BIZ_TYPE_EVALUATION = "MONITOR_EVALUATION";

    // Action constants for alert rules
    public static final String ACTION_CREATE_RULE = "CREATE_RULE";
    public static final String ACTION_UPDATE_RULE = "UPDATE_RULE";
    public static final String ACTION_DELETE_RULE = "DELETE_RULE";
    public static final String ACTION_TOGGLE_RULE = "TOGGLE_RULE";

    // Action constants for health checks
    public static final String ACTION_CREATE_CHECK = "CREATE_CHECK";
    public static final String ACTION_UPDATE_CHECK = "UPDATE_CHECK";
    public static final String ACTION_DELETE_CHECK = "DELETE_CHECK";
    public static final String ACTION_TOGGLE_CHECK = "TOGGLE_CHECK";

    // Action constants for collection
    public static final String ACTION_MANUAL_COLLECT = "MANUAL_COLLECT";
    public static final String ACTION_RUN_ONCE_COLLECT = "RUN_ONCE_COLLECT";

    // Action constants for evaluation and alert events
    public static final String ACTION_EVALUATE_RULES = "EVALUATE_RULES";
    public static final String ACTION_MANUAL_RESOLVE = "MANUAL_RESOLVE";

    // Action constants for approval
    public static final String BIZ_TYPE_APPROVAL = "APPROVAL";
    public static final String ACTION_APPROVAL_CREATE = "APPROVAL_CREATE";
    public static final String ACTION_APPROVAL_APPROVE = "APPROVAL_APPROVE";
    public static final String ACTION_APPROVAL_REJECT = "APPROVAL_REJECT";
    public static final String ACTION_APPROVAL_CANCEL = "APPROVAL_CANCEL";

    // Result constants
    public static final String RESULT_SUCCESS = "SUCCESS";
    public static final String RESULT_FAILED = "FAILED";

    private final SysAuditLogMapper sysAuditLogMapper;
    private final ObjectMapper objectMapper;

    /**
     * Record alert rule audit log.
     */
    public void recordAlertRule(Long ruleId, String action, String result,
                                Map<String, ?> beforeData, Map<String, ?> afterData) {
        record(BIZ_TYPE_ALERT_RULE, ruleId, action, result, beforeData, afterData);
    }

    /**
     * Record health check audit log.
     */
    public void recordHealthCheck(Long checkId, String action, String result,
                                  Map<String, ?> beforeData, Map<String, ?> afterData) {
        record(BIZ_TYPE_HEALTH_CHECK, checkId, action, result, beforeData, afterData);
    }

    /**
     * Record alert event audit log (manual resolve).
     */
    public void recordAlertEvent(Long eventId, String action, String result,
                                 Map<String, ?> beforeData, Map<String, ?> afterData) {
        record(BIZ_TYPE_ALERT_EVENT, eventId, action, result, beforeData, afterData);
    }

    /**
     * Record single target collection audit log.
     */
    public void recordManualCollect(Long targetId, String result,
                                     Map<String, ?> afterData) {
        record(BIZ_TYPE_COLLECTION, targetId, ACTION_MANUAL_COLLECT, result, null, afterData);
    }

    /**
     * Record run-once batch collection audit log.
     */
    public void recordRunOnceCollect(String result, Map<String, ?> afterData) {
        // Use 0 as bizId for batch operations (no specific target)
        record(BIZ_TYPE_COLLECTION, 0L, ACTION_RUN_ONCE_COLLECT, result, null, afterData);
    }

    /**
     * Record rule evaluation audit log.
     */
    public void recordEvaluation(Long targetId, String result, Map<String, ?> afterData) {
        record(BIZ_TYPE_EVALUATION, targetId, ACTION_EVALUATE_RULES, result, null, afterData);
    }

    /**
     * Record approval audit log.
     */
    public void recordApproval(Long approvalId, String action, String result,
                            Map<String, ?> beforeData, Map<String, ?> afterData) {
        record(BIZ_TYPE_APPROVAL, approvalId, action, result, beforeData, afterData);
    }

    /**
     * Core record method.
     * Audit log failure should not affect main business operation.
     * We catch RuntimeException broadly to handle both BizException and
     * MyBatis/Spring data access exceptions (SQL/connection/type conversion).
     */
    private void record(String bizType, Long bizId, String action, String result,
                        Map<String, ?> beforeData, Map<String, ?> afterData) {
        try {
            sysAuditLogMapper.insertAuditLog(
                    UserContextHolder.getUserIdOrDefault(1L),
                    bizType,
                    bizId,
                    action,
                    beforeData != null ? toJson(beforeData) : null,
                    afterData != null ? toJson(afterData) : null,
                    result,
                    null
            );
        } catch (RuntimeException e) {
            // Audit log failure should not affect main business operation
            // Catch RuntimeException broadly: BizException, MyBatisException, DataAccessException, etc.
            log.error("Failed to record audit log: bizType={}, bizId={}, action={}, error={}",
                    bizType, bizId, action, e.getMessage());
        }
    }

    private String toJson(Map<String, ?> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "审计日志序列化失败");
        }
    }

    /**
     * Helper method to build audit data map from key-value pairs.
     */
    public static Map<String, Object> auditData(Object... entries) {
        Map<String, Object> value = new LinkedHashMap<>();
        for (int index = 0; index < entries.length; index += 2) {
            if (entries[index] != null && entries[index + 1] != null) {
                value.put((String) entries[index], entries[index + 1]);
            }
        }
        return value;
    }
}
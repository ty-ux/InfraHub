package com.infrahub.release.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.infrahub.release.handler.JsonbTypeHandler;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.Map;

@Data
@TableName(value = "approval_request", autoResultMap = true)
public class ApprovalRequest {

    private Long id;
    private String bizType;
    private Long bizId;
    private String action;
    private String status;
    private Long applicantId;
    private Long approverId;
    private String applyNote;
    private String approveNote;

    /**
     * Request payload as JSON object.
     * Stored as JSONB in database.
     */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private Map<String, Object> requestPayload;

    private OffsetDateTime createdAt;
    private OffsetDateTime approvedAt;

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    // Biz type constants
    public static final String BIZ_TYPE_MONITOR_ALERT_RULE = "MONITOR_ALERT_RULE";
    public static final String BIZ_TYPE_MONITOR_HEALTH_CHECK = "MONITOR_HEALTH_CHECK";
    public static final String BIZ_TYPE_RELEASE_ORDER = "RELEASE_ORDER";
    public static final String BIZ_TYPE_ROLLBACK_ORDER = "ROLLBACK_ORDER";

    // Action constants
    public static final String ACTION_TOGGLE_RULE = "TOGGLE_RULE";
    public static final String ACTION_TOGGLE_CHECK = "TOGGLE_CHECK";
    public static final String ACTION_EXECUTE_RELEASE = "EXECUTE_RELEASE";
    public static final String ACTION_EXECUTE_ROLLBACK = "EXECUTE_ROLLBACK";
}
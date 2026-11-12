package com.infrahub.release.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.infrahub.release.handler.JsonbTypeHandler;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.Map;

@Data
@TableName(value = "approval_notification_log", autoResultMap = true)
public class ApprovalNotificationLog {

    private Long id;
    private Long approvalRequestId;
    private String eventType;
    private String bizType;
    private Long bizId;
    private String status;
    private String targetUrl;
    private String errorMessage;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private Map<String, Object> payload;

    private OffsetDateTime createdAt;

    public static final String STATUS_SENT = "SENT";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_SKIPPED = "SKIPPED";
}

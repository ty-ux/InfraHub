package com.infrahub.release.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * E3.2: 多渠道通知发送记录
 */
@Data
@TableName("monitor_notification_record")
public class MonitorNotificationRecord {

    public static final String CHANNEL_WEBHOOK = "WEBHOOK";
    public static final String CHANNEL_DINGTALK = "DINGTALK";
    public static final String CHANNEL_FEISHU = "FEISHU";
    public static final String CHANNEL_EMAIL = "EMAIL";
    public static final String CHANNEL_SMS = "SMS";

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_SENT = "SENT";
    public static final String STATUS_FAILED = "FAILED";

    private Long id;
    private Long alertEventId;
    private Long alertRuleId;
    private String channelType;
    private String channelTarget;
    private String channelConfig;
    private String notificationStatus;
    private String requestPayload;
    private Integer responseStatus;
    private String responseBody;
    private String errorMessage;
    private OffsetDateTime sentAt;
    private OffsetDateTime createdAt;
}
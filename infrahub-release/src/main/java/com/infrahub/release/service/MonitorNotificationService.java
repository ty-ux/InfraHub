package com.infrahub.release.service;

import com.infrahub.release.entity.MonitorAlertEvent;
import com.infrahub.release.entity.MonitorAlertRule;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * Notification service for alert events.
 * Sends webhook notifications for FIRING and RESOLVED events.
 * E3.2: Uses multi-channel sender for backward-compatible notification.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MonitorNotificationService {

    private final MonitorWebhookSender webhookSender;
    private final MonitorMultiChannelSender multiChannelSender;

    /**
     * Send a notification for a new FIRING event.
     *
     * @param event        The alert event
     * @param rule         The alert rule that triggered this event
     * @param actualValue  The actual metric value that triggered the alert
     */
    public void notifyFiring(MonitorAlertEvent event, MonitorAlertRule rule, BigDecimal actualValue) {
        sendNotification(event, rule, actualValue, "FIRING");
    }

    /**
     * Send a notification for a RESOLVED event.
     *
     * @param event        The alert event
     * @param rule         The alert rule that triggered this event
     * @param actualValue  The actual metric value at recovery time
     */
    public void notifyResolved(MonitorAlertEvent event, MonitorAlertRule rule, BigDecimal actualValue) {
        sendNotification(event, rule, actualValue, "RESOLVED");
    }

    private void sendNotification(MonitorAlertEvent event, MonitorAlertRule rule, BigDecimal actualValue, String status) {
        String notifyChannel = rule.getNotifyChannel();
        if (notifyChannel == null || notifyChannel.isBlank()) {
            log.debug("No notifyChannel configured for rule {}, skipping notification", rule.getId());
            return;
        }

        Map<String, Object> payload = buildPayload(event, rule, actualValue, status);

        try {
            // E3.2: 使用多渠道发送器（向后兼容旧 webhook URL）
            multiChannelSender.send(event.getId(), rule.getId(), notifyChannel, payload);
        } catch (Exception e) {
            // Notification failure does not affect event processing
            log.error("Failed to send notification for event {}: {}", event.getId(), e.getMessage());
        }
    }

    private Map<String, Object> buildPayload(MonitorAlertEvent event, MonitorAlertRule rule,
                                               BigDecimal actualValue, String status) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("eventId", event.getId());
        payload.put("ruleId", rule.getId());
        payload.put("ruleName", rule.getRuleName());
        payload.put("targetType", event.getTargetType());
        payload.put("targetId", event.getTargetId());
        payload.put("status", status);
        payload.put("metricName", rule.getMetricName());
        payload.put("compareOp", rule.getCompareOp());
        payload.put("thresholdValue", rule.getThresholdValue());
        payload.put("actualValue", actualValue);
        payload.put("summary", event.getSummary());
        payload.put("occurredAt", formatDateTime(OffsetDateTime.now()));
        return payload;
    }

    private String formatDateTime(OffsetDateTime dateTime) {
        if (dateTime == null) return null;
        return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(dateTime);
    }
}

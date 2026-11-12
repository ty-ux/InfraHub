package com.infrahub.release.service;

import com.infrahub.release.entity.ApprovalNotificationLog;
import com.infrahub.release.entity.ApprovalRequest;
import com.infrahub.release.mapper.ApprovalNotificationLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Approval notification service.
 * Sends webhook notifications for approval events.
 * Notification failures do not affect the approval workflow.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApprovalNotificationService {

    private final MonitorWebhookSender webhookSender;
    private final ApprovalNotificationLogMapper notificationLogMapper;

    @Value("${infrahub.approval.notification.enabled:false}")
    private boolean enabled;

    @Value("${infrahub.approval.notification.webhook-url:}")
    private String webhookUrl;

    /**
     * Notify that an approval request was created.
     */
    public void notifyCreated(ApprovalRequest approval) {
        sendNotification("APPROVAL_CREATED", approval);
    }

    /**
     * Notify that an approval request was approved.
     */
    public void notifyApproved(ApprovalRequest approval) {
        sendNotification("APPROVAL_APPROVED", approval);
    }

    /**
     * Notify that an approval request was rejected.
     */
    public void notifyRejected(ApprovalRequest approval) {
        sendNotification("APPROVAL_REJECTED", approval);
    }

    /**
     * Notify that an approval request was cancelled.
     */
    public void notifyCancelled(ApprovalRequest approval) {
        sendNotification("APPROVAL_CANCELLED", approval);
    }

    /**
     * Send notification webhook.
     * Failures are logged but do not throw exceptions.
     */
    private void sendNotification(String eventType, ApprovalRequest approval) {
        if (!enabled) {
            log.debug("Approval notification is disabled, skipping {} event", eventType);
            saveNotificationLog(approval, eventType, ApprovalNotificationLog.STATUS_SKIPPED, null, null, null);
            return;
        }

        if (webhookUrl == null || webhookUrl.isBlank()) {
            log.debug("Approval notification webhook URL is empty, skipping {} event", eventType);
            saveNotificationLog(approval, eventType, ApprovalNotificationLog.STATUS_SKIPPED, null, "webhook URL not configured", null);
            return;
        }

        String lowerUrl = webhookUrl.toLowerCase().trim();
        if (!lowerUrl.startsWith("http://") && !lowerUrl.startsWith("https://")) {
            log.warn("Invalid approval notification webhook URL (must be http:// or https://): {}", webhookUrl);
            saveNotificationLog(approval, eventType, ApprovalNotificationLog.STATUS_SKIPPED, webhookUrl, "invalid webhook URL", null);
            return;
        }

        Map<String, Object> payload = buildPayload(eventType, approval);

        try {
            boolean sent = webhookSender.send(webhookUrl, payload);
            if (sent) {
                log.info("Approval notification sent: eventType={}, approvalId={}", eventType, approval.getId());
                saveNotificationLog(approval, eventType, ApprovalNotificationLog.STATUS_SENT, webhookUrl, null, payload);
            } else {
                log.warn("Approval notification failed to send: eventType={}, approvalId={}", eventType, approval.getId());
                saveNotificationLog(approval, eventType, ApprovalNotificationLog.STATUS_FAILED, webhookUrl, "send returned false", payload);
            }
        } catch (Exception e) {
            log.error("Approval notification error: eventType={}, approvalId={}, error={}",
                    eventType, approval.getId(), e.getMessage());
            saveNotificationLog(approval, eventType, ApprovalNotificationLog.STATUS_FAILED, webhookUrl, e.getMessage(), payload);
        }
    }

    /**
     * Save notification log record.
     * Failures in logging should not affect the approval workflow.
     */
    private void saveNotificationLog(ApprovalRequest approval, String eventType, String status,
                                     String targetUrl, String errorMessage, Map<String, Object> payload) {
        try {
            ApprovalNotificationLog logEntry = new ApprovalNotificationLog();
            logEntry.setApprovalRequestId(approval.getId());
            logEntry.setEventType(eventType);
            logEntry.setBizType(approval.getBizType());
            logEntry.setBizId(approval.getBizId());
            logEntry.setStatus(status);
            logEntry.setTargetUrl(targetUrl);
            logEntry.setErrorMessage(errorMessage);
            logEntry.setPayload(payload);
            logEntry.setCreatedAt(OffsetDateTime.now());
            notificationLogMapper.insert(logEntry);
        } catch (Exception e) {
            log.error("Failed to save approval notification log: eventType={}, approvalId={}, error={}",
                    eventType, approval.getId(), e.getMessage());
        }
    }

    /**
     * Build webhook payload.
     */
    private Map<String, Object> buildPayload(String eventType, ApprovalRequest approval) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("eventType", eventType);
        payload.put("approvalId", approval.getId());
        payload.put("bizType", approval.getBizType());
        payload.put("bizId", approval.getBizId());
        payload.put("action", approval.getAction());
        payload.put("status", approval.getStatus());
        payload.put("applicantId", approval.getApplicantId());
        payload.put("approverId", approval.getApproverId());
        payload.put("applyNote", approval.getApplyNote());
        payload.put("approveNote", approval.getApproveNote());
        payload.put("requestPayload", approval.getRequestPayload());
        payload.put("occurredAt", OffsetDateTime.now().toString());
        return payload;
    }
}

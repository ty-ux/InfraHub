package com.infrahub.release.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.infrahub.release.entity.MonitorNotificationRecord;
import com.infrahub.release.mapper.MonitorNotificationRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * E3.2: 多渠道通知发送服务
 * 支持向后兼容：纯 http/https URL 继续按 webhook 发送
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MonitorMultiChannelSender {

    private static final Pattern CHANNEL_PATTERN = Pattern.compile("^(webhook|dingtalk|feishu|email|sms):(.+)$", Pattern.CASE_INSENSITIVE);
    private static final String DINGTALK_WEBHOOK_PREFIX = "https://oapi.dingtalk.com/robot/send?access_token=";
    private static final String FEISHU_WEBHOOK_PREFIX = "https://open.feishu.cn/open-apis/bot/v2/hook/";

    private final MonitorWebhookSender webhookSender;
    private final MonitorNotificationRecordMapper recordMapper;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /**
     * 解析并发送通知到指定渠道
     * 向后兼容：纯 http/https URL 继续按 webhook 发送
     */
    public void send(Long eventId, Long ruleId, String notifyChannel, Map<String, Object> payload) {
        if (notifyChannel == null || notifyChannel.isBlank()) {
            log.debug("notifyChannel is empty, skipping notification");
            return;
        }

        String trimmed = notifyChannel.trim();
        Matcher matcher = CHANNEL_PATTERN.matcher(trimmed);

        if (matcher.matches()) {
            // 新格式：channel:target
            String channelType = matcher.group(1).toUpperCase();
            String channelTarget = matcher.group(2).trim();
            sendToChannel(eventId, ruleId, channelType, channelTarget, payload);
        } else if (trimmed.toLowerCase().startsWith("http://") || trimmed.toLowerCase().startsWith("https://")) {
            // 向后兼容：旧格式 URL 继续按 webhook 发送
            sendToChannel(eventId, ruleId, MonitorNotificationRecord.CHANNEL_WEBHOOK, trimmed, payload);
        } else {
            log.warn("Invalid notifyChannel format (must be channel:target or http(s):// URL): {}", notifyChannel);
            saveRecord(eventId, ruleId, "UNKNOWN", notifyChannel, null, null, null, "Invalid channel format", null);
        }
    }

    private void sendToChannel(Long eventId, Long ruleId, String channelType, String channelTarget, Map<String, Object> payload) {
        String requestPayload = serializePayload(payload);

        try {
            switch (channelType) {
                case "WEBHOOK" -> sendWebhook(eventId, ruleId, channelTarget, requestPayload, payload);
                case "DINGTALK" -> sendDingTalk(eventId, ruleId, channelTarget, requestPayload, payload);
                case "FEISHU" -> sendFeishu(eventId, ruleId, channelTarget, requestPayload, payload);
                case "EMAIL" -> sendEmail(eventId, ruleId, channelTarget, requestPayload, payload);
                case "SMS" -> sendSms(eventId, ruleId, channelTarget, requestPayload, payload);
                default -> {
                    log.warn("Unknown channel type: {}", channelType);
                    saveRecord(eventId, ruleId, channelType, channelTarget, requestPayload, null, null, "Unknown channel type", null);
                }
            }
        } catch (Exception e) {
            log.error("Failed to send {} notification to {}: {}", channelType, channelTarget, e.getMessage());
            saveRecord(eventId, ruleId, channelType, channelTarget, requestPayload, null, null, e.getMessage(), null);
        }
    }

    private void sendWebhook(Long eventId, Long ruleId, String url, String requestPayload, Map<String, Object> payload) {
        boolean success = webhookSender.send(url, payload);
        if (success) {
            saveRecord(eventId, ruleId, MonitorNotificationRecord.CHANNEL_WEBHOOK, url, requestPayload, 200, "OK", null, OffsetDateTime.now());
        } else {
            saveRecord(eventId, ruleId, MonitorNotificationRecord.CHANNEL_WEBHOOK, url, requestPayload, null, null, "Webhook send failed", null);
        }
    }

    private void sendDingTalk(Long eventId, Long ruleId, String accessTokenOrUrl, String requestPayload, Map<String, Object> payload) {
        String webhookUrl = accessTokenOrUrl.startsWith("http")
                ? accessTokenOrUrl
                : DINGTALK_WEBHOOK_PREFIX + accessTokenOrUrl;

        Map<String, Object> dingtalkPayload = new HashMap<>();
        Map<String, Object> text = new HashMap<>();
        text.put("content", buildTextContent(payload));
        dingtalkPayload.put("msgtype", "text");
        dingtalkPayload.put("text", text);

        sendHttpPost(eventId, ruleId, "DINGTALK", webhookUrl, dingtalkPayload, requestPayload);
    }

    private void sendFeishu(Long eventId, Long ruleId, String hookKeyOrUrl, String requestPayload, Map<String, Object> payload) {
        String webhookUrl = hookKeyOrUrl.startsWith("http")
                ? hookKeyOrUrl
                : FEISHU_WEBHOOK_PREFIX + hookKeyOrUrl;

        Map<String, Object> feishuPayload = new HashMap<>();
        Map<String, Object> content = new HashMap<>();
        content.put("text", buildTextContent(payload));
        feishuPayload.put("msg_type", "text");
        feishuPayload.put("content", content);

        sendHttpPost(eventId, ruleId, "FEISHU", webhookUrl, feishuPayload, requestPayload);
    }

    private void sendEmail(Long eventId, Long ruleId, String email, String requestPayload, Map<String, Object> payload) {
        // 模拟邮件发送（实际实现需要邮件服务）
        log.info("[SIMULATED EMAIL] To: {}, Subject: Alert Notification - {}", email, payload.get("status"));
        saveRecord(eventId, ruleId, MonitorNotificationRecord.CHANNEL_EMAIL, email, requestPayload, 200, "SIMULATED", null, OffsetDateTime.now());
    }

    private void sendSms(Long eventId, Long ruleId, String phone, String requestPayload, Map<String, Object> payload) {
        // 模拟短信发送（实际实现需要短信服务）
        log.info("[SIMULATED SMS] To: {}, Message: {}", phone, buildTextContent(payload));
        saveRecord(eventId, ruleId, MonitorNotificationRecord.CHANNEL_SMS, phone, requestPayload, 200, "SIMULATED", null, OffsetDateTime.now());
    }

    private void sendHttpPost(Long eventId, Long ruleId, String channelType, String url, Map<String, Object> payload, String originalPayload) {
        try {
            String jsonBody = objectMapper.writeValueAsString(payload);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                saveRecord(eventId, ruleId, channelType, url, originalPayload, response.statusCode(), response.body(), null, OffsetDateTime.now());
            } else {
                saveRecord(eventId, ruleId, channelType, url, originalPayload, response.statusCode(), response.body(), "HTTP error", null);
            }
        } catch (Exception e) {
            saveRecord(eventId, ruleId, channelType, url, originalPayload, null, null, e.getMessage(), null);
        }
    }

    private String buildTextContent(Map<String, Object> payload) {
        StringBuilder sb = new StringBuilder();
        sb.append("【告警通知】\n");
        sb.append("规则: ").append(payload.get("ruleName")).append("\n");
        sb.append("状态: ").append(payload.get("status")).append("\n");
        sb.append("指标: ").append(payload.get("metricName")).append("\n");
        sb.append("阈值: ").append(payload.get("thresholdValue")).append("\n");
        sb.append("实际值: ").append(payload.get("actualValue")).append("\n");
        sb.append("摘要: ").append(payload.get("summary")).append("\n");
        return sb.toString();
    }

    private String serializePayload(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private void saveRecord(Long eventId, Long ruleId, String channelType, String channelTarget,
                            String requestPayload, Integer responseStatus, String responseBody,
                            String errorMessage, OffsetDateTime sentAt) {
        MonitorNotificationRecord record = new MonitorNotificationRecord();
        record.setAlertEventId(eventId);
        record.setAlertRuleId(ruleId);
        record.setChannelType(channelType);
        record.setChannelTarget(channelTarget);
        record.setRequestPayload(requestPayload);
        record.setResponseStatus(responseStatus);
        record.setResponseBody(responseBody);
        record.setErrorMessage(errorMessage);
        record.setSentAt(sentAt);
        record.setNotificationStatus(sentAt != null ? MonitorNotificationRecord.STATUS_SENT : MonitorNotificationRecord.STATUS_FAILED);
        record.setCreatedAt(OffsetDateTime.now());

        recordMapper.insert(record);
    }
}

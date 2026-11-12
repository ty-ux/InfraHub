package com.infrahub.release.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Webhook sender for alert notifications.
 * Uses JDK HttpClient - no additional dependencies required.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MonitorWebhookSender {

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /**
     * Send a webhook notification to the specified URL.
     *
     * @param webhookUrl The webhook endpoint URL
     * @param payload    The notification payload as a map
     * @return true if sent successfully, false otherwise
     */
    public boolean send(String webhookUrl, Map<String, Object> payload) {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            log.debug("Webhook URL is empty, skipping notification");
            return false;
        }

        // Validate URL scheme
        String lowerUrl = webhookUrl.toLowerCase().trim();
        if (!lowerUrl.startsWith("http://") && !lowerUrl.startsWith("https://")) {
            log.warn("Invalid webhook URL scheme (must be http:// or https://): {}", webhookUrl);
            return false;
        }

        try {
            String jsonBody = objectMapper.writeValueAsString(payload);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(webhookUrl))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                log.info("Webhook notification sent successfully to {}, status={}", webhookUrl, response.statusCode());
                return true;
            } else {
                log.warn("Webhook notification failed, url={}, status={}, body={}",
                        webhookUrl, response.statusCode(), response.body());
                return false;
            }
        } catch (Exception e) {
            log.error("Webhook notification error, url={}, error={}", webhookUrl, e.getMessage());
            return false;
        }
    }
}

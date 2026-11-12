package com.infrahub.release.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Collects metrics from HTTP endpoints.
 * Supports JSON format (default) and Prometheus text format.
 * Only supports http:// and https:// URLs.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MonitorMetricCollector {

    private final ObjectMapper objectMapper;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final MonitorMetricPayloadParser prometheusParser = new MonitorMetricPayloadParser();

    /**
     * Collect metrics from the given URL with JSON format (default).
     *
     * @param url            the HTTP/HTTPS URL to collect from
     * @param timeoutSeconds timeout for the request
     * @return JSON string of the collected metrics
     * @throws CollectionException if collection fails
     */
    public String collect(String url, int timeoutSeconds) throws CollectionException {
        return collect(url, timeoutSeconds, "json");
    }

    /**
     * Collect metrics from the given URL with specified format.
     *
     * @param url            the HTTP/HTTPS URL to collect from
     * @param timeoutSeconds timeout for the request
     * @param format         the format of the response: "json" (default) or "prometheus"
     * @return JSON string of the collected metrics
     * @throws CollectionException if collection fails
     */
    public String collect(String url, int timeoutSeconds, String format) throws CollectionException {
        // Validate URL scheme
        if (url == null || url.isBlank()) {
            throw new CollectionException("采集 URL 不能为空");
        }

        String lowerUrl = url.toLowerCase().trim();
        if (!lowerUrl.startsWith("http://") && !lowerUrl.startsWith("https://")) {
            throw new CollectionException("URL 必须以 http:// 或 https:// 开头");
        }

        // Build request
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(timeoutSeconds > 0 ? timeoutSeconds : 5))
                    .header("Accept", "application/json")
                    .GET()
                    .build();
        } catch (IllegalArgumentException e) {
            throw new CollectionException("URL 格式非法: " + e.getMessage());
        }

        // Execute request
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            log.error("HTTP collection failed, url={}, error={}", url, e.getMessage());
            throw new CollectionException("HTTP 请求失败: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CollectionException("HTTP 请求被中断");
        }

        // Check status
        int statusCode = response.statusCode();
        if (statusCode < 200 || statusCode >= 300) {
            log.warn("HTTP collection returned non-2xx status, url={}, status={}", url, statusCode);
            throw new CollectionException("HTTP 返回状态码 " + statusCode);
        }

        // Validate response body
        String body = response.body();
        if (body == null || body.isBlank()) {
            throw new CollectionException("HTTP 响应体为空");
        }

        // Determine format (default to json)
        String effectiveFormat = (format == null || format.isBlank()) ? "json" : format.toLowerCase().trim();

        if ("prometheus".equals(effectiveFormat)) {
            // Parse Prometheus text format and convert to JSON
            try {
                String jsonPayload = prometheusParser.parse(body);
                log.info("Successfully collected Prometheus metrics from {}", url);
                return jsonPayload;
            } catch (MonitorMetricPayloadParser.ParseException e) {
                log.warn("Failed to parse Prometheus response, url={}, error={}", url, e.getMessage());
                throw new CollectionException("Prometheus 解析失败: " + e.getMessage());
            }
        } else {
            // Default: validate and return JSON response as-is
            try {
                Object parsed = objectMapper.readValue(body, Object.class);
                if (!(parsed instanceof java.util.Map)) {
                    throw new CollectionException("HTTP 响应必须是 JSON 对象");
                }
            } catch (IOException e) {
                log.warn("Failed to parse JSON response, url={}, error={}", url, e.getMessage());
                throw new CollectionException("HTTP 响应不是有效的 JSON");
            }

            log.info("Successfully collected JSON metrics from {}", url);
            return body;
        }
    }

    /**
     * Exception thrown when collection fails.
     */
    public static class CollectionException extends Exception {
        public CollectionException(String message) {
            super(message);
        }
    }
}

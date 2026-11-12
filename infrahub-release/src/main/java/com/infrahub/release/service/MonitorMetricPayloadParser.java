package com.infrahub.release.service;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser for Prometheus text exposition format.
 * Converts Prometheus metrics to JSON format for metric_payload storage.
 */
public class MonitorMetricPayloadParser {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    // Pattern for simple metric: metric_name value [timestamp]
    // e.g., "cpu_usage 85.5" or "cpu_usage 85.5 1714380000000"
    // Prometheus text exposition allows optional timestamp (int64/float, including negative/decimal/scientific)
    private static final Pattern SIMPLE_METRIC_PATTERN = Pattern.compile(
            "^([a-zA-Z_:][a-zA-Z0-9_:]*)\\s+([+-]?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?|NaN|[+-]?Inf)(?:\\s+[+-]?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?)?$"
    );

    // Pattern for labeled metric: metric_name{labels} value [timestamp]
    // e.g., "http_requests_total{method="GET",code="200"} 12"
    // or "http_requests_total{method="GET",code="200"} 12 1714380000000"
    private static final Pattern LABELED_METRIC_PATTERN = Pattern.compile(
            "^([a-zA-Z_:][a-zA-Z0-9_:]*)\\s*\\{([^}]*)\\}\\s+([+-]?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?|NaN|[+-]?Inf)(?:\\s+[+-]?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?)?$"
    );

    /**
     * Parse Prometheus text format and convert to JSON string.
     *
     * @param prometheusText the Prometheus text exposition format
     * @return JSON string representation of metrics
     * @throws ParseException if parsing fails
     */
    public String parse(String prometheusText) throws ParseException {
        if (prometheusText == null || prometheusText.isBlank()) {
            throw new ParseException("Prometheus 文本不能为空");
        }

        Map<String, Object> metrics = new HashMap<>();
        String[] lines = prometheusText.split("\\r?\\n");
        int validLines = 0;

        for (String line : lines) {
            String trimmedLine = line.trim();

            // Skip empty lines
            if (trimmedLine.isEmpty()) {
                continue;
            }

            // Skip comments (# HELP, # TYPE, etc.)
            if (trimmedLine.startsWith("#")) {
                continue;
            }

            // Try to parse as labeled metric first
            Matcher labeledMatcher = LABELED_METRIC_PATTERN.matcher(trimmedLine);
            if (labeledMatcher.matches()) {
                String metricName = labeledMatcher.group(1);
                String labels = labeledMatcher.group(2);
                String valueStr = labeledMatcher.group(3).trim();

                Object value = parseValue(valueStr);
                if (value == null) {
                    throw new ParseException("无效的指标值: " + trimmedLine);
                }

                // Flatten: metric_name{method="GET",code="200"} -> metric_name_method_GET_code_200
                String flattenedKey = flattenLabeledMetric(metricName, labels);
                metrics.put(flattenedKey, value);
                validLines++;
                continue;
            }

            // Try to parse as simple metric
            Matcher simpleMatcher = SIMPLE_METRIC_PATTERN.matcher(trimmedLine);
            if (simpleMatcher.matches()) {
                String metricName = simpleMatcher.group(1);
                String valueStr = simpleMatcher.group(2).trim();

                Object value = parseValue(valueStr);
                if (value == null) {
                    throw new ParseException("无效的指标值: " + trimmedLine);
                }

                metrics.put(metricName, value);
                validLines++;
                continue;
            }

            // Invalid line format - return failure
            throw new ParseException("无效的 Prometheus 格式: " + trimmedLine);
        }

        // Return failure if no valid metric lines found
        if (validLines == 0) {
            throw new ParseException("未找到有效的指标数据");
        }

        try {
            return OBJECT_MAPPER.writeValueAsString(metrics);
        } catch (Exception e) {
            throw new ParseException("JSON 序列化失败: " + e.getMessage());
        }
    }

    /**
     * Parse a value string into appropriate type (Double or Long).
     *
     * @param valueStr the value string
     * @return parsed value or null if invalid
     */
    private Object parseValue(String valueStr) {
        if (valueStr == null || valueStr.isEmpty()) {
            return null;
        }

        // Handle special Prometheus values
        if ("NaN".equalsIgnoreCase(valueStr) || "+Inf".equalsIgnoreCase(valueStr) || "-Inf".equalsIgnoreCase(valueStr)) {
            return valueStr;
        }

        try {
            // Try to parse as number
            if (valueStr.contains(".") || valueStr.contains("e") || valueStr.contains("E")) {
                return Double.parseDouble(valueStr);
            } else {
                return Long.parseLong(valueStr);
            }
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Flatten labeled metric into a single key.
     * e.g., metric_name{method="GET",code="200"} -> metric_name_method_GET_code_200
     *
     * @param metricName the metric name
     * @param labels the labels string (e.g., method="GET",code="200")
     * @return flattened key with sanitized label keys/values
     */
    private String flattenLabeledMetric(String metricName, String labels) {
        StringBuilder key = new StringBuilder(sanitizeKey(metricName));

        // Parse labels: key1="value1",key2="value2"
        String[] labelPairs = labels.split(",");
        for (String pair : labelPairs) {
            String trimmedPair = pair.trim();
            int eqIndex = trimmedPair.indexOf('=');
            if (eqIndex > 0 && eqIndex < trimmedPair.length() - 1) {
                String labelKey = trimmedPair.substring(0, eqIndex).trim();
                String labelValue = trimmedPair.substring(eqIndex + 1).trim();

                // Remove quotes from value
                if (labelValue.startsWith("\"") && labelValue.endsWith("\"")) {
                    labelValue = labelValue.substring(1, labelValue.length() - 1);
                }

                // Sanitize both key and value: only allow [A-Za-z0-9_]
                key.append('_').append(sanitizeKey(labelKey)).append('_').append(sanitizeKey(labelValue));
            }
        }

        return key.toString();
    }

    /**
     * Sanitize a key string to only contain [A-Za-z0-9_].
     * All other characters are replaced with underscore.
     *
     * @param key the original key
     * @return sanitized key
     */
    private String sanitizeKey(String key) {
        if (key == null || key.isEmpty()) {
            return key;
        }
        StringBuilder sb = new StringBuilder();
        for (char c : key.toCharArray()) {
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        return sb.toString();
    }

    /**
     * Exception thrown when parsing fails.
     */
    public static class ParseException extends Exception {
        public ParseException(String message) {
            super(message);
        }
    }
}

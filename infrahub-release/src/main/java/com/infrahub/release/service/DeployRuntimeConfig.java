package com.infrahub.release.service;

import org.springframework.util.StringUtils;

import java.util.Map;

public record DeployRuntimeConfig(
        String root,
        int port,
        String pidFile,
        String logFile,
        String healthUrl
) {

    private static final String DEFAULT_ROOT = "/opt/infrahub/infrahub-web";
    private static final int DEFAULT_PORT = 8080;

    public static DeployRuntimeConfig from(Map<String, String> config) {
        String root = value(config, "deploy.root", DEFAULT_ROOT);
        int port = intValue(config, "deploy.port", DEFAULT_PORT);
        String pidFile = value(config, "deploy.pidFile", root + "/run/app.pid");
        String logFile = value(config, "deploy.logFile", root + "/logs/app.log");
        String healthUrl = value(config, "deploy.healthUrl", "http://127.0.0.1:" + port + "/actuator/health");
        return new DeployRuntimeConfig(root, port, pidFile, logFile, healthUrl);
    }

    private static String value(Map<String, String> config, String key, String fallback) {
        String value = config == null ? null : config.get(key);
        return StringUtils.hasText(value) ? value : fallback;
    }

    private static int intValue(Map<String, String> config, String key, int fallback) {
        String value = config == null ? null : config.get(key);
        if (!StringUtils.hasText(value)) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}

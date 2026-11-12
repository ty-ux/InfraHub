package com.infrahub.release.service;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 部署失败分类器，前后端共享同一套分类逻辑。
 * <p>
 * 分类顺序按优先级排列：
 * 1. 前置检查失败
 * 2. Artifact 缺失
 * 3. 端口占用
 * 4. SSH/连接失败
 * 5. 启动失败
 * 6. 健康检查失败
 * 7. 未识别失败
 */
@Component
public class DeployFailureCategorizer {

    private static final Pattern PRECHECK_PATTERN = Pattern.compile("前置检查失败[^\\n]*|precheck failed[^\\n]*", Pattern.CASE_INSENSITIVE);
    private static final Pattern ARTIFACT_PATTERN = Pattern.compile("Artifact file not found: [^\\n]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern PORT_PATTERN = Pattern.compile("端口已被占用[^\\n]*|port already in use[^\\n]*|Address already in use[^\\n]*", Pattern.CASE_INSENSITIVE);
    private static final Pattern SSH_PATTERN = Pattern.compile("Connection refused[^\\n]*|Permission denied[^\\n]*", Pattern.CASE_INSENSITIVE);
    private static final Pattern START_PATTERN = Pattern.compile("启动失败[^\\n]*|start failed[^\\n]*|Failed to start[^\\n]*", Pattern.CASE_INSENSITIVE);
    private static final Pattern HEALTH_PATTERN = Pattern.compile("Health check failed[^\\n]*|健康检查失败[^\\n]*", Pattern.CASE_INSENSITIVE);

    public String categorize(String deployLog) {
        if (!StringUtils.hasText(deployLog)) {
            return null;
        }

        // 1. 前置检查失败
        if (deployLog.contains("前置检查失败") || deployLog.toLowerCase().contains("precheck failed")
                || deployLog.contains("__INFRAHUB_PRECHECK_SUCCESS__=false")) {
            Matcher m = PRECHECK_PATTERN.matcher(deployLog);
            return "【前置检查失败】" + (m.find() ? m.group() : "前置检查未通过");
        }

        // 2. Artifact 缺失
        if (deployLog.contains("Artifact file not found") || deployLog.toLowerCase().contains("artifact not found")) {
            Matcher m = ARTIFACT_PATTERN.matcher(deployLog);
            return "【制品缺失】" + (m.find() ? m.group() : "制品文件不存在");
        }

        // 3. 端口占用
        if (deployLog.contains("端口已被占用") || deployLog.toLowerCase().contains("port already in use")
                || deployLog.contains("Address already in use")) {
            Matcher m = PORT_PATTERN.matcher(deployLog);
            return "【端口占用】" + (m.find() ? m.group() : "目标端口已被占用");
        }

        // 4. SSH/连接失败
        if (deployLog.contains("Connection refused") || deployLog.contains("Permission denied")
                || (deployLog.contains("SSH") && deployLog.toLowerCase().contains("failed"))) {
            Matcher m = SSH_PATTERN.matcher(deployLog);
            return "【SSH连接失败】" + (m.find() ? m.group() : "SSH 连接失败");
        }

        // 5. 启动失败
        if (deployLog.contains("启动失败") || deployLog.toLowerCase().contains("start failed")
                || deployLog.contains("Failed to start")) {
            Matcher m = START_PATTERN.matcher(deployLog);
            return "【启动失败】" + (m.find() ? m.group() : "服务启动失败");
        }

        // 6. 健康检查失败
        if (deployLog.contains("Health check failed") || deployLog.contains("健康检查失败")
                || deployLog.toLowerCase().contains("health check")) {
            Matcher m = HEALTH_PATTERN.matcher(deployLog);
            return "【健康检查失败】" + (m.find() ? m.group() : "健康检查未通过");
        }

        // 7. 未识别失败
        return "【未识别失败】请查看完整日志定位问题";
    }
}

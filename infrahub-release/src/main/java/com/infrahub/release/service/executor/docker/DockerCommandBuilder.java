package com.infrahub.release.service.executor.docker;

import com.infrahub.release.dto.DockerExecutionConfig;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Docker 命令构建器。
 * 生成可预测、可测试的 docker run 和 docker rm 命令。
 */
@Component
public class DockerCommandBuilder {

    /**
     * 构建 docker run 命令。
     *
     * @param config Docker 执行配置
     * @return 命令参数列表（不包含 "docker" 前缀）
     */
    public List<String> buildRunCommand(DockerExecutionConfig config) {
        validateConfig(config);

        List<String> args = new ArrayList<>();
        args.add("run");
        args.add("-d");

        // 容器名称
        if (StringUtils.hasText(config.getContainerName())) {
            args.add("--name");
            args.add(config.getContainerName());
        }

        // 端口映射
        if (!CollectionUtils.isEmpty(config.getPorts())) {
            for (String port : config.getPorts()) {
                args.add("-p");
                args.add(port);
            }
        }

        // 环境变量
        if (!CollectionUtils.isEmpty(config.getEnv())) {
            for (Map.Entry<String, String> entry : config.getEnv().entrySet()) {
                args.add("-e");
                args.add(entry.getKey() + "=" + entry.getValue());
            }
        }

        // 网络
        if (StringUtils.hasText(config.getNetwork())) {
            args.add("--network");
            args.add(config.getNetwork());
        }

        // 重启策略
        if (StringUtils.hasText(config.getRestartPolicy())) {
            args.add("--restart");
            args.add(config.getRestartPolicy());
        }

        // 卷挂载
        if (!CollectionUtils.isEmpty(config.getVolumes())) {
            for (Map.Entry<String, String> entry : config.getVolumes().entrySet()) {
                args.add("-v");
                args.add(entry.getKey() + ":" + entry.getValue());
            }
        }

        // 健康检查
        if (StringUtils.hasText(config.getHealthCheckCmd())) {
            args.add("--health-cmd");
            args.add(config.getHealthCheckCmd());
        }
        if (config.getHealthIntervalSeconds() != null) {
            args.add("--health-interval");
            args.add(config.getHealthIntervalSeconds() + "s");
        }
        if (config.getHealthTimeoutSeconds() != null) {
            args.add("--health-timeout");
            args.add(config.getHealthTimeoutSeconds() + "s");
        }
        if (config.getHealthRetries() != null) {
            args.add("--health-retries");
            args.add(String.valueOf(config.getHealthRetries()));
        }

        // 额外参数
        if (!CollectionUtils.isEmpty(config.getExtraArgs())) {
            args.addAll(config.getExtraArgs());
        }

        // 镜像
        args.add(config.getImage());

        return args;
    }

    /**
     * 构建 docker rm 命令（强制删除）。
     *
     * @param containerName 容器名称
     * @return 命令参数列表
     */
    public List<String> buildRemoveCommand(String containerName) {
        if (!StringUtils.hasText(containerName)) {
            throw new IllegalArgumentException("容器名称不能为空");
        }
        return List.of("rm", "-f", containerName);
    }

    /**
     * 构建 docker stop 命令。
     *
     * @param containerName 容器名称
     * @param timeoutSeconds 超时秒数
     * @return 命令参数列表
     */
    public List<String> buildStopCommand(String containerName, Integer timeoutSeconds) {
        if (!StringUtils.hasText(containerName)) {
            throw new IllegalArgumentException("容器名称不能为空");
        }
        List<String> args = new ArrayList<>();
        args.add("stop");
        if (timeoutSeconds != null && timeoutSeconds > 0) {
            args.add("-t");
            args.add(String.valueOf(timeoutSeconds));
        }
        args.add(containerName);
        return args;
    }

    /**
     * 构建 docker logs 命令。
     *
     * @param containerName 容器名称
     * @param tail 行数，null 表示全部
     * @param follow 是否跟踪
     * @return 命令参数列表
     */
    public List<String> buildLogsCommand(String containerName, Integer tail, Boolean follow) {
        if (!StringUtils.hasText(containerName)) {
            throw new IllegalArgumentException("容器名称不能为空");
        }
        List<String> args = new ArrayList<>();
        args.add("logs");
        if (tail != null && tail > 0) {
            args.add("--tail");
            args.add(String.valueOf(tail));
        }
        if (Boolean.TRUE.equals(follow)) {
            args.add("-f");
        }
        args.add(containerName);
        return args;
    }

    /**
     * 构建 docker ps 命令（查询容器状态）。
     *
     * @param containerName 容器名称
     * @return 命令参数列表
     */
    public List<String> buildPsCommand(String containerName) {
        if (!StringUtils.hasText(containerName)) {
            throw new IllegalArgumentException("容器名称不能为空");
        }
        return List.of("ps", "-a", "--filter", "name=" + containerName, "--format", "{{.Status}}");
    }

    /**
     * 构建 docker inspect 命令（获取容器健康状态）。
     *
     * @param containerName 容器名称
     * @return 命令参数列表
     */
    public List<String> buildInspectHealthCommand(String containerName) {
        if (!StringUtils.hasText(containerName)) {
            throw new IllegalArgumentException("容器名称不能为空");
        }
        return List.of("inspect", "--format", "{{.State.Health.Status}}", containerName);
    }

    /**
     * 构建完整命令字符串（用于日志展示）。
     *
     * @param args 命令参数列表
     * @return 完整命令字符串
     */
    public String buildCommandString(List<String> args) {
        if (CollectionUtils.isEmpty(args)) {
            return "";
        }
        return "docker " + String.join(" ", args.stream()
                .map(this::quoteIfNecessary)
                .toList());
    }

    private String quoteIfNecessary(String arg) {
        if (arg == null) {
            return "";
        }
        if (arg.contains(" ") || arg.contains("\"") || arg.contains("'")) {
            return "\"" + arg.replace("\"", "\\\"") + "\"";
        }
        return arg;
    }

    private void validateConfig(DockerExecutionConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("Docker 配置不能为空");
        }
        if (!StringUtils.hasText(config.getImage())) {
            throw new IllegalArgumentException("镜像地址不能为空");
        }
    }
}

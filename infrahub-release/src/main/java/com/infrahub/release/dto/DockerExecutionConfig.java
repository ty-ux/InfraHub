package com.infrahub.release.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Docker 执行器配置 DTO。
 * 用于解析 executorConfig JSON payload。
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DockerExecutionConfig {

    /**
     * Docker 镜像地址，如 nginx:latest 或 registry.example.com/app:v1.0
     */
    private String image;

    /**
     * 容器名称
     */
    private String containerName;

    /**
     * 端口映射列表，格式 "8080:80"
     */
    private List<String> ports;

    /**
     * 环境变量，格式 "KEY=VALUE"
     */
    private Map<String, String> env;

    /**
     * 网络名称
     */
    private String network;

    /**
     * 重启策略：no, always, on-failure, unless-stopped
     */
    private String restartPolicy;

    /**
     * Docker 主机地址，如 tcp://192.0.2.20:2375，为空则使用本地
     */
    private String dockerHost;

    /**
     * 工作目录挂载
     */
    private Map<String, String> volumes;

    /**
     * 额外参数
     */
    private List<String> extraArgs;

    /**
     * 健康检查命令
     */
    private String healthCheckCmd;

    /**
     * 健康检查间隔秒数
     */
    private Integer healthIntervalSeconds;

    /**
     * 健康检查超时秒数
     */
    private Integer healthTimeoutSeconds;

    /**
     * 健康检查重试次数
     */
    private Integer healthRetries;

    /**
     * 启动超时秒数
     */
    private Integer startTimeoutSeconds;
}

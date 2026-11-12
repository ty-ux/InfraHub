package com.infrahub.release.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 主机与实例状态采集配置。
 *
 * <p>与既有 HTTP 采集器（{@code infrahub.monitor.collection.fixed-delay-ms}）解耦：
 * 本功能有独立的 period，不复用也不改变既有默认值。
 *
 * <p>非法配置**启动即失败**（fail-closed），不静默回退到默认值 —— 否则一个写错的阈值
 * 会让"过期"判定永远不成立，界面长期显示新鲜状态。
 *
 * @see docs/release/v2-host-instance-status-collection-contract.md 第 2.4 节
 */
@Data
@Component
@ConfigurationProperties(prefix = "infrahub.monitor.collection.host-instance")
public class HostInstanceCollectionProperties {

    /** 本功能独立开关；与 infrahub.monitor.collection.enabled 同时为 true 才会调度 */
    private boolean enabled = false;

    /** 采集周期（毫秒） */
    private long periodMs = 30_000L;

    /** 单目标超时（毫秒），必须小于采集周期 */
    private long singleTargetTimeoutMs = 5_000L;

    /** 并发上限 */
    private int maxConcurrency = 4;

    /** 连续失败阈值，达到后目标状态置为"无法确认" */
    private int failureThreshold = 3;

    /** 数据过期阈值（毫秒），必须不小于采集周期 */
    private long staleThresholdMs = 90_000L;

    @PostConstruct
    void validate() {
        require(periodMs >= 1000L,
                "infrahub.monitor.collection.host-instance.period-ms 必须 >= 1000，当前=" + periodMs);
        require(singleTargetTimeoutMs >= 1L,
                "infrahub.monitor.collection.host-instance.single-target-timeout-ms 必须 >= 1，当前=" + singleTargetTimeoutMs);
        require(singleTargetTimeoutMs < periodMs,
                "infrahub.monitor.collection.host-instance.single-target-timeout-ms 必须小于 period-ms，当前="
                        + singleTargetTimeoutMs + " >= " + periodMs);
        require(maxConcurrency >= 1 && maxConcurrency <= 32,
                "infrahub.monitor.collection.host-instance.max-concurrency 必须在 1..32，当前=" + maxConcurrency);
        require(failureThreshold >= 1,
                "infrahub.monitor.collection.host-instance.failure-threshold 必须 >= 1，当前=" + failureThreshold);
        require(staleThresholdMs >= periodMs,
                "infrahub.monitor.collection.host-instance.stale-threshold-ms 必须不小于 period-ms，当前="
                        + staleThresholdMs + " < " + periodMs);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException("采集配置不合法：" + message);
        }
    }
}
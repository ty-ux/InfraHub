package com.infrahub.release.support;

/**
 * 主机/实例状态采集的配置开关名。
 *
 * <p>抽成常量是为了让调度器的 {@code @ConditionalOnProperty} 与读模型汇报的"采集是否启用"
 * 引用同一份字面量，避免两处各写一遍后悄悄漂移。
 *
 * @see docs/release/v2-host-instance-status-collection-contract.md 第 2.4 / 8 节
 */
public final class CollectionSwitches {

    /** 平台采集总开关（与既有 HTTP 采集共用） */
    public static final String GLOBAL_ENABLED = "infrahub.monitor.collection.enabled";

    /** 主机与实例采集的独立开关 */
    public static final String HOST_INSTANCE_ENABLED = "infrahub.monitor.collection.host-instance.enabled";

    private CollectionSwitches() {
    }
}
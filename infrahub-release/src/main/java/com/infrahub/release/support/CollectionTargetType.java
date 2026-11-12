package com.infrahub.release.support;

/**
 * 采集目标的类型标识 —— 单一真源。
 *
 * <p>取值必须与 {@code monitor_snapshot.target_type} 的既有在线取值逐字一致：
 * 在线库中 monitor_snapshot 只存在 {@code HOST}，读方代码使用 {@code SERVICE_INSTANCE}
 * （见 {@code RuntimeInstanceResourceViewService}）。
 *
 * <p>注意：{@code db/flyway/examples/V5__seed_sys_enum_monitor_types.example.sql} 中的
 * {@code INSTANCE} 是**从未执行**的示例取值，不是在线取值，禁止在任何新代码中使用。
 *
 * @see docs/release/v2-host-instance-status-collection-contract.md 第 1 节
 */
public final class CollectionTargetType {

    /** 目标为 asset_host 行，targetId = asset_host.id */
    public static final String HOST = "HOST";

    /** 目标为 runtime_service_instance 行，targetId = runtime_service_instance.id */
    public static final String SERVICE_INSTANCE = "SERVICE_INSTANCE";

    private CollectionTargetType() {
    }

    public static boolean isSupported(String targetType) {
        return HOST.equals(targetType) || SERVICE_INSTANCE.equals(targetType);
    }
}
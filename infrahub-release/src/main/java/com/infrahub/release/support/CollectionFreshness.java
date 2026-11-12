package com.infrahub.release.support;

import java.time.Duration;
import java.time.OffsetDateTime;

/**
 * 采集状态的新鲜度 —— 读取时按 last_success_at 与过期阈值计算，不依赖 updated_at。
 *
 * @see docs/release/v2-host-instance-status-collection-contract.md 第 4.5 节
 */
public enum CollectionFreshness {

    /** 从未成功观测 */
    NONE,

    /** 成功观测仍在阈值内 */
    FRESH,

    /** 成功观测已超出阈值 */
    STALE;

    /**
     * 计算新鲜度。
     *
     * @param lastSuccessAt     最近一次成功观测时刻，可为 null
     * @param now               当前时刻
     * @param staleThresholdMs  过期阈值（毫秒）
     */
    public static CollectionFreshness of(OffsetDateTime lastSuccessAt, OffsetDateTime now, long staleThresholdMs) {
        if (lastSuccessAt == null) {
            return NONE;
        }
        long ageMs = Duration.between(lastSuccessAt, now).toMillis();
        return ageMs <= staleThresholdMs ? FRESH : STALE;
    }
}
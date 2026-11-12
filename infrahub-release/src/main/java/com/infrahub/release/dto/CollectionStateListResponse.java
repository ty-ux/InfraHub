package com.infrahub.release.dto;

import java.util.List;

/**
 * 采集状态批量读取的响应体。
 *
 * <p>除了逐目标的状态，还带上"采集是否启用"和"过期阈值"：界面据此区分
 * "自动采集在跑、这是它记录的状态" 与 "自动采集根本没开，所以这些数字不会自己更新"，
 * 而不是让用户对着一张永远不变的表猜。
 *
 * @param collectionEnabled 自动采集当前是否启用（两个开关同时为真才为 true）
 * @param staleThresholdMs  当前生效的过期阈值（毫秒），超过它未成功观测即算过期
 * @param states            逐目标的采集状态，顺序与请求的 targetIds 去重后一致
 * @see docs/release/v2-host-instance-status-collection-contract.md 第 6.3 节
 */
public record CollectionStateListResponse(
        boolean collectionEnabled,
        long staleThresholdMs,
        List<CollectionStateView> states
) {
}
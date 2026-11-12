package com.infrahub.release.dto;

/**
 * 一次采集调度的结果汇总，用于日志与自测断言。
 *
 * @param total        本轮枚举到的目标总数
 * @param applied      成功观测并写入目标状态的个数
 * @param recordedOnly 只写入采集状态、未把目标状态写"新鲜"的个数（跳过 / 失败未达阈值 / 达阈值已复位）
 * @param discarded    被拒收的迟到样本个数（目标已删除 / 配置已变 / observed_at 不更新）
 * @param expired      本轮被判为过期并复位状态的个数
 * @param writeFailed  单个目标写回时抛异常、事务已回滚的个数（其余目标不受影响，见 R6）
 */
public record CollectionRunSummary(
        int total,
        int applied,
        int recordedOnly,
        int discarded,
        int expired,
        int writeFailed
) {

    public static CollectionRunSummary empty() {
        return new CollectionRunSummary(0, 0, 0, 0, 0, 0);
    }
}
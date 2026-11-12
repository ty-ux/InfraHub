package com.infrahub.release.dto;

import java.time.OffsetDateTime;

/**
 * 单个目标的采集状态读模型，供列表页展示"状态来源 / 最近成功观察 / 新鲜度 / 失败原因"。
 *
 * <p>这是**只读**视图，不触发任何探测：前端轮询它读的是数据库里已记录的状态，
 * 真正的探测只由后端调度器发起（契约 §6.2）。
 *
 * @param targetType            目标类型：HOST / SERVICE_INSTANCE
 * @param targetId              目标主键
 * @param observerType          产出该状态的观测器；NONE 表示目标类型不支持
 * @param source                状态来源：SSH_PROBE（平台主动探测）/ AGENT_REPORT（真实 Agent 上报）/ NONE
 * @param configPresent         采样时是否有可用连接配置
 * @param result                最近一次采集结果：SUCCESS / FAILED / SKIPPED_UNCONFIGURED / SKIPPED_UNSUPPORTED / EXPIRED / NONE
 * @param freshness             新鲜度：FRESH（新鲜）/ STALE（过期）/ NONE（从未成功观测）
 * @param lastAttemptAt         最近一次尝试采集的时刻
 * @param lastSuccessAt         最近一次成功观测的时刻；失败不会清除它
 * @param observedAt            最近一次被接受的样本时刻
 * @param consecutiveFailureCount 连续失败次数
 * @param errorCategory         最近一次失败的类别（仅失败时有值）
 * @param errorSummary          已脱敏截断的失败摘要
 * @param observedStatus        最近一次成功观测到的运行状态
 * @param observedHealth        最近一次成功观测到的健康状态
 * @param discardedSampleCount  被拒收的迟到样本累计数
 */
public record CollectionStateView(
        String targetType,
        Long targetId,
        String observerType,
        String source,
        boolean configPresent,
        String result,
        String freshness,
        OffsetDateTime lastAttemptAt,
        OffsetDateTime lastSuccessAt,
        OffsetDateTime observedAt,
        Integer consecutiveFailureCount,
        String errorCategory,
        String errorSummary,
        String observedStatus,
        String observedHealth,
        Integer discardedSampleCount
) {
}
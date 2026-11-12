package com.infrahub.release.dto;

import com.infrahub.release.support.CollectionErrorCategory;
import com.infrahub.release.support.CollectionResult;

import java.time.OffsetDateTime;

/**
 * 一次只读探测的观测结果。**观测器不写任何库**，只返回本对象，写回由编排层统一负责。
 *
 * @param result              结果：SUCCESS / FAILED / SKIPPED_UNCONFIGURED / SKIPPED_UNSUPPORTED
 * @param errorCategory       原因分类。失败观测：失败类别；成功观测：判定原因（PROCESS_ABSENT /
 *                            HEALTH_ENDPOINT_ERROR）；跳过观测：跳过原因（CONFIG_MISSING /
 *                            DIAGNOSTIC_CONFIG_MISSING / UNSUPPORTED_TARGET）；无原因时为 null
 * @param errorSummary        已脱敏、已截断的摘要，不含凭据；跳过观测保留可定位的**未配置原因**
 * @param observedStatus      成功观测的运行判定：主机 ONLINE/OFFLINE，实例 RUNNING/STOPPED
 * @param observedHealth      成功观测的健康判定：UP / DOWN / UNKNOWN
 * @param observedAt          采样开始时刻，写回条件更新的守卫键
 * @param observerType        产出本结果的观测器标识
 * @param configPresent       采样时目标是否有**可用的启用 SSH 连接配置**。它只回答这一个问题，
 *                            与部署诊断参数是否配齐无关：SSH 配置在、诊断参数不足时它仍是 true
 */
public record CollectionObservation(
        CollectionResult result,
        CollectionErrorCategory errorCategory,
        String errorSummary,
        String observedStatus,
        String observedHealth,
        OffsetDateTime observedAt,
        String observerType,
        boolean configPresent
) {

    /** 探测成功但进程确实不在：仍是成功观测，结论为 STOPPED。 */
    public static CollectionObservation processAbsent(String observerType, OffsetDateTime observedAt) {
        return new CollectionObservation(CollectionResult.SUCCESS, CollectionErrorCategory.PROCESS_ABSENT,
                null, "STOPPED", "UNKNOWN", observedAt, observerType, true);
    }

    public static CollectionObservation failure(CollectionErrorCategory category, String summary,
                                               String observerType, OffsetDateTime observedAt) {
        return new CollectionObservation(CollectionResult.FAILED, category, summary,
                null, null, observedAt, observerType, true);
    }

    /**
     * 未配置（跳过）：目标没有可用的启用 SSH 连接配置。
     *
     * <p>调用方已确认"连接配置就是不存在"时用这个重载，不留原因文案（原因已由分类自明）。
     */
    public static CollectionObservation unconfigured(String observerType, OffsetDateTime observedAt) {
        return skipped(CollectionErrorCategory.CONFIG_MISSING, false, null, observerType, observedAt);
    }

    /**
     * 未配置（跳过），并保留一条可定位、但不泄密的未配置原因。
     *
     * <p>{@code configPresent=false}：确实没有可用的启用 SSH 连接配置。
     */
    public static CollectionObservation unconfigured(String observerType, OffsetDateTime observedAt, String summary) {
        return skipped(CollectionErrorCategory.CONFIG_MISSING, false, summary, observerType, observedAt);
    }

    /**
     * 未配置（跳过）：**SSH 连接配置就位**，只是部署诊断参数不足（缺 {@code deploy.root} / {@code deploy.port} 等）。
     *
     * <p>{@code configPresent} 保持 {@code true}：它回答的是"采样时有没有可用 SSH 连接配置"，
     * 这里确实有。不能因为另一个东西没配，就把"SSH 确实存在"说成"没有连接配置"。
     *
     * <p>和 {@link #unconfigured} 一样，这是**跳过而不是失败**：不推进连续失败计数，
     * 也不借失败阈值改写目标状态。{@code summary} 保留可定位的原因（诊断服务给出的缺失项说明）。
     */
    public static CollectionObservation diagnosticsUnconfigured(String observerType, OffsetDateTime observedAt,
                                                               String summary) {
        return skipped(CollectionErrorCategory.DIAGNOSTIC_CONFIG_MISSING, true, summary, observerType, observedAt);
    }

    /**
     * 未配置（跳过）：部署诊断参数**存在歧义**（同一个运行期键有多条有效记录）。
     *
     * <p>{@code configPresent} 保持 {@code true}：SSH 连接配置就在那儿，问题是部署参数无法确定。
     * 与"参数不足"一样是**跳过而不是失败**：运行期已按拒绝执行处理，采集侧不得把它累计成
     * 连续失败、也不得据此改写目标状态。
     */
    public static CollectionObservation diagnosticsAmbiguous(String observerType, OffsetDateTime observedAt,
                                                             String summary) {
        return skipped(CollectionErrorCategory.DIAGNOSTIC_CONFIG_AMBIGUOUS, true, summary, observerType, observedAt);
    }

    /** 跳过观测的唯一构造点：把"两类未配置"收在一处，避免它们各自漂移出不同的 result/configPresent 组合。 */
    private static CollectionObservation skipped(CollectionErrorCategory category, boolean configPresent,
                                                 String summary, String observerType, OffsetDateTime observedAt) {
        return new CollectionObservation(CollectionResult.SKIPPED_UNCONFIGURED, category, summary,
                null, null, observedAt, observerType, configPresent);
    }

    /**
     * 准入已变更（跳过）：采样之后、拨号之前，目标的可拨号身份被改动，本轮取消。
     *
     * <p>{@code configPresent=true}：采样时确实取到了可用的启用连接配置，本轮之所以没拨号，
     * 是因为采样之后它被改动了，而不是因为"从来没配好"。
     *
     * <p>这是**跳过而不是失败**：不推进连续失败计数、不改写目标状态、不刷新成功观测时间，
     * 来源也不记 {@code SSH_PROBE} —— 本轮根本没有发出探测。
     */
    public static CollectionObservation admissionChanged(String observerType, OffsetDateTime observedAt,
                                                        String summary) {
        return new CollectionObservation(CollectionResult.SKIPPED_ADMISSION_CHANGED,
                CollectionErrorCategory.ADMISSION_CHANGED, summary, null, null, observedAt, observerType, true);
    }

    public static CollectionObservation unsupported(String observerType, OffsetDateTime observedAt) {
        return new CollectionObservation(CollectionResult.SKIPPED_UNSUPPORTED, CollectionErrorCategory.UNSUPPORTED_TARGET,
                null, null, null, observedAt, observerType, false);
    }
}
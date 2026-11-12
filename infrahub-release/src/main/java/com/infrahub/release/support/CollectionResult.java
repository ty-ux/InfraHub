package com.infrahub.release.support;

/**
 * 一次采集尝试的结果。
 *
 * <p>{@link #SKIPPED_UNCONFIGURED} 与 {@link #SKIPPED_UNSUPPORTED} **不是失败**：
 * 它们不参与连续失败计数，也不改写目标状态。把"没配 SSH"当成失败会让主机被误判为故障。
 *
 * <p>"未配置"包含两类，都属于 {@link #SKIPPED_UNCONFIGURED}，只是原因分类不同：
 * 主机没有可用的启用 SSH 连接配置（{@code CONFIG_MISSING}），
 * 或 SSH 配置就位但部署诊断参数不足（{@code DIAGNOSTIC_CONFIG_MISSING}）。
 * 两种情况都**没有发出真实探测**，因此来源记 {@code NONE}，不谎称来自 SSH 探测。
 *
 * <p>{@link #SKIPPED_ADMISSION_CHANGED} 同样**不是失败**：准入后目标的身份或连接配置被改动，
 * 本轮拨号在发出前被取消。它不代表目标故障、也不代表探测失败，因此不计入连续失败、不改写目标状态、
 * 不刷新成功观测时间。
 *
 * @see docs/release/v2-host-instance-status-collection-contract.md 第 4.1 / 4.3 节
 */
public enum CollectionResult {

    /** 探测成功并得到有效观测（进程有/无、健康 UP/DOWN 都算成功观测） */
    SUCCESS,

    /** 探测失败（认证、超时、不可达、未知错误） */
    FAILED,

    /** 未配置：没有可用的启用 SSH 连接配置，或 SSH 配置就位但部署诊断参数不足 */
    SKIPPED_UNCONFIGURED,

    /** 不支持：目标类型没有可用观测器 */
    SKIPPED_UNSUPPORTED,

    /** 准入已变更：快照采下后目标身份/地址/端口/连接配置被改动，本轮在拨号前取消 */
    SKIPPED_ADMISSION_CHANGED,

    /** 证据过期：成功观测超出过期阈值 */
    EXPIRED,

    /** 尚无任何记录 */
    NONE;

    public boolean isFailure() {
        return this == FAILED;
    }

    public boolean isSkip() {
        return this == SKIPPED_UNCONFIGURED || this == SKIPPED_UNSUPPORTED
                || this == SKIPPED_ADMISSION_CHANGED;
    }
}
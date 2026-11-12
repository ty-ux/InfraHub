package com.infrahub.release.support;

/**
 * 一次外部部署动作（SSH / Docker / Kubernetes / 预检）可以被证明到什么程度。
 *
 * <p>存在的理由：执行结果长期只有 {@code success} 布尔值，而 SSH 超时、连接中断、kubectl 超时
 * 都返回 {@code false}。把 {@code false} 一律当成“远端动作确定失败”，会让操作者在一个可能仍在
 * 运行的发布上做出回滚、重试或“已安全结束”的判断。这里把“确定成功 / 确定失败 / 部分成功 /
 * 未开始 / 结果未知”分开，任何无法证明的状态都落到 {@link #UNKNOWN}，绝不默认成确定。
 *
 * <p>判定只使用结构化证据（进程退出码、是否拿到退出状态、执行器自己的超时标记、预检是否通过），
 * 不解析 stdout/stderr 自由文本来推断结果。
 */
public enum DeployExecutionOutcome {

    /** 远端动作确定没有开始：预检失败、输入校验失败、命令尚未发出。 */
    NOT_STARTED("NOT_STARTED"),
    /** 所有计划实例都拿到明确的成功证据（退出码 0）。 */
    SUCCEEDED("SUCCEEDED"),
    /** 至少执行过一个实例，且每个已执行实例都有明确的非零退出码，没有任何实例成功。 */
    FAILED("FAILED"),
    /** 部分实例成功、部分实例确定失败；整批既不能算成功，也不能算“什么都没变”。 */
    PARTIAL("PARTIAL"),
    /** 至少一个实例无法确认结果：超时、连接中断、未拿到退出状态、终态写入失败。 */
    UNKNOWN("UNKNOWN");

    private final String code;

    DeployExecutionOutcome(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    /** 固定前缀，用于在不新增数据库列的前提下把结果类别持久化到失败原因里。 */
    public String marker() {
        return "[" + code + "]";
    }

    /**
     * 任务批次是否“可以确定整批都成功”。只有 {@link #SUCCEEDED} 才算。
     */
    public boolean isSuccess() {
        return this == SUCCEEDED;
    }

    /**
     * 是否属于“结果未知、必须人工核对”：未知与部分成功都不能被当作已安全结束。
     */
    public boolean requiresManualVerification() {
        return this == UNKNOWN || this == PARTIAL;
    }

    /**
     * 是否满足自动回滚对“原动作结果确定”的最低要求。
     *
     * <p>只有全部已执行实例都确定失败、且没有任何实例成功时才成立。部分成功意味着系统处于
     * 混合状态，未知意味着可能仍在运行。
     */
    public boolean allowsAutomaticRollbackDecision() {
        return this == FAILED;
    }

    /**
     * 按实例级统计聚合整批结果。优先级：未知 &gt; 部分成功 &gt; 全部成功 &gt; 未开始 &gt; 失败。
     *
     * @param attempted  本次认领的实例总数（含未开始的实例）
     * @param succeeded  确定成功的实例数
     * @param failed     确定失败的实例数
     * @param unknown    结果未知的实例数
     * @param notStarted 确定未开始的实例数
     */
    public static DeployExecutionOutcome aggregate(int attempted, int succeeded, int failed,
                                                   int unknown, int notStarted) {
        if (attempted <= 0 || succeeded < 0 || failed < 0 || unknown < 0 || notStarted < 0
                || (long) succeeded + failed + unknown + notStarted != attempted) {
            return UNKNOWN;
        }
        if (unknown > 0) {
            return UNKNOWN;
        }
        if (succeeded > 0 && succeeded == attempted) {
            return SUCCEEDED;
        }
        if (succeeded > 0) {
            return PARTIAL;
        }
        if (attempted > 0 && notStarted == attempted) {
            return NOT_STARTED;
        }
        return FAILED;
    }

    /**
     * 从失败原因开头的固定标记读取结果类别。只识别本枚举自己的标记，不做自由文本推断；
     * 没有标记或标记不认识时返回 {@code null}（调用方按“无信息”处理，不猜成任何确定状态）。
     */
    public static DeployExecutionOutcome fromMarker(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.stripLeading();
        for (DeployExecutionOutcome outcome : values()) {
            if (trimmed.startsWith(outcome.marker())) {
                return outcome;
            }
        }
        return null;
    }
}

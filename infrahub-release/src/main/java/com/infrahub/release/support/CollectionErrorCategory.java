package com.infrahub.release.support;

/**
 * 采集失败类别 —— 必须可在展示层区分，且不得互相冒充。
 *
 * <p>分类时机只影响**展示的原因文案**；判定变更时机统一由连续失败阈值与过期阈值控制，
 * 不因为"确定不可达"而提前把状态改写成 OFFLINE/STOPPED。
 *
 * <p>注意 {@link #PROCESS_ABSENT} 与 {@link #HEALTH_ENDPOINT_ERROR} 不是失败类别：
 * 它们是成功观测得到的结论，仅在此处作为"判定原因"保留可读文案。失败路径不会使用它们。
 *
 * <p>同理 {@link #CONFIG_MISSING}、{@link #DIAGNOSTIC_CONFIG_MISSING} 与 {@link #UNSUPPORTED_TARGET}
 * 也不是失败类别：它们描述的是**跳过**这次采集的原因。{@link #isFailureCategory()} 是对真失败的显式白名单，
 * 这三个值都不在其中 —— 判定"是否推进连续失败计数"必须以该方法为准，不能只看"有没有 errorCategory"。
 *
 * <p>{@link #ADMISSION_CHANGED} 也**不是**失败类别：准入后目标身份或连接配置被改动，
 * 本轮在拨号前被取消，既没有发出探测、也不能说明目标不可达。
 *
 * @see docs/release/v2-host-instance-status-collection-contract.md 第 4.2 节
 */
public enum CollectionErrorCategory {

    /** 无启用 SSH 配置（未配置，不是失败） */
    CONFIG_MISSING,

    /**
     * 启用的 SSH 连接配置**在**，但部署诊断参数不足（未配置，不是失败）。
     *
     * <p>与 {@link #CONFIG_MISSING} 的区别只在"缺的是哪一类配置"：前者是主机上根本没有可用 SSH 配置，
     * 后者是 SSH 配置就位、但诊断所需的运行时参数（{@code deploy.root} / {@code deploy.port} 等）没有配齐。
     * 两者**都是"未配置"**：既不算探测失败、不推进连续失败计数，也不借失败阈值去改写目标状态。
     *
     * <p>单列一类而不是并入 {@link #CONFIG_MISSING}，是因为二者的处置建议完全不同，
     * 而页面能区分它们的唯一依据就是这个分类 —— 否则只能把"SSH 确实存在"说成"缺少连接配置"。
     */
    DIAGNOSTIC_CONFIG_MISSING,

    /**
     * 部署诊断参数**存在歧义**：同一应用/环境下，运行期要读的同一个部署键有多条有效记录
     * （未配置，不是失败）。
     *
     * <p>与 {@link #DIAGNOSTIC_CONFIG_MISSING} 的区别：那里是"一条都没有配"，这里是"配了多条、
     * 无法确定用哪一条"。运行期对歧义直接拒绝执行，**不猜优先级**；采集侧因此也把它归为跳过，
     * 既不推进连续失败计数，也不借失败阈值改写目标状态 —— 配置问题不能累计成运行故障。
     */
    DIAGNOSTIC_CONFIG_AMBIGUOUS,

    /** 实例类型无观测器（不支持，不是失败） */
    UNSUPPORTED_TARGET,

    /**
     * 准入已变更（跳过，不是失败）。
     *
     * <p>采样后、拨号前，目标的可拨号身份被改动：主机改址、SSH 端口被改、启用的连接配置被替换/删除/停用、
     * 实例改绑主机或改服务/环境、目标本身被删除。此时**不能**拿着旧样本继续拨号，
     * 也**不能**把它当成"目标故障" —— 它既不是进程停止、也不是主机离线、更不是探测失败。
     *
     * <p>与 {@link #CONFIG_MISSING} 的区别：那类是"从来没配好"，本类是"配好了但采样之后被改动"，
     * 处置建议完全不同（前者去补配置，后者等下一轮重新采样）。
     */
    ADMISSION_CHANGED,

    /** SSH 认证失败 */
    AUTH_FAILED,

    /** 连接或读取超时（瞬时） */
    NETWORK_TIMEOUT,

    /** 连接被拒 / 无路由 / 主机不可达 */
    TARGET_UNREACHABLE,

    /** 其它未归类错误 */
    PROBE_ERROR,

    /**
     * 探测任务未能被调度（采集线程池已被上一轮未结束的探测占满）。
     *
     * <p>这是**采集侧**的失败，不是目标侧的失败，但结论一样：本轮没有拿到关于该目标的任何证据，
     * 因此不能继续宣称它"新鲜"。单列一类是为了让"探测根本没跑"和"探测跑了但失败"在界面上可区分。
     */
    PROBE_SCHEDULING_REJECTED,

    /** 探测成功但进程确实不在（成功观测，结论 STOPPED） */
    PROCESS_ABSENT,

    /** 探测成功、进程在跑但健康接口明确报错（成功观测，结论 RUNNING + DOWN） */
    HEALTH_ENDPOINT_ERROR;

    /** 是否为真正的失败类别（失败时才允许推进连续失败计数） */
    public boolean isFailureCategory() {
        return this == AUTH_FAILED || this == NETWORK_TIMEOUT
                || this == TARGET_UNREACHABLE || this == PROBE_ERROR
                || this == PROBE_SCHEDULING_REJECTED;
    }
}
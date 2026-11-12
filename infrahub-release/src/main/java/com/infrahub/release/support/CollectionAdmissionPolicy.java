package com.infrahub.release.support;

import com.infrahub.release.dto.CollectionTargetRef;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 受限采集的**允许集策略** —— 调用方显式交给受限入口的、单轮内静态不可变的授权清单。
 *
 * <p><b>为什么必须由产品侧接受一份策略</b>：编排层原来的 {@code collectOnce()} 先做全局过期结算、
 * 再枚举**全部**目标，没有任何位置可以传入"本轮只允许碰哪些目标"。外侧白名单挡不住这件事：
 * 入口只要仍然全量枚举并全量探测，外侧再筛也只是"先执行后检查"。因此限制必须发生在链路**内部**，
 * 且由调用方以**值对象**的形式传入。
 *
 * <p><b>允许的是"身份"，不是主键</b>：一条授权记录就是一个完整的
 * {@link CollectionTargetRef}（目标类型/id、主机归属、实例类型、地址、生效 SSH 端口、
 * 实例的服务与环境、SSH 配置 id 与其版本时刻），即**上一轮准入快照**里的那十项。
 * 只有十个维度**全部**一致才算命中：同一个 id 换了地址、换了端口、换了绑定主机、
 * 换了服务/环境，或所依据的 SSH 配置换了 id / 被改过版本，都会被判为**不在允许集内**。
 * "总数还是 3"或"id 还在清单里"都**不是**授权 —— 等量换 id、同 id 改址同样被拒。
 *
 * <p><b>不含任何凭据</b>：本类与 {@link CollectionTargetRef} 都不持有密码、私钥或密钥引用；
 * {@link #toString()} 只输出条目数与目标键，日志与断言可用 {@link #describe(CollectionTargetRef)}
 * 取"非机密目标键"。凭据值不出现在策略、日志、{@code toString} 或任何 HTTP DTO 里。
 *
 * <p><b>不可变与进程内静态</b>：构造时对入参做防御性拷贝（{@link List#copyOf}，元素本身是不可变
 * record），因此调用方在本轮进行中改动它自己那份集合**不会**改变已经在跑的这一轮；
 * 本轮内也没有任何"热更新/撤销"入口 —— 静态允许集是**进程内一次调用**的边界，
 * 不是一套带撤销的权限系统（撤销窗口见契约文档）。
 *
 * <p><b>多轮执行</b>：多轮必须**每轮显式传入同一份已批准策略**。本类不会、也不能
 * "根据本轮枚举结果自动放宽"自己的范围。
 *
 * @see docs/release/v2-host-instance-status-collection-contract.md 受限采集入口一节
 */
public final class CollectionAdmissionPolicy {

    /** 判定结论的分类原因（非机密、可定位，便于按维度统计）。 */
    public enum Reason {
        /** 命中允许集 */
        ADMITTED,
        /** 允许集里没有这个 (目标类型, 目标 id) —— 额外新增目标、等量替换的 id 都归此类 */
        TARGET_NOT_ADMITTED,
        /** 授权的是别的主机归属 */
        HOST_BINDING_CHANGED,
        /** 实例类型变了（决定用哪条探测路径与哪套判定） */
        INSTANCE_TYPE_CHANGED,
        /** 实例被改绑到别的服务（决定用哪一份诊断参数） */
        SERVICE_CHANGED,
        /** 实例被改绑到别的环境（同上） */
        ENV_CHANGED,
        /** 主机地址变了 */
        ADDRESS_CHANGED,
        /** 实际拨号依据的 SSH 配置换了另一条记录 */
        SSH_CONFIG_CHANGED,
        /** 同一条 SSH 配置被改动过（版本时刻不同） */
        SSH_CONFIG_VERSION_CHANGED,
        /** 生效 SSH 端口变了 */
        SSH_PORT_CHANGED,
        /**
         * 写回事务边界上锁定的当前身份已不再见证被授权的采样身份。
         *
         * <p>只在**写回边界**出现：采样之后、写回事务之前目标被改动（改址/改绑/改配置）时，
         * 旧样本既不能被接受，也不能以"它原来在允许集里"为由动任何一行数据。
         */
        CURRENT_IDENTITY_CHANGED
    }

    /**
     * 单个目标的判定结论。
     *
     * @param allowed 是否在允许集内
     * @param reason  {@link Reason} 分类
     * @param detail  人类可读的差异说明（非机密，可在日志与拒绝汇总里出现）
     */
    public record Decision(boolean allowed, Reason reason, String detail) {
    }

    private final List<CollectionTargetRef> admitted;

    private CollectionAdmissionPolicy(List<CollectionTargetRef> admitted) {
        // 防御性拷贝：本轮用的是这一份快照，调用方之后改动自己那份集合不影响在飞的一轮
        this.admitted = List.copyOf(admitted);
    }

    /**
     * 用一份显式允许集构造策略。
     *
     * @param refs 被授权的目标身份；可为**空集**（空集合法，但受限入口必须零动作）
     * @throws NullPointerException 入参或其任一元素为 null —— null 不等于"全量"，必须显式给出
     */
    public static CollectionAdmissionPolicy of(List<CollectionTargetRef> refs) {
        Objects.requireNonNull(refs,
                "允许集不可为 null；受限入口不接受空策略，null 不等于全量");
        return new CollectionAdmissionPolicy(refs);
    }

    /** 空允许集：合法，但受限入口据此必须零探测、零写回、零过期结算。 */
    public static CollectionAdmissionPolicy empty() {
        return new CollectionAdmissionPolicy(List.of());
    }

    public boolean isEmpty() {
        return admitted.isEmpty();
    }

    public int size() {
        return admitted.size();
    }

    /** 被授权身份的只读视图（本类构造时已防御性拷贝，返回的列表不可变）。 */
    public List<CollectionTargetRef> admittedRefs() {
        return admitted;
    }

    /**
     * 判定一个目标身份是否被本策略授权。
     *
     * <p>先按 (目标类型, 目标 id) 定位，再对**每一个**维度逐项比对：
     * 主机归属 → 实例类型 → 服务 → 环境 → 地址 → SSH 配置 id → SSH 配置版本 → 生效端口。
     * 任意一项不一致即拒绝，并给出该维度的分类原因。
     * 同键若在允许集里出现多条，以第一条为准。
     *
     * <p>版本时刻用 {@code toInstant()} 比对，而不是 {@code OffsetDateTime.equals}：
     * 后者还要求时区偏移一致，而 timestamptz 读回来的偏移随会话时区变化，
     * 直接 equals 会把"根本没改"误判成"改过"（与写回侧 {@code configStillMatches} 同口径）。
     */
    public Decision decide(CollectionTargetRef actual) {
        Objects.requireNonNull(actual, "待判定身份不可为 null");
        CollectionTargetRef baseline = sameKeyBaseline(actual);
        if (baseline == null) {
            return new Decision(false, Reason.TARGET_NOT_ADMITTED,
                    "允许集只授权 " + keys() + "，本次出现 " + describe(actual));
        }
        if (!Objects.equals(baseline.hostId(), actual.hostId())) {
            return changed(Reason.HOST_BINDING_CHANGED, "host_id", baseline.hostId(), actual.hostId());
        }
        if (!Objects.equals(baseline.instanceType(), actual.instanceType())) {
            return changed(Reason.INSTANCE_TYPE_CHANGED, "instance_type",
                    baseline.instanceType(), actual.instanceType());
        }
        if (!Objects.equals(baseline.serviceId(), actual.serviceId())) {
            return changed(Reason.SERVICE_CHANGED, "service_id", baseline.serviceId(), actual.serviceId());
        }
        if (!Objects.equals(baseline.envId(), actual.envId())) {
            return changed(Reason.ENV_CHANGED, "env_id", baseline.envId(), actual.envId());
        }
        if (!Objects.equals(baseline.hostAddress(), actual.hostAddress())) {
            return changed(Reason.ADDRESS_CHANGED, "host_address", baseline.hostAddress(), actual.hostAddress());
        }
        if (!Objects.equals(baseline.sshConfigId(), actual.sshConfigId())) {
            return changed(Reason.SSH_CONFIG_CHANGED, "ssh_config_id",
                    baseline.sshConfigId(), actual.sshConfigId());
        }
        if (!sameInstant(baseline.sshConfigUpdatedAt(), actual.sshConfigUpdatedAt())) {
            return changed(Reason.SSH_CONFIG_VERSION_CHANGED, "ssh_config_updated_at",
                    baseline.sshConfigUpdatedAt(), actual.sshConfigUpdatedAt());
        }
        if (!Objects.equals(baseline.sshPort(), actual.sshPort())) {
            return changed(Reason.SSH_PORT_CHANGED, "ssh_port", baseline.sshPort(), actual.sshPort());
        }
        return new Decision(true, Reason.ADMITTED, "命中允许集 " + describe(actual));
    }

    /**
     * 非机密的"目标键"，供日志、拒绝汇总与断言使用：只有类型与 id。
     * 地址、端口等身份字段不在这里拼出来，凭据更不会出现。
     */
    public static String describe(CollectionTargetRef ref) {
        return ref.targetType() + "/" + ref.targetId();
    }

    private CollectionTargetRef sameKeyBaseline(CollectionTargetRef actual) {
        for (CollectionTargetRef candidate : admitted) {
            if (Objects.equals(candidate.targetType(), actual.targetType())
                    && Objects.equals(candidate.targetId(), actual.targetId())) {
                return candidate;
            }
        }
        return null;
    }

    private static Decision changed(Reason reason, String dimension, Object expected, Object actual) {
        return new Decision(false, reason,
                dimension + " 期望 " + expected + " 实为 " + actual);
    }

    private static boolean sameInstant(OffsetDateTime left, OffsetDateTime right) {
        if (left == null || right == null) {
            return left == right;
        }
        return left.toInstant().equals(right.toInstant());
    }

    private String keys() {
        StringBuilder builder = new StringBuilder();
        for (CollectionTargetRef ref : admitted) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(describe(ref));
        }
        return builder.length() == 0 ? "（空集：不授权任何目标）" : builder.toString();
    }

    /** 只输出条目数：身份明细不出现在 toString 里，避免被日志/断言意外扩散。 */
    @Override
    public String toString() {
        return "CollectionAdmissionPolicy[size=" + admitted.size() + "]";
    }
}

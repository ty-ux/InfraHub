package com.infrahub.release.service;

import com.infrahub.release.config.HostInstanceCollectionProperties;
import com.infrahub.release.dto.CollectionObservation;
import com.infrahub.release.dto.CollectionTargetRef;
import com.infrahub.release.entity.AssetHost;
import com.infrahub.release.entity.AssetHostSshConfig;
import com.infrahub.release.entity.MonitorCollectionState;
import com.infrahub.release.entity.RuntimeServiceInstance;
import com.infrahub.release.mapper.AssetHostMapper;
import com.infrahub.release.mapper.AssetHostSshConfigMapper;
import com.infrahub.release.mapper.MonitorCollectionStateMapper;
import com.infrahub.release.mapper.RuntimeServiceInstanceMapper;
import com.infrahub.release.support.CollectionAddress;
import com.infrahub.release.support.CollectionAdmissionPolicy;
import com.infrahub.release.support.CollectionResult;
import com.infrahub.release.support.CollectionSource;
import com.infrahub.release.support.CollectionTargetType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 采集写回的一致性边界。
 *
 * <p><b>为什么必须是独立 Bean</b>：{@code @Transactional} 依赖 Spring 代理，
 * 同类内部自调用不会经过代理、事务不会生效。编排层（{@code HostInstanceCollectionService}）
 * 与写回逻辑分处两个 Bean，代理才真正介入。
 *
 * <p><b>每个目标一个短事务</b>，事务内顺序固定为：
 * <ol>
 *   <li>锁目标行（{@code SELECT ... FOR UPDATE}）并比对身份与地址；</li>
 *   <li>锁采集状态行；</li>
 *   <li>锁 SSH 配置行并比对配置版本；</li>
 *   <li>接受样本（{@code upsertObservation}，由 {@code observed_at} 守卫）；</li>
 *   <li>更新目标状态字段（成功写入 / 达阈值复位）。</li>
 * </ol>
 * 网络等待完全在事务之外 —— 探测早已在调用本类之前完成。
 *
 * <p><b>为什么提交前不能松手</b>：旧实现把"写采集表"和"写目标表"拆成两次独立提交。
 * 于是两个交错样本 A(observed_at=T1) 与 B(T2&gt;T1) 可以：A 的 upsert 成功（当时它确实最新），
 * B 的 upsert 也成功，B 把目标写成 STOPPED，随后 A 才把目标写成 RUNNING ——
 * 最终采集表说 STOPPED、目标表说 RUNNING，两个"事实"互相打架。
 * 现在两个写在同一事务、同一把行锁下完成，最后一次提交者的 {@code observed_at} 必然最新。
 *
 * <p><b>进程内互斥不足以证明正确</b>：{@code AtomicBoolean} 只能约束单个 JVM。
 * 多实例部署下并发由 PostgreSQL 的行锁裁决，因此这里的正确性证据必须来自真实数据库
 * （见 {@code CollectionWriteBackPostgresTest} 的并发交错用例），而不是单进程的单元测试。
 *
 * <p><b>锁顺序全局统一：目标行 → 采集状态行 → SSH 配置行。</b>
 * 过期结算路径同样遵守（先锁目标行，再锁采集状态行并读当前证据，最后标记与复位），
 * 否则两条路径会以相反顺序取锁而死锁。
 *
 * @see docs/release/v2-host-instance-status-collection-contract.md 第 5.4 / 5.5 / 6 节
 */
@Service
@RequiredArgsConstructor
public class CollectionWriteBackService {

    /** 采集把状态退回"无法确认"时写入的值 */
    public static final String STATUS_UNKNOWN = "UNKNOWN";

    /** 证据过期的标记值，与 {@link CollectionResult#EXPIRED} 同名（契约里落库的就是这个字符串） */
    private static final String RESULT_EXPIRED = CollectionResult.EXPIRED.name();

    private static final String HEALTH_UNKNOWN = "UNKNOWN";

    private final AssetHostMapper assetHostMapper;
    private final RuntimeServiceInstanceMapper runtimeServiceInstanceMapper;
    private final MonitorCollectionStateMapper monitorCollectionStateMapper;
    private final AssetHostSshConfigMapper assetHostSshConfigMapper;
    private final HostInstanceCollectionProperties properties;

    /**
     * 写回一个目标的探测结果，**整体一个短事务**。
     *
     * <p>任何一步抛出异常，事务回滚：采集状态与目标字段一起回到写回前的样子，
     * 不会出现"采集表说失败达阈值、目标表还没复位"这类半成品。
     */
    @Transactional
    public WriteOutcome writeBack(CollectionTargetRef ref, CollectionObservation observation) {
        // 1) 目标行：既提供身份比对，也让同一目标的写回跨 JVM 串行化
        if (!lockAndMatchTarget(ref)) {
            countDiscarded(ref, observation.observedAt());
            return WriteOutcome.DISCARDED;
        }
        // 2) 采集状态行
        monitorCollectionStateMapper.selectByTargetForUpdate(ref.targetType(), ref.targetId());
        // 3) SSH 配置行：比对"探测所依据的配置版本"，而不只是 id
        if (!configStillMatches(ref)) {
            countDiscarded(ref, observation.observedAt());
            return WriteOutcome.DISCARDED;
        }
        // 4)+5) 接受样本并写目标字段
        return acceptObservation(ref, observation);
    }

    /**
     * 受限写回：在**同一个事务、同一处动作边界**上先做受限策略复核，再走既有的身份/配置守卫。
     *
     * <p><b>为什么必须在事务边界再核一次</b>：编排层的准入是**枚举那一刻**的判定，从采样到写回之间
     * 目标可能已被改址、改绑主机/服务/环境、或换了 SSH 配置版本。若只在编排层筛一次，旧样本就会
     * 落到"已经不是被授权身份"的那一行上。因此这里的复核发生在任何 UPDATE/INSERT **之前**，
     * 而且是在目标行行锁之内 —— 与 {@link #writeBack} 的锁顺序完全一致（目标行 → 采集状态行 → 配置行）。
     *
     * <p><b>锁定比对保留的是原始 ref</b>：{@code lockAndMatchTarget} / {@code configStillMatches} 始终拿
     * **采样时那一份** {@code ref} 去和锁内读到的当前行比对。这里绝不会"重建一个当前 ref"
     * 再拿旧观测去写 —— 那等于把旧样本改绑到新身份上，正是既有守卫要防止的事。
     *
     * <p><b>拒绝即零写入</b>：策略拒绝、锁定的当前身份不再见证采样身份、或配置不再匹配时，
     * 本方法直接返回 {@link WriteOutcome#OUT_OF_SCOPE} 并且**不碰任何一行数据** ——
     * 包括不调用 {@code incrementDiscarded}。既有的 {@code DISCARDED} 路径会更新丢弃计数，
     * 若在"目标已越界"时先加一次丢弃计数再声称"没有写状态"，那就是一次真实的越界写入。
     * 因此计数只可能发生在**已经通过全部身份/策略复核**、只有 {@code observed_at} 不够新的时候
     * （即目标确在允许集内、样本只是迟到）—— 这才是"允许集内迟到样本保留既有语义"。
     *
     * <p><b>目标行已被删除</b>同样归入 {@link WriteOutcome#OUT_OF_SCOPE}：允许集授权的那个身份
     * 在库里已经不存在，本轮就不该再留下任何痕迹（既没有目标行可改，也不去动采集状态行）。
     *
     * @param policy 本轮允许集；不接受 null（null 不等于全量，调用方必须显式给出）
     */
    @Transactional
    public WriteOutcome writeBackScoped(CollectionTargetRef ref, CollectionObservation observation,
                                        CollectionAdmissionPolicy policy) {
        Objects.requireNonNull(policy,
                "受限写回必须显式传入允许集策略；null 不等于全量");
        // 闸门 3-a：原始 ref 本身就不在允许集内 —— 连锁都不必取，更不写任何一行
        if (!policy.decide(ref).allowed()) {
            return WriteOutcome.OUT_OF_SCOPE;
        }
        // 闸门 3-b：锁目标行，并核对"锁定的当前身份是否仍见证被授权的采样身份"
        if (!lockAndMatchTarget(ref)) {
            return WriteOutcome.OUT_OF_SCOPE;
        }
        // 闸门 3-c：采集状态行（与既有写回同序）
        monitorCollectionStateMapper.selectByTargetForUpdate(ref.targetType(), ref.targetId());
        // 闸门 3-d：探测所依据的 SSH 配置（id + 版本时刻 + 端口）是否仍是当前启用配置
        if (!configStillMatches(ref)) {
            return WriteOutcome.OUT_OF_SCOPE;
        }
        return acceptObservation(ref, observation);
    }

    /**
     * 写回的公共尾部：目标行与配置行都已核对通过之后的"接受这一份样本"。
     *
     * <p>此时目标身份已确认仍在范围内，剩下的只是样本时序问题：{@code observed_at} 不比库中现值新
     * 就拒收（绝不让旧样本盖住新样本）并留下丢弃计数。调用方必须先完成各自的身份/策略核对
     * —— 本方法不做任何身份判定。
     */
    private WriteOutcome acceptObservation(CollectionTargetRef ref, CollectionObservation observation) {
        // 接受样本；observed_at 不比现值新则拒收，绝不让旧样本盖住新样本
        if (monitorCollectionStateMapper.upsertObservation(buildState(ref, observation)) == 0) {
            countDiscarded(ref, observation.observedAt());
            return WriteOutcome.DISCARDED;
        }
        // 目标字段
        return applyTargetStatus(ref, observation);
    }

    /**
     * 结算**单个**目标的过期证据：标记 EXPIRED 与复位目标状态在同一事务里完成。
     *
     * <p>这正是旧实现"复位失败即永久跳过"的解药：旧实现先用一条批量 UPDATE 把所有过期目标标成 EXPIRED，
     * 再逐个复位；一旦某个目标复位失败（目标表被锁、连接抖动、约束冲突），它已经不在
     * {@code last_result != 'EXPIRED'} 的候选集合里了 —— 下一轮再也不会被选中，于是**永远**停在
     * "采集表 EXPIRED、目标状态还是旧的 ONLINE"这个自相矛盾的状态上。
     * 现在标记与复位同事务：复位失败则标记一并回滚，该目标下一轮仍是候选，可重试。
     *
     * <p><b>为什么必须在锁内重读当前证据</b>：候选集合是事务外一次只读查询选出来的，它携带的
     * {@code lastObservedStatus/Health} 是**那一刻**的快照。若在"选候选"与"结算"之间，另一个执行者
     * 提交了一条更晚但仍相对 {@code staleBefore} 已过期的成功观测（例如 ONLINE@00:00 → OFFLINE@00:01），
     * 那么：
     * <ol>
     *   <li>{@code markExpiredOne} 的条件（{@code last_success_at < staleBefore} 且未标记）仍然成立，
     *       于是当前记录被标成 EXPIRED；</li>
     *   <li>复位却拿候选携带的旧值 ONLINE 去做 CAS，而目标行此刻是 OFFLINE，命中 0 行；</li>
     *   <li>事务照常提交：采集表 EXPIRED、目标状态 OFFLINE 未复位，且因 {@code last_result = 'EXPIRED'}
     *       被排除出候选，**再也不会被复位**。</li>
     * </ol>
     * 因此这里在目标行锁之后、标记之前，先 {@code SELECT ... FOR UPDATE} 锁定并读取采集状态行，
     * 只用**当前**证据判断是否该标记、并用**当前**观测值做条件复位。候选快照只用来定位目标，
     * 不参与任何判断 —— 方法签名里也不再接受调用方传来的 expected 值，从形状上杜绝这种错位。
     *
     * <p>先锁目标行再锁/改采集状态行，与 {@link #writeBack} 的锁顺序一致（目标行 → 采集状态行 → SSH 配置行）。
     * 本方法全程不碰网络，探测早在事务之外完成。
     *
     * @return 是否真的复位了目标状态（false = 当前证据已不满足过期条件、已被别的执行者处理，或目标无可复位值）
     */
    @Transactional
    public boolean settleExpiredTarget(String targetType, Long targetId, OffsetDateTime staleBefore) {
        lockTargetRow(targetType, targetId);
        MonitorCollectionState current = monitorCollectionStateMapper.selectByTargetForUpdate(targetType, targetId);
        if (current == null) {
            // 目标刚被删除，采集状态行也不在了：无可结算
            return false;
        }
        if (!isStillExpired(current, staleBefore)) {
            // 期间已有更新的成功观测（可能已经不新鲜→仍然过期，也可能变新鲜了），或已被别的执行者标记
            return false;
        }
        if (monitorCollectionStateMapper.markExpiredOne(targetType, targetId, staleBefore) == 0) {
            // 同上，由 SQL 再兜一层：条件不再成立就不标记，留待下一轮重新评估
            return false;
        }
        return resetToUnknown(targetType, targetId,
                current.getLastObservedStatus(), current.getLastObservedHealth());
    }

    /**
     * 当前采集状态是否仍是"证据已过期且尚未标记"。
     *
     * <p>要求的条件是三条同时成立：曾经成功观测过（否则没有可复位的"我们写入的值"）、
     * 最近一次成功早于 {@code staleBefore}、且尚未被判为 EXPIRED。
     * 直接对**锁内读到的行**求值，而不是对候选快照求值。
     */
    private static boolean isStillExpired(MonitorCollectionState current, OffsetDateTime staleBefore) {
        if (current.getLastSuccessAt() == null || !current.getLastSuccessAt().isBefore(staleBefore)) {
            return false;
        }
        return !RESULT_EXPIRED.equals(current.getLastResult());
    }

    // ---------- 身份比对 ----------

    private boolean lockAndMatchTarget(CollectionTargetRef ref) {
        if (CollectionTargetType.HOST.equals(ref.targetType())) {
            AssetHost host = assetHostMapper.selectForUpdate(ref.targetId());
            return host != null && Objects.equals(CollectionAddress.of(host), ref.hostAddress());
        }
        if (CollectionTargetType.SERVICE_INSTANCE.equals(ref.targetType())) {
            RuntimeServiceInstance instance = runtimeServiceInstanceMapper.selectForUpdate(ref.targetId());
            if (instance == null
                    || !Objects.equals(instance.getHostId(), ref.hostId())
                    || !Objects.equals(instance.getInstanceType(), ref.instanceType())) {
                return false;
            }
            // 服务与环境决定这次探测用的**是哪一份诊断参数**：实例被改绑到别的服务/环境后，
            // 旧样本描述的是"按旧服务的参数探测出的结论"，落到实例上就是在用 A 的结论冒充 B 的现状。
            // 因此把它们与主机绑定、实例类型一并纳入身份比对。
            if (!Objects.equals(instance.getServiceId(), ref.serviceId())
                    || !Objects.equals(instance.getEnvId(), ref.envId())) {
                return false;
            }
            if (ref.hostId() == null) {
                // 采样时就没有主机；hostId 相等已在上方确认，无可再比
                return true;
            }
            // 地址属于实例所在主机：主机改址后，基于旧地址采到的样本不得落到实例上
            AssetHost host = assetHostMapper.selectForUpdate(ref.hostId());
            return host != null && Objects.equals(CollectionAddress.of(host), ref.hostAddress());
        }
        return false;
    }

    /** 过期结算路径的加锁：只取行锁，不做身份比对（目标是"刚过期"的那个，身份自明）。 */
    private void lockTargetRow(String targetType, Long targetId) {
        if (CollectionTargetType.HOST.equals(targetType)) {
            assetHostMapper.selectForUpdate(targetId);
        } else if (CollectionTargetType.SERVICE_INSTANCE.equals(targetType)) {
            runtimeServiceInstanceMapper.selectForUpdate(targetId);
        }
    }

    /**
     * 探测所依据的 SSH 配置是否仍是当前启用配置，且**未被修改**。
     *
     * <p>只比 id 是不够的：同一条配置被改（换个用户名、换个端口）后 id 不变，
     * 旧样本却是按旧参数探测出来的，落到同一个目标上就是在报告"用 A 参数探测的结论"于"现在是 B 参数的机器"。
     * 因此额外比对 {@code updated_at}。
     *
     * <p>时间比对用 {@code toInstant()} 而不是 {@code equals}：{@code OffsetDateTime.equals}
     * 还要求时区偏移一致，而 timestamptz 读回来的偏移随会话时区变化，直接 equals 会造成
     * "配置根本没改却被判为改过"的误拒收。比时刻才是本意。
     *
     * <p>不比较"文件里是不是同一条记录的第几条"：历史数据允许同一主机存在多条启用配置，
     * 而枚举侧的取法是任意的。这里只要求"启用集合中仍存在这条 id + 这个 updated_at + 这个端口"，
     * 既覆盖了禁用/删除（集合里没有了），也不会在退化数据上误伤。
     *
     * <p>端口必须一并比对：拨号端点取自快照，配置被改端口后若样本仍被接受，就会出现
     * "采集表说按 22 端口探测、配置现在写着 2222"的错位。版本时刻通常已能覆盖这一改动，
     * 但不能只依赖它 —— 表结构允许 {@code ssh_port} 被单独改写而版本时刻不更新的写法，
     * 这一维度必须显式守住。
     */
    private boolean configStillMatches(CollectionTargetRef ref) {
        if (!ref.hasConfig()) {
            // 采样时就没有可用配置：那本次结果必然是 SKIPPED_UNCONFIGURED，不存在"配置变了"的问题
            return true;
        }
        Long hostId = CollectionTargetType.HOST.equals(ref.targetType()) ? ref.targetId() : ref.hostId();
        if (hostId == null) {
            return false;
        }
        List<AssetHostSshConfig> enabled = assetHostSshConfigMapper.selectEnabledByHostIdForUpdate(hostId);
        for (AssetHostSshConfig config : enabled) {
            if (Objects.equals(config.getId(), ref.sshConfigId())
                    && sameInstant(config.getUpdatedAt(), ref.sshConfigUpdatedAt())
                    && Objects.equals(config.getSshPort(), ref.sshPort())) {
                return true;
            }
        }
        return false;
    }

    private static boolean sameInstant(OffsetDateTime left, OffsetDateTime right) {
        if (left == null || right == null) {
            return left == right;
        }
        return left.toInstant().equals(right.toInstant());
    }

    // ---------- 写回 ----------

    /**
     * 迟到样本的拒收计数。目标已被删除时连采集状态行都没有，这里命中 0 行是预期的无操作。
     */
    private void countDiscarded(CollectionTargetRef ref, OffsetDateTime observedAt) {
        monitorCollectionStateMapper.incrementDiscarded(ref.targetType(), ref.targetId(), observedAt);
    }

    private MonitorCollectionState buildState(CollectionTargetRef ref, CollectionObservation observation) {
        boolean success = observation.result() == CollectionResult.SUCCESS;
        boolean failed = observation.result() == CollectionResult.FAILED;
        MonitorCollectionState state = new MonitorCollectionState();
        state.setTargetType(ref.targetType());
        state.setTargetId(ref.targetId());
        state.setObserverType(observation.observerType());
        state.setConfigPresent(observation.configPresent());
        state.setLastSource(sourceOf(observation).name());
        state.setLastResult(observation.result().name());
        state.setLastAttemptAt(observation.observedAt());
        // 失败时传 null，由 SQL 的 COALESCE 保留上一次成功时间——单次瞬时失败不清除最近成功观察
        state.setLastSuccessAt(success ? observation.observedAt() : null);
        // INSERT 分支直接用这个值：首次失败必须记 1，否则"连续失败计数"从 0 起步，
        // 达到阈值需要的轮数会比配置的 threshold 多一次。ON CONFLICT 分支由 SQL 自行 +1。
        state.setConsecutiveFailureCount(failed ? 1 : 0);
        state.setLastErrorCategory(observation.errorCategory() == null ? null : observation.errorCategory().name());
        state.setLastErrorSummary(observation.errorSummary());
        state.setLastObservedStatus(observation.observedStatus());
        state.setLastObservedHealth(observation.observedHealth());
        state.setObservedAt(observation.observedAt());
        return state;
    }

    /** 跳过类结果没有真正探测过，来源记 NONE，不谎称来自探测。 */
    private static CollectionSource sourceOf(CollectionObservation observation) {
        return observation.result().isSkip() ? CollectionSource.NONE : CollectionSource.SSH_PROBE;
    }

    private WriteOutcome applyTargetStatus(CollectionTargetRef ref, CollectionObservation observation) {
        if (observation.result() == CollectionResult.SUCCESS) {
            return writeObserved(ref, observation);
        }
        if (observation.result() == CollectionResult.FAILED) {
            return resetIfThresholdReached(ref);
        }
        return WriteOutcome.RECORDED_ONLY;
    }

    private WriteOutcome writeObserved(CollectionTargetRef ref, CollectionObservation observation) {
        if (CollectionTargetType.HOST.equals(ref.targetType())) {
            return assetHostMapper.updateHostStatus(ref.targetId(), observation.observedStatus()) > 0
                    ? WriteOutcome.APPLIED : WriteOutcome.DISCARDED;
        }
        String health = observation.observedHealth() == null ? HEALTH_UNKNOWN : observation.observedHealth();
        return runtimeServiceInstanceMapper.updateObservedStatus(
                ref.targetId(), observation.observedStatus(), health) > 0
                ? WriteOutcome.APPLIED : WriteOutcome.DISCARDED;
    }

    private WriteOutcome resetIfThresholdReached(CollectionTargetRef ref) {
        MonitorCollectionState state = monitorCollectionStateMapper.selectByTarget(ref.targetType(), ref.targetId());
        if (state == null || state.getConsecutiveFailureCount() < properties.getFailureThreshold()) {
            return WriteOutcome.RECORDED_ONLY;
        }
        return resetToUnknown(ref.targetType(), ref.targetId(),
                state.getLastObservedStatus(), state.getLastObservedHealth())
                ? WriteOutcome.RESET : WriteOutcome.RECORDED_ONLY;
    }

    /**
     * 把目标状态退回"无法确认"。
     *
     * <p>条件复位：只有库中该字段仍是采集上次观测到的值时才改写。若期间已被别的来源改成别的值，
     * 说明采集已不是该值的所有者，此时不该抹掉别人的结论。
     *
     * @return 是否真的改写了目标状态
     */
    private boolean resetToUnknown(String targetType, Long targetId, String expectedStatus, String expectedHealth) {
        if (expectedStatus == null) {
            // 从未成功观测过，没有"我们写入的值"可复位
            return false;
        }
        if (CollectionTargetType.HOST.equals(targetType)) {
            return assetHostMapper.resetHostStatusIfObserved(targetId, expectedStatus, STATUS_UNKNOWN) > 0;
        }
        String health = expectedHealth == null ? HEALTH_UNKNOWN : expectedHealth;
        return runtimeServiceInstanceMapper.resetObservedStatusIfMatched(
                targetId, expectedStatus, health, STATUS_UNKNOWN, HEALTH_UNKNOWN) > 0;
    }

    /** 一个目标的写回结论。 */
    public enum WriteOutcome {
        /** 目标状态已按观测结果写入 */
        APPLIED,
        /** 只写了采集状态，未改动目标状态 */
        RECORDED_ONLY,
        /** 迟到样本被拒收 */
        DISCARDED,
        /** 失败达阈值，目标状态已复位为无法确认 */
        RESET,
        /**
         * 受限轮次专用：样本或锁定的当前身份不在本轮的允许集内，**整轮对该目标零写入**。
         *
         * <p>与 {@link #DISCARDED} 的区别是"有没有留下痕迹"：{@code DISCARDED} 会更新丢弃计数，
         * {@code OUT_OF_SCOPE} 连计数都不动。它不是失败，也不刷新任何成功时刻。
         */
        OUT_OF_SCOPE
    }
}
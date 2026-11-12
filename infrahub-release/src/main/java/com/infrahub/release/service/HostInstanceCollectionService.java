package com.infrahub.release.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.infrahub.release.config.HostInstanceCollectionProperties;
import com.infrahub.release.dto.CollectionObservation;
import com.infrahub.release.dto.CollectionRunSummary;
import com.infrahub.release.dto.CollectionScopedRunSummary;
import com.infrahub.release.dto.CollectionTargetRef;
import com.infrahub.release.entity.AssetHost;
import com.infrahub.release.entity.AssetHostSshConfig;
import com.infrahub.release.entity.MonitorCollectionState;
import com.infrahub.release.entity.RuntimeServiceInstance;
import com.infrahub.release.mapper.MonitorCollectionStateMapper;
import com.infrahub.release.support.CollectionAddress;
import com.infrahub.release.support.CollectionAdmissionPolicy;
import com.infrahub.release.support.CollectionErrorCategory;
import com.infrahub.release.support.CollectionScopeRejection;
import com.infrahub.release.support.CollectionTargetType;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * 主机与实例状态采集编排。
 *
 * <p><b>职责边界</b>：本类只做"枚举目标 → 并发只读探测 → 交给写回边界"。
 * 探测本身由 {@link CollectionTargetObserver} 负责；**所有数据库一致性**都在
 * {@link CollectionWriteBackService} 里，本类不持有事务，也不直接写目标表。
 *
 * <p><b>纪律</b>：
 * <ul>
 *   <li>网络等待只发生在探测线程池里，调用方线程不做阻塞式网络调用，更不会带着事务等网络。</li>
 *   <li>单个目标失败/超时/写回异常都不阻塞其他目标（R6）：每个目标独立取结果、独立归类、独立事务。</li>
 *   <li>每个探测任务的截止时刻按**它自己的实际开始时刻**计算，而不是"轮到取它时再给一份完整预算"（R5）。</li>
 *   <li>线程池与队列都是有界的，探测卡死时任务不会无上限堆积（R5）。</li>
 * </ul>
 *
 * @see docs/release/v2-host-instance-status-collection-contract.md 第 5 节
 */
@Slf4j
@Service
public class HostInstanceCollectionService {

    /** 观测器未参与本轮（目标类型不支持）时的占位标识 */
    public static final String OBSERVER_NONE = "NONE";

    private final AssetHostService assetHostService;
    private final AssetHostSshConfigService assetHostSshConfigService;
    private final RuntimeServiceInstanceService runtimeServiceInstanceService;
    private final MonitorCollectionStateMapper monitorCollectionStateMapper;
    private final CollectionWriteBackService writeBackService;
    private final List<CollectionTargetObserver> observers;
    private final HostInstanceCollectionProperties properties;
    private final Clock collectionClock;

    private final ThreadPoolExecutor probePool;
    private final AtomicInteger threadSeq = new AtomicInteger();
    private volatile boolean shuttingDown;

    public HostInstanceCollectionService(AssetHostService assetHostService,
                                         AssetHostSshConfigService assetHostSshConfigService,
                                         RuntimeServiceInstanceService runtimeServiceInstanceService,
                                         MonitorCollectionStateMapper monitorCollectionStateMapper,
                                         CollectionWriteBackService writeBackService,
                                         List<CollectionTargetObserver> observers,
                                         HostInstanceCollectionProperties properties,
                                         Clock collectionClock) {
        this.assetHostService = assetHostService;
        this.assetHostSshConfigService = assetHostSshConfigService;
        this.runtimeServiceInstanceService = runtimeServiceInstanceService;
        this.monitorCollectionStateMapper = monitorCollectionStateMapper;
        this.writeBackService = writeBackService;
        this.observers = observers;
        this.properties = properties;
        this.collectionClock = collectionClock;
        // 有界队列，长度等于线程数：批大小也等于线程数，因此批内任务总能立刻拿到线程；
        // 反之若线程被上一轮卡死的探测占满，新任务会**被拒绝**而不是无限排队。
        // 旧实现用 newFixedThreadPool 的无界 LinkedBlockingQueue，卡住时任务无上限堆积。
        this.probePool = new ThreadPoolExecutor(
                properties.getMaxConcurrency(),
                properties.getMaxConcurrency(),
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(properties.getMaxConcurrency()),
                runnable -> {
                    Thread thread = new Thread(runnable, "collection-probe-" + threadSeq.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * 执行一轮采集：先结算过期，再枚举目标并发探测，最后逐个目标写回。
     *
     * <p>本方法**不加事务**：探测是网络等待，不能和数据库事务绑在一起。
     * 事务边界在 {@link CollectionWriteBackService} 里，每个目标一次。
     */
    public CollectionRunSummary collectOnce() {
        if (shuttingDown) {
            return CollectionRunSummary.empty();
        }
        int expired = settleExpired();

        List<CollectionTargetRef> targets = enumerateTargets();
        List<TargetOutcome> outcomes = probeAll(targets);

        int applied = 0;
        int recordedOnly = 0;
        int discarded = 0;
        int writeFailed = 0;
        for (TargetOutcome outcome : outcomes) {
            try {
                switch (writeBackService.writeBack(outcome.ref(), outcome.observation())) {
                    case APPLIED -> applied++;
                    case DISCARDED -> discarded++;
                    case RECORDED_ONLY, RESET -> recordedOnly++;
                }
            } catch (RuntimeException exception) {
                // R6：单个目标的写回异常（事务已回滚）不得终止其余目标。
                // 计数留痕 + 脱敏日志，绝不静默吞掉。
                writeFailed++;
                log.warn("目标写回失败，事务已回滚，继续处理其余目标：target={}/{} err={}",
                        outcome.ref().targetType(), outcome.ref().targetId(), summarize(exception));
            }
        }
        CollectionRunSummary summary = new CollectionRunSummary(
                targets.size(), applied, recordedOnly, discarded, expired, writeFailed);
        if (writeFailed > 0) {
            log.warn("状态采集完成（含写回失败）：{}", summary);
        } else {
            log.info("状态采集完成：{}", summary);
        }
        return summary;
    }

    /**
     * 受限单轮采集：**只对调用方显式给定的不可变允许集**探测与写回。
     *
     * <p><b>与无参入口的关系</b>：{@link #collectOnce()} 的行为**完全不变**（先全局结算过期、
     * 再全量枚举、再全量探测）。本方法是一条独立入口，不是它的开关；生产默认调度仍然走无参入口。
     * 允许集是**进程内一次调用**的边界：调用方必须以值对象的形式把已批准的身份清单交进来，
     * 本方法不会从本轮枚举结果里自动放宽自己的范围。
     *
     * <p><b>本轮不做任何过期结算（含允许集内目标）</b>：既不调用全局 {@link #settleExpired()}
     * （它的候选是"所有已过期目标"，不受允许集约束），也不按允许集逐目标去调
     * {@link CollectionWriteBackService#settleExpiredTarget}（该方法只锁目标行、不做任何身份比对，
     * 方法签名里也没有可以传入"当前身份"的位置，外部无法为它提供动作时刻的身份保证）。
     * 因此汇总里 {@code expiredSettled=0} **等于"本轮根本没有去查过期"**，
     * 由 {@code expiredSettlementSkipped=true} 显式标注，不能被读成"不存在过期证据"。
     * 读取接口里基于时间的 STALE 计算不受影响。
     *
     * <p><b>三道闸门</b>（每一道都在**动作发生之前**）：
     * <ol>
     *   <li><b>枚举后</b>：未通过允许集的目标**不进入探测池**，观测器与执行器根本看不到它们；</li>
     *   <li><b>交给观测器之前</b>：对当前采样身份再核一次允许集，排队不会放宽范围；</li>
     *   <li><b>写回事务边界</b>：{@link CollectionWriteBackService#writeBackScoped} 在锁内用
     *       **原始采样 ref** 复核，拒绝即零写入（连丢弃计数都不动）。</li>
     * </ol>
     *
     * <p><b>拒绝不是失败</b>：拒绝只记日志与计入本轮内存汇总（{@code rejections}），
     * 不写成 FAILED、不刷新成功时刻、也不会因为"拒绝次数"触发失败阈值复位。
     *
     * @param policy 本轮不可变允许集；{@code null} 会在**任何副作用之前**立即被拒
     *               （null 不等于全量）。空集合法，但必须零探测、零写回、零过期结算
     * @return 本轮观察汇总；停机等未开展工作的轮次返回 {@link CollectionScopedRunSummary#empty()}
     */
    public CollectionScopedRunSummary collectOnceForScope(CollectionAdmissionPolicy policy) {
        Objects.requireNonNull(policy,
                "受限采集入口必须显式传入允许集策略；null 不等于全量，空集请用 CollectionAdmissionPolicy.empty()");
        if (shuttingDown) {
            return CollectionScopedRunSummary.empty();
        }

        // 闸门 2 在探测线程里判定，因此拒绝留痕用同步列表；读取前会显式同步（见下方 frozen）
        List<CollectionScopeRejection> rejections = Collections.synchronizedList(new ArrayList<>());

        // 闸门 1：枚举后立刻筛。未通过的目标不会进入探测池。
        List<CollectionTargetRef> enumerated = enumerateTargets();
        List<CollectionTargetRef> candidates = new ArrayList<>();
        for (CollectionTargetRef ref : enumerated) {
            CollectionAdmissionPolicy.Decision decision = policy.decide(ref);
            if (decision.allowed()) {
                candidates.add(ref);
            } else {
                rejections.add(CollectionScopeRejection.at(CollectionScopeRejection.Stage.ENUMERATION,
                        ref, decision.reason(), decision.detail()));
            }
        }

        // 闸门 2：真正交给观测器之前，用同一份策略对当前采样身份再核一次。
        // 被拒的样本返回 null（既不拨号，也不进入写回），拒绝留痕已在复核处记录。
        List<TargetOutcome> outcomes = probeAll(candidates, ref -> {
            CollectionAdmissionPolicy.Decision again = policy.decide(ref);
            if (!again.allowed()) {
                rejections.add(CollectionScopeRejection.at(CollectionScopeRejection.Stage.PRE_PROBE,
                        ref, again.reason(), again.detail()));
                return null;
            }
            return probeOne(ref);
        });

        int applied = 0;
        int recordedOnly = 0;
        int discarded = 0;
        int writeFailed = 0;
        for (TargetOutcome outcome : outcomes) {
            try {
                // 闸门 3：写回事务边界上复核（含锁定的当前身份与配置），拒绝即零写入
                switch (writeBackService.writeBackScoped(outcome.ref(), outcome.observation(), policy)) {
                    case APPLIED -> applied++;
                    case DISCARDED -> discarded++;
                    case RECORDED_ONLY, RESET -> recordedOnly++;
                    case OUT_OF_SCOPE -> recordWriteBoundaryRejection(rejections, policy, outcome.ref());
                }
            } catch (RuntimeException exception) {
                // 单个目标的写回异常（事务已回滚）不得终止其余目标；计数留痕 + 脱敏日志
                writeFailed++;
                log.warn("受限轮次目标写回失败，事务已回滚，继续处理其余目标：target={}/{} err={}",
                        outcome.ref().targetType(), outcome.ref().targetId(), summarize(exception));
            }
        }

        List<CollectionScopeRejection> frozen;
        // 所有探测任务都已取过结果（超时者已被取消），这里仍显式加锁再拷贝，
        // 避免"忽略中断仍在写入"的个别任务与拷贝并发
        synchronized (rejections) {
            frozen = List.copyOf(rejections);
        }
        CollectionScopedRunSummary summary = new CollectionScopedRunSummary(
                enumerated.size(), candidates.size(), outcomes.size(),
                applied, recordedOnly, discarded, writeFailed,
                0, true, frozen);
        if (writeFailed == 0 && frozen.isEmpty()) {
            log.info("受限状态采集完成：{}", summary);
        } else {
            log.warn("受限状态采集完成（含拒绝/写回失败）：{}", summary);
            for (CollectionScopeRejection rejection : frozen) {
                log.warn("受限轮次拒绝：{}", rejection);
            }
        }
        return summary;
    }

    /**
     * 写回事务边界拒绝了这一份样本时的留痕。
     *
     * <p>原始 ref 仍在允许集内、却仍被边界拒绝，说明**锁定的当前目标/配置身份**已不再见证它
     * （采样之后被改址/改绑/换配置）；若原始 ref 本身已不在允许集内，则沿用它的分类原因。
     */
    private static void recordWriteBoundaryRejection(List<CollectionScopeRejection> rejections,
                                                     CollectionAdmissionPolicy policy,
                                                     CollectionTargetRef ref) {
        CollectionAdmissionPolicy.Decision decision = policy.decide(ref);
        if (decision.allowed()) {
            rejections.add(CollectionScopeRejection.at(CollectionScopeRejection.Stage.WRITE_BOUNDARY, ref,
                    CollectionAdmissionPolicy.Reason.CURRENT_IDENTITY_CHANGED,
                    "写回事务边界复核未通过：样本仍在允许集内，但锁定的当前目标/配置身份已不再见证它"));
            return;
        }
        rejections.add(CollectionScopeRejection.at(CollectionScopeRejection.Stage.WRITE_BOUNDARY,
                ref, decision.reason(), decision.detail()));
    }

    // ---------- 枚举 ----------

    private List<CollectionTargetRef> enumerateTargets() {
        List<AssetHost> hosts = assetHostService.list();
        Map<Long, AssetHost> hostById = new HashMap<>();
        for (AssetHost host : hosts) {
            hostById.put(host.getId(), host);
        }
        Map<Long, AssetHostSshConfig> configByHost = enabledConfigByHost();

        List<CollectionTargetRef> refs = new ArrayList<>();
        for (AssetHost host : hosts) {
            refs.add(newRef(CollectionTargetType.HOST, host.getId(), host.getId(), null, null, null,
                    configByHost.get(host.getId()), CollectionAddress.of(host)));
        }
        for (RuntimeServiceInstance instance : runtimeServiceInstanceService.list()) {
            AssetHostSshConfig config = instance.getHostId() == null ? null : configByHost.get(instance.getHostId());
            AssetHost host = instance.getHostId() == null ? null : hostById.get(instance.getHostId());
            refs.add(newRef(CollectionTargetType.SERVICE_INSTANCE, instance.getId(), instance.getHostId(),
                    instance.getInstanceType(), instance.getServiceId(), instance.getEnvId(),
                    config, CollectionAddress.of(host)));
        }
        return refs;
    }

    /** 一次查出全部启用中的 SSH 配置，避免逐台主机查询。 */
    private Map<Long, AssetHostSshConfig> enabledConfigByHost() {
        List<AssetHostSshConfig> configs = assetHostSshConfigService.list(
                new LambdaQueryWrapper<AssetHostSshConfig>().eq(AssetHostSshConfig::getEnabled, Boolean.TRUE));
        Map<Long, AssetHostSshConfig> byHost = new HashMap<>();
        for (AssetHostSshConfig config : configs) {
            byHost.putIfAbsent(config.getHostId(), config);
        }
        return byHost;
    }

    /**
     * 采样：把"这次准入批准的可拨号身份"整体固定下来。
     *
     * <p>除了既有的目标/主机/实例类型/配置 id 与版本时刻，还要固定**生效端口**，
     * 以及实例的**服务与环境**（它们决定诊断命令用的是哪一份参数）。
     * 少了任何一项，采样到拨号之间被改动的那个维度就无法察觉，拨号会按改动后的值发出去。
     */
    private static CollectionTargetRef newRef(String targetType, Long targetId, Long hostId,
                                              String instanceType, Long serviceId, Long envId,
                                              AssetHostSshConfig config, String hostAddress) {
        return new CollectionTargetRef(targetType, targetId, hostId, instanceType,
                config == null ? null : config.getId(),
                config == null ? null : config.getUpdatedAt(),
                hostAddress,
                config == null ? null : config.getSshPort(),
                serviceId,
                envId);
    }

    // ---------- 探测 ----------

    /**
     * 全量路径的探测：探测函数就是产品既有的 {@link #probeOne}，与改造前的行为逐字一致。
     */
    private List<TargetOutcome> probeAll(List<CollectionTargetRef> targets) {
        return probeAll(targets, this::probeOne);
    }

    /**
     * 分批并发探测。批大小等于并发上限，保证批内每个任务都能立刻拿到线程。
     *
     * <p>{@code probe} 是"对一个已采样的身份真正执行探测"的那一步：全量入口传
     * {@link #probeOne}，受限入口传"策略复核 + {@link #probeOne}"。线程池、预算锚点、批大小、
     * 超时归类与取消语义都由本方法统一提供，两条路径不会各自长出一套并发行为。
     */
    private List<TargetOutcome> probeAll(List<CollectionTargetRef> targets,
                                         Function<CollectionTargetRef, CollectionObservation> probe) {
        List<TargetOutcome> outcomes = new ArrayList<>(targets.size());
        int batchSize = Math.max(1, properties.getMaxConcurrency());
        for (int start = 0; start < targets.size(); start += batchSize) {
            if (shuttingDown || Thread.currentThread().isInterrupted()) {
                break;
            }
            int end = Math.min(start + batchSize, targets.size());
            outcomes.addAll(probeBatch(targets.subList(start, end), probe));
        }
        return outcomes;
    }

    private List<TargetOutcome> probeBatch(List<CollectionTargetRef> batch,
                                          Function<CollectionTargetRef, CollectionObservation> probe) {
        long budgetNanos = TimeUnit.MILLISECONDS.toNanos(properties.getSingleTargetTimeoutMs());
        List<ProbeSlot> slots = new ArrayList<>(batch.size());
        for (CollectionTargetRef ref : batch) {
            slots.add(submitProbe(ref, budgetNanos, probe));
        }

        List<TargetOutcome> outcomes = new ArrayList<>(batch.size());
        for (int index = 0; index < slots.size(); index++) {
            ProbeSlot slot = slots.get(index);
            if (slot.preFailed() != null) {
                outcomes.add(new TargetOutcome(slot.ref(), slot.preFailed()));
                continue;
            }
            try {
                // 预算按该任务**自己的锚点**算，而不是"轮到取结果时再给一份完整预算"。
                // 旧实现每个下标都调 future.get(单目标超时)，于是第 N 个任务实际能跑
                // N × 单目标超时 那么久 —— 100ms 配置下第二个任务跑到 180ms 仍被接受。
                CollectionObservation observation =
                        slot.future().get(slot.remainingMillis(), TimeUnit.MILLISECONDS);
                // null 表示该样本在**交给观测器之前**就被拒绝（受限入口的闸门 2）：它没有拨号，
                // 也不得进入写回。留痕已在复核处记下，这里直接丢弃，不产生任何后续动作。
                if (observation != null) {
                    outcomes.add(new TargetOutcome(slot.ref(), observation));
                }
            } catch (TimeoutException exception) {
                boolean started = slot.startedNanos().get() >= 0;
                slot.cancel();
                outcomes.add(new TargetOutcome(slot.ref(), started
                        ? CollectionObservation.failure(CollectionErrorCategory.NETWORK_TIMEOUT,
                                "单目标探测自开始起超过 " + properties.getSingleTargetTimeoutMs() + "ms 未返回，已放弃等待",
                                observerTypeFor(slot.ref()), now())
                        : CollectionObservation.failure(CollectionErrorCategory.PROBE_SCHEDULING_REJECTED,
                                "探测排队超过 " + properties.getSingleTargetTimeoutMs() + "ms 仍未获得线程，本轮未执行",
                                observerTypeFor(slot.ref()), now())));
            } catch (ExecutionException exception) {
                outcomes.add(new TargetOutcome(slot.ref(), CollectionObservation.failure(
                        CollectionErrorCategory.PROBE_ERROR, summarize(exception.getCause()),
                        observerTypeFor(slot.ref()), now())));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                for (int rest = index; rest < slots.size(); rest++) {
                    slots.get(rest).cancel();
                }
                break;
            }
        }
        return outcomes;
    }

    private ProbeSlot submitProbe(CollectionTargetRef ref, long budgetNanos,
                                 Function<CollectionTargetRef, CollectionObservation> probe) {
        if (shuttingDown) {
            return ProbeSlot.notScheduled(ref, CollectionObservation.failure(
                    CollectionErrorCategory.PROBE_SCHEDULING_REJECTED,
                    "采集正在停机，本轮不再调度该目标", observerTypeFor(ref), now()));
        }
        AtomicLong startedAtNanos = new AtomicLong(-1L);
        long submittedAtNanos = System.nanoTime();
        try {
            Future<CollectionObservation> future = probePool.submit(() -> {
                startedAtNanos.set(System.nanoTime());
                return probe.apply(ref);
            });
            return ProbeSlot.scheduled(ref, future, submittedAtNanos, startedAtNanos, budgetNanos);
        } catch (RejectedExecutionException exception) {
            // 线程池已满 = 有探测卡住了没回来。这是**采集侧**的失败，必须显式留痕，
            // 不能因为"没调度"就悄悄跳过——否则该目标会一直挂着旧状态冒充新鲜。
            return ProbeSlot.notScheduled(ref, CollectionObservation.failure(
                    CollectionErrorCategory.PROBE_SCHEDULING_REJECTED,
                    "采集线程池已满（上一轮探测尚未结束），本轮未调度该目标",
                    observerTypeFor(ref), now()));
        }
    }

    private CollectionObservation probeOne(CollectionTargetRef ref) {
        // 先判类型再判配置：不支持的实例类型必须显式报"不支持"，
        // 不能因为恰好也没配 SSH 就降级说成"未配置"。
        CollectionTargetObserver observer = selectObserver(ref);
        if (observer == null) {
            return CollectionObservation.unsupported(OBSERVER_NONE, now());
        }
        if (!ref.hasConfig()) {
            return CollectionObservation.unconfigured(observer.observerType(), now());
        }
        return observer.observe(ref);
    }

    private CollectionTargetObserver selectObserver(CollectionTargetRef ref) {
        for (CollectionTargetObserver observer : observers) {
            if (observer.supports(ref)) {
                return observer;
            }
        }
        return null;
    }

    private String observerTypeFor(CollectionTargetRef ref) {
        CollectionTargetObserver observer = selectObserver(ref);
        return observer == null ? OBSERVER_NONE : observer.observerType();
    }

    // ---------- 过期结算 ----------

    /**
     * 结算过期：把 last_success_at 已超出新鲜度阈值的目标标记为 EXPIRED，并复位其目标状态，
     * 使界面不会再长期显示新鲜的 ONLINE / RUNNING。
     *
     * <p>先只读选出候选，再**逐目标一个短事务**（标记 + 复位）：
     * 某个目标的复位失败只回滚它自己，下一轮仍会被重新选中并可重试，
     * 不会像旧实现那样"批量标记已完成、复位失败即永久跳过"。
     *
     * <p>候选只用来**定位**目标，不进事务、也不作为判断依据：这两个时刻之间可能有别的执行者
     * 提交了更新的观测，候选携带的 {@code lastObservedStatus/Health} 早已不是当前值。
     * 过期与否、复位到什么，全部由事务内锁住采集状态行后读到的**当前证据**决定
     * （见 {@link CollectionWriteBackService#settleExpiredTarget}）。
     */
    private int settleExpired() {
        OffsetDateTime staleBefore = now().minus(Duration.ofMillis(properties.getStaleThresholdMs()));
        List<MonitorCollectionState> candidates = monitorCollectionStateMapper.selectExpiredTargets(staleBefore);
        int reset = 0;
        for (MonitorCollectionState state : candidates) {
            try {
                if (writeBackService.settleExpiredTarget(state.getTargetType(), state.getTargetId(), staleBefore)) {
                    reset++;
                }
            } catch (RuntimeException exception) {
                log.warn("过期结算失败，事务已回滚，该目标将在下一轮重试：target={}/{} err={}",
                        state.getTargetType(), state.getTargetId(), summarize(exception));
            }
        }
        return reset;
    }

    // ---------- 生命周期 ----------

    /** 停机时停止后续调度并做有界等待：不给线程池无限期收尾的机会。 */
    @PreDestroy
    public void shutdown() {
        shuttingDown = true;
        probePool.shutdownNow();
        try {
            if (!probePool.awaitTermination(properties.getSingleTargetTimeoutMs(), TimeUnit.MILLISECONDS)) {
                log.warn("采集线程池未在 {}ms 内结束，不再等待", properties.getSingleTargetTimeoutMs());
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(collectionClock);
    }

    private static String summarize(Throwable throwable) {
        if (throwable == null) {
            return "探测任务异常结束";
        }
        String message = throwable.getMessage() == null ? throwable.getClass().getSimpleName() : throwable.getMessage();
        String firstLine = message.strip();
        int newline = firstLine.indexOf('\n');
        if (newline >= 0) {
            firstLine = firstLine.substring(0, newline);
        }
        return firstLine.length() <= 200 ? firstLine : firstLine.substring(0, 200);
    }

    private record TargetOutcome(CollectionTargetRef ref, CollectionObservation observation) {
    }

    /**
     * 一个已提交（或未能提交）的探测任务。
     *
     * <p>{@code startedAtNanos} 为 -1 表示任务还没进入 call()：此时预算从**提交时刻**起算，
     * 也就是"排队时间也算进预算"。卡住的探测会把线程占满，排队者因此很快超预算，
     * 被归类为"未调度"并取消，而不是无限期排队等一个可能永远不来的线程。
     */
    private record ProbeSlot(CollectionTargetRef ref,
                             Future<CollectionObservation> future,
                             CollectionObservation preFailed,
                             long submittedAtNanos,
                             AtomicLong startedNanos,
                             long budgetNanos) {

        static ProbeSlot scheduled(CollectionTargetRef ref, Future<CollectionObservation> future,
                                   long submittedAtNanos, AtomicLong startedNanos, long budgetNanos) {
            return new ProbeSlot(ref, future, null, submittedAtNanos, startedNanos, budgetNanos);
        }

        static ProbeSlot notScheduled(CollectionTargetRef ref, CollectionObservation preFailed) {
            return new ProbeSlot(ref, null, preFailed, 0L, new AtomicLong(-1L), 0L);
        }

        /** 实际开始时刻为锚点；尚未开始则以提交时刻为锚点。 */
        long remainingMillis() {
            long started = startedNanos.get();
            long anchor = started < 0 ? submittedAtNanos : started;
            long remaining = budgetNanos - (System.nanoTime() - anchor);
            return Math.max(0L, TimeUnit.NANOSECONDS.toMillis(remaining));
        }

        void cancel() {
            if (future != null) {
                future.cancel(true);
            }
        }
    }
}
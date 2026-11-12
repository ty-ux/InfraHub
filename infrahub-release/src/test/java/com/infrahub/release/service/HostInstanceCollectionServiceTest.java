package com.infrahub.release.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.infrahub.release.config.HostInstanceCollectionProperties;
import com.infrahub.release.dto.CollectionObservation;
import com.infrahub.release.dto.CollectionRunSummary;
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
import com.infrahub.release.support.CollectionErrorCategory;
import com.infrahub.release.support.CollectionResult;
import com.infrahub.release.support.CollectionSource;
import com.infrahub.release.support.CollectionTargetType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 采集编排的单元测试：用可控观测器 + 固定时钟验证并发、超时、乱序、按类别拒收与条件写回。
 *
 * <p><b>写回用的是真的 {@link CollectionWriteBackService}</b>，只有 Mapper 是桩。
 * 这样"锁顺序 → 配置版本比对 → 接受样本 → 写目标字段"这条链是被真正跑过的，
 * 而不是把编排层与写回层各测一半、中间的真实契约没人验。
 * 事务语义（一个目标一个事务、异常回滚、复位失败可重试）由
 * {@code CollectionWriteBackServiceTransactionTest} 单独证明；真实 PostgreSQL 上的
 * 并发交错与阈值语义由 {@code CollectionWriteBackPostgresTest} 证明。
 *
 * <p>探测被替换成 {@link FakeObserver}，所以这里**不连真实 SSH、不碰真实数据库**。
 *
 * @see docs/release/v2-host-instance-status-collection-contract.md 第 7 节
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class HostInstanceCollectionServiceTest {

    private static final OffsetDateTime T0 = OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    private static final Clock FIXED_CLOCK = Clock.fixed(T0.toInstant(), ZoneOffset.UTC);
    private static final String OBSERVER = "SSH_PROCESS";
    private static final String PROCESS = "PROCESS";

    @Mock
    private AssetHostService assetHostService;
    @Mock
    private AssetHostSshConfigService assetHostSshConfigService;
    @Mock
    private RuntimeServiceInstanceService runtimeServiceInstanceService;
    @Mock
    private AssetHostMapper assetHostMapper;
    @Mock
    private RuntimeServiceInstanceMapper runtimeServiceInstanceMapper;
    @Mock
    private MonitorCollectionStateMapper monitorCollectionStateMapper;
    @Mock
    private AssetHostSshConfigMapper assetHostSshConfigMapper;

    private HostInstanceCollectionService service;
    private CollectionWriteBackService writeBackService;
    private HostInstanceCollectionProperties properties;
    private FakeObserver observer;

    @BeforeEach
    void freshProperties() {
        properties = new HostInstanceCollectionProperties();
        properties.setEnabled(true);
        properties.setPeriodMs(30_000L);
        properties.setSingleTargetTimeoutMs(3_000L);
        properties.setMaxConcurrency(3);
        properties.setFailureThreshold(3);
        properties.setStaleThresholdMs(90_000L);
    }

    @AfterEach
    void tearDown() {
        // 池线程是守护线程，停机时会被中断，阻塞中的假观测器随之退出。
        if (service != null) {
            service.shutdown();
        }
    }

    // ---------- 成功观测 ----------

    @Test
    @DisplayName("主机探测成功：按观测值写 host_status，采集行记 SSH_PROBE 与最近成功观察时间")
    void hostSuccessWritesObservedStatus() {
        giveHost(1L, 5L);

        start(target -> success("ONLINE"));

        CollectionRunSummary summary = service.collectOnce();

        assertThat(summary.total()).isEqualTo(1);
        assertThat(summary.applied()).as("成功观测应写入目标状态").isEqualTo(1);
        assertThat(summary.recordedOnly()).isZero();
        assertThat(summary.discarded()).isZero();

        verify(assetHostMapper).updateHostStatus(1L, "ONLINE");
        verify(runtimeServiceInstanceMapper, never()).updateObservedStatus(anyLong(), any(), any());

        MonitorCollectionState state = capturedStateFor(CollectionTargetType.HOST, 1L);
        assertThat(state.getTargetType()).isEqualTo(CollectionTargetType.HOST);
        assertThat(state.getLastResult()).isEqualTo(CollectionResult.SUCCESS.name());
        assertThat(state.getLastSource()).as("来源必须是主动探测").isEqualTo(CollectionSource.SSH_PROBE.name());
        assertThat(state.getConfigPresent()).isTrue();
        assertThat(state.getLastSuccessAt()).isEqualTo(T0);
        assertThat(state.getLastAttemptAt()).isEqualTo(T0);
        assertThat(state.getLastErrorCategory()).as("成功观测不该带失败类别").isNull();
    }

    @Test
    @DisplayName("写回顺序固定：先锁目标行，再锁采集行，再锁 SSH 配置行，然后才接受样本、写目标字段")
    void writeBackFollowsTheFixedLockOrder() {
        giveHost(1L, 5L);

        start(target -> success("ONLINE"));
        service.collectOnce();

        InOrder order = inOrder(assetHostMapper, monitorCollectionStateMapper, assetHostSshConfigMapper);
        order.verify(assetHostMapper).selectForUpdate(1L);
        order.verify(monitorCollectionStateMapper).selectByTargetForUpdate(CollectionTargetType.HOST, 1L);
        order.verify(assetHostSshConfigMapper).selectEnabledByHostIdForUpdate(1L);
        order.verify(monitorCollectionStateMapper).upsertObservation(any());
        order.verify(assetHostMapper).updateHostStatus(1L, "ONLINE");
    }

    @Test
    @DisplayName("进程在跑但健康检查失败：RUNNING + DOWN 是成功观测，不推进失败计数")
    void runningProcessWithFailingHealthIsSuccess() {
        giveInstance(200L, 1L, PROCESS, 5L);

        start(target -> success("RUNNING", "DOWN"));

        CollectionRunSummary summary = service.collectOnce();

        assertThat(summary.applied()).isEqualTo(1);
        verify(runtimeServiceInstanceMapper).updateObservedStatus(200L, "RUNNING", "DOWN");

        MonitorCollectionState state = capturedStateFor(CollectionTargetType.SERVICE_INSTANCE, 200L);
        assertThat(state.getLastResult())
                .as("健康接口报错不影响'探测本身成功'").isEqualTo(CollectionResult.SUCCESS.name());
        assertThat(state.getLastSuccessAt()).as("成功观测应刷新最近成功观察时间").isEqualTo(T0);
        assertThat(state.getLastObservedStatus()).isEqualTo("RUNNING");
        assertThat(state.getLastObservedHealth()).isEqualTo("DOWN");
    }

    @Test
    @DisplayName("探测成功但进程确实不在：结论 STOPPED，不判为失败")
    void processAbsentIsSuccessNotFailure() {
        giveInstance(201L, 1L, PROCESS, 5L);

        start(target -> CollectionObservation.processAbsent(OBSERVER, T0));

        CollectionRunSummary summary = service.collectOnce();

        assertThat(summary.applied()).isEqualTo(1);
        verify(runtimeServiceInstanceMapper).updateObservedStatus(201L, "STOPPED", "UNKNOWN");
        assertThat(capturedStateFor(CollectionTargetType.SERVICE_INSTANCE, 201L).getLastResult())
                .isEqualTo(CollectionResult.SUCCESS.name());
    }

    @Test
    @DisplayName("一轮里主机与实例一起采集：两类目标各自写回，不互相串写")
    void hostAndInstanceAreCollectedInOneRound() {
        giveHost(1L, 5L);
        giveInstance(200L, 1L, PROCESS, 5L);

        start(target -> success("ONLINE", "UP"));

        CollectionRunSummary summary = service.collectOnce();

        assertThat(summary.total()).as("主机与实例都算目标").isEqualTo(2);
        assertThat(summary.applied()).isEqualTo(2);
        verify(assetHostMapper).updateHostStatus(1L, "ONLINE");
        verify(runtimeServiceInstanceMapper).updateObservedStatus(200L, "ONLINE", "UP");

        MonitorCollectionState hostState = capturedStateFor(CollectionTargetType.HOST, 1L);
        MonitorCollectionState instanceState =
                capturedStateFor(CollectionTargetType.SERVICE_INSTANCE, 200L);
        assertThat(hostState.getTargetId()).as("两类目标共用主键空间，靠 target_type 区分").isEqualTo(1L);
        assertThat(instanceState.getTargetId()).isEqualTo(200L);
        assertThat(hostState.getLastObservedStatus()).isEqualTo("ONLINE");
        assertThat(instanceState.getLastObservedHealth()).isEqualTo("UP");
    }

    // ---------- 未配置 / 不支持 ----------

    @Test
    @DisplayName("未配置连接：记 SKIPPED_UNCONFIGURED，不探测、不改目标状态、不算失败")
    void missingConfigIsSkippedNotFailed() {
        giveHostWithoutConfig(1L);

        start(target -> success("ONLINE"));

        CollectionRunSummary summary = service.collectOnce();

        assertThat(observer.probed()).as("无可用连接配置时不得发起探测").isEmpty();
        assertThat(summary.applied()).isZero();
        assertThat(summary.recordedOnly()).isEqualTo(1);
        verify(assetHostMapper, never()).updateHostStatus(anyLong(), any());

        MonitorCollectionState state = capturedStateFor(CollectionTargetType.HOST, 1L);
        assertThat(state.getLastResult()).isEqualTo(CollectionResult.SKIPPED_UNCONFIGURED.name());
        assertThat(state.getLastSource()).as("没探测过就不能声称来自探测").isEqualTo(CollectionSource.NONE.name());
        assertThat(state.getLastErrorCategory()).isEqualTo(CollectionErrorCategory.CONFIG_MISSING.name());
        assertThat(state.getConfigPresent()).isFalse();
        assertThat(state.getLastSuccessAt()).as("跳过不构成成功观测").isNull();
        assertThat(state.getLastObservedStatus()).as("没观测过就不能留下观测值").isNull();
    }

    @Test
    @DisplayName("实例类型无观测器支持：显式判为 SKIPPED_UNSUPPORTED，不探测、不留观测值")
    void unsupportedInstanceTypeIsSkipped() {
        giveInstance(202L, 1L, "DOCKER", 5L);

        // 观测器只认 PROCESS：Docker / K8s 实例没有匹配的观测器
        start(target -> success("RUNNING", "UP"));

        CollectionRunSummary summary = service.collectOnce();

        assertThat(observer.probed()).isEmpty();
        assertThat(summary.recordedOnly()).isEqualTo(1);
        verify(runtimeServiceInstanceMapper, never()).updateObservedStatus(anyLong(), any(), any());

        MonitorCollectionState state = capturedStateFor(CollectionTargetType.SERVICE_INSTANCE, 202L);
        assertThat(state.getLastResult()).isEqualTo(CollectionResult.SKIPPED_UNSUPPORTED.name());
        assertThat(state.getLastErrorCategory()).isEqualTo(CollectionErrorCategory.UNSUPPORTED_TARGET.name());
        assertThat(state.getLastObservedStatus())
                .as("不支持的实例类型绝不能被写成 STOPPED").isNull();
    }

    @Test
    @DisplayName("既无匹配观测器又无配置时判'不支持'：判定顺序是先类型后配置")
    void unsupportedWinsOverUnconfigured() {
        giveInstanceWithoutConfig(203L, 1L, "KUBERNETES");

        start(target -> success("RUNNING", "UP"));

        CollectionRunSummary summary = service.collectOnce();

        assertThat(summary.recordedOnly()).isEqualTo(1);
        MonitorCollectionState state = capturedStateFor(CollectionTargetType.SERVICE_INSTANCE, 203L);
        assertThat(state.getLastResult())
                .as("没有观测器就必须报'不支持'，否则会把'平台没能力采'说成'用户没配置'")
                .isEqualTo(CollectionResult.SKIPPED_UNSUPPORTED.name());
        assertThat(state.getConfigPresent()).isFalse();
    }

    // ---------- 失败与阈值 ----------

    @Test
    @DisplayName("首次失败就从 1 起算：空采集表时第一行的连续失败计数必须是 1，不是 0")
    void firstFailureStartsAtOne() {
        giveHost(1L, 5L);

        start(target -> CollectionObservation.failure(CollectionErrorCategory.AUTH_FAILED,
                "认证失败", OBSERVER, T0));

        // 采集表里还没有这一行：INSERT 分支直接用传下去的计数值
        when(monitorCollectionStateMapper.selectByTarget(CollectionTargetType.HOST, 1L)).thenReturn(null);

        service.collectOnce();

        MonitorCollectionState state = capturedStateFor(CollectionTargetType.HOST, 1L);
        assertThat(state.getConsecutiveFailureCount())
                .as("首轮失败记 1：否则阈值=1 要等到第二轮才触发，等于配置被悄悄放大一轮")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("成功与跳过都把计数清零，交给 SQL：传给 INSERT 分支的计数为 0")
    void successResetsFailureCountToZero() {
        giveHost(1L, 5L);

        start(target -> success("ONLINE"));
        service.collectOnce();

        assertThat(capturedStateFor(CollectionTargetType.HOST, 1L).getConsecutiveFailureCount()).isZero();
    }

    @Test
    @DisplayName("失败未达阈值：只记采集行不动目标状态；失败样本不带观测值，成功时间交由 SQL 保留")
    void failureBelowThresholdKeepsTargetStatus() {
        giveHost(1L, 5L);

        start(target -> CollectionObservation.failure(CollectionErrorCategory.AUTH_FAILED,
                "认证失败", OBSERVER, T0));

        when(monitorCollectionStateMapper.selectByTarget(CollectionTargetType.HOST, 1L))
                .thenReturn(stateRow(CollectionTargetType.HOST, 1L, 1, "ONLINE", "UNKNOWN"));

        CollectionRunSummary summary = service.collectOnce();

        assertThat(summary.recordedOnly()).isEqualTo(1);
        assertThat(summary.applied()).isZero();
        verify(assetHostMapper, never()).resetHostStatusIfObserved(anyLong(), any(), any());
        verify(assetHostMapper, never()).updateHostStatus(anyLong(), any());

        MonitorCollectionState state = capturedStateFor(CollectionTargetType.HOST, 1L);
        assertThat(state.getLastResult()).isEqualTo(CollectionResult.FAILED.name());
        assertThat(state.getLastErrorCategory()).isEqualTo(CollectionErrorCategory.AUTH_FAILED.name());
        assertThat(state.getLastErrorSummary()).isEqualTo("认证失败");
        assertThat(state.getLastSuccessAt())
                .as("传 null 给 SQL 的 COALESCE，单次瞬时失败不清除最近成功观察时间")
                .isNull();
        assertThat(state.getLastObservedStatus())
                .as("失败的样本没有观测状态，不能覆盖上次观测").isNull();
    }

    @Test
    @DisplayName("连续失败达阈值：按上次观测值条件复位主机为 UNKNOWN")
    void failureAtThresholdResetsHostToUnknown() {
        giveHost(1L, 5L);

        start(target -> CollectionObservation.failure(CollectionErrorCategory.NETWORK_TIMEOUT,
                "连接超时", OBSERVER, T0));

        when(monitorCollectionStateMapper.selectByTarget(CollectionTargetType.HOST, 1L))
                .thenReturn(stateRow(CollectionTargetType.HOST, 1L, 3, "ONLINE", "UNKNOWN"));
        when(assetHostMapper.resetHostStatusIfObserved(1L, "ONLINE", "UNKNOWN")).thenReturn(1);

        CollectionRunSummary summary = service.collectOnce();

        assertThat(summary.recordedOnly()).as("复位也属于'未把状态写新鲜'").isEqualTo(1);
        assertThat(summary.applied()).isZero();
        verify(assetHostMapper).resetHostStatusIfObserved(1L, "ONLINE", "UNKNOWN");
    }

    @Test
    @DisplayName("实例达阈值：两个维度一起按上次观测值条件复位")
    void failureAtThresholdResetsInstanceDimensions() {
        giveInstance(200L, 1L, PROCESS, 5L);

        start(target -> CollectionObservation.failure(CollectionErrorCategory.TARGET_UNREACHABLE,
                "目标不可达", OBSERVER, T0));

        when(monitorCollectionStateMapper.selectByTarget(CollectionTargetType.SERVICE_INSTANCE, 200L))
                .thenReturn(stateRow(CollectionTargetType.SERVICE_INSTANCE, 200L, 3, "RUNNING", "DOWN"));
        when(runtimeServiceInstanceMapper.resetObservedStatusIfMatched(200L, "RUNNING", "DOWN", "UNKNOWN", "UNKNOWN"))
                .thenReturn(1);

        CollectionRunSummary summary = service.collectOnce();

        assertThat(summary.recordedOnly()).isEqualTo(1);
        verify(runtimeServiceInstanceMapper)
                .resetObservedStatusIfMatched(200L, "RUNNING", "DOWN", "UNKNOWN", "UNKNOWN");
        verify(assetHostMapper, never()).resetHostStatusIfObserved(anyLong(), any(), any());
    }

    @Test
    @DisplayName("达阈值但目标状态已被他方改写：条件复位命中 0 行，不抹掉别人的结论")
    void resetIsSkippedWhenTargetStatusWasOverwritten() {
        giveHost(1L, 5L);

        start(target -> CollectionObservation.failure(CollectionErrorCategory.PROBE_ERROR,
                "探测异常", OBSERVER, T0));

        when(monitorCollectionStateMapper.selectByTarget(CollectionTargetType.HOST, 1L))
                .thenReturn(stateRow(CollectionTargetType.HOST, 1L, 5, "ONLINE", "UNKNOWN"));
        when(assetHostMapper.resetHostStatusIfObserved(1L, "ONLINE", "UNKNOWN")).thenReturn(0);

        CollectionRunSummary summary = service.collectOnce();

        verify(assetHostMapper).resetHostStatusIfObserved(1L, "ONLINE", "UNKNOWN");
        verify(assetHostMapper, never()).updateHostStatus(anyLong(), any());
        assertThat(summary.applied()).isZero();
        assertThat(summary.recordedOnly()).isEqualTo(1);
    }

    @Test
    @DisplayName("从未成功观测过的目标达阈值：没有可复位的历史值，不写 UNKNOWN")
    void resetIsSkippedWhenNeverObserved() {
        giveHost(1L, 5L);

        start(target -> CollectionObservation.failure(CollectionErrorCategory.TARGET_UNREACHABLE,
                "目标不可达", OBSERVER, T0));

        when(monitorCollectionStateMapper.selectByTarget(CollectionTargetType.HOST, 1L))
                .thenReturn(stateRow(CollectionTargetType.HOST, 1L, 9, null, null));

        service.collectOnce();

        verify(assetHostMapper, never()).resetHostStatusIfObserved(anyLong(), any(), any());
    }

    // ---------- 拒收 ----------

    @Test
    @DisplayName("迟到的旧样本：upsert 命中 0 行，计为拒收且不写目标状态")
    void lateSampleIsDiscarded() {
        giveHost(1L, 5L);

        start(target -> success("ONLINE"));

        when(monitorCollectionStateMapper.upsertObservation(any())).thenReturn(0);

        CollectionRunSummary summary = service.collectOnce();

        assertThat(summary.discarded()).isEqualTo(1);
        assertThat(summary.applied()).isZero();
        verify(monitorCollectionStateMapper).incrementDiscarded(CollectionTargetType.HOST, 1L, T0);
        verify(assetHostMapper, never()).updateHostStatus(anyLong(), any());
        verify(assetHostMapper, never()).resetHostStatusIfObserved(anyLong(), any(), any());
    }

    @Test
    @DisplayName("目标已删除：写回前身份校验失败，样本拒收且不落库")
    void deletedTargetIsDiscarded() {
        giveHost(1L, 5L);

        start(target -> success("ONLINE"));

        when(assetHostMapper.selectForUpdate(1L)).thenReturn(null);

        CollectionRunSummary summary = service.collectOnce();

        assertThat(summary.discarded()).isEqualTo(1);
        verify(monitorCollectionStateMapper, never()).upsertObservation(any());
        verify(monitorCollectionStateMapper).incrementDiscarded(CollectionTargetType.HOST, 1L, T0);
    }

    @Test
    @DisplayName("SSH 配置被换成另一条：采样对象已变，样本拒收")
    void replacedConfigIsDiscarded() {
        giveHost(1L, 5L);

        start(target -> success("ONLINE"));

        when(assetHostSshConfigMapper.selectEnabledByHostIdForUpdate(1L))
                .thenReturn(List.of(sshConfig(6L, 1L)));

        CollectionRunSummary summary = service.collectOnce();

        assertThat(summary.discarded()).isEqualTo(1);
        verify(monitorCollectionStateMapper, never()).upsertObservation(any());
    }

    @Test
    @DisplayName("同一条 SSH 配置在探测期间被改过：id 相同但版本不同，旧样本拒收")
    void editedConfigIsDiscarded() {
        giveHost(1L, 5L);

        start(target -> success("ONLINE"));

        // id 不变、updated_at 变新 —— 只比 id 的旧实现会把这个样本放过去
        AssetHostSshConfig edited = sshConfig(5L, 1L);
        edited.setUpdatedAt(T0.plusSeconds(60));
        when(assetHostSshConfigMapper.selectEnabledByHostIdForUpdate(1L)).thenReturn(List.of(edited));

        CollectionRunSummary summary = service.collectOnce();

        assertThat(summary.discarded()).as("配置版本变了，探测结论不再适用于当前对象").isEqualTo(1);
        verify(monitorCollectionStateMapper, never()).upsertObservation(any());
    }

    @Test
    @DisplayName("SSH 配置被禁用/删除：启用集合里已无此条，旧样本拒收")
    void disabledConfigIsDiscarded() {
        giveHost(1L, 5L);

        start(target -> success("ONLINE"));

        when(assetHostSshConfigMapper.selectEnabledByHostIdForUpdate(1L)).thenReturn(List.of());

        CollectionRunSummary summary = service.collectOnce();

        assertThat(summary.discarded()).isEqualTo(1);
        verify(monitorCollectionStateMapper, never()).upsertObservation(any());
    }

    @Test
    @DisplayName("主机在探测期间被改址：基于旧地址采到的样本不得落到新地址的主机上")
    void reAddressedHostDiscardsLateSample() {
        giveHost(1L, 5L);

        start(target -> success("ONLINE"));

        AssetHost moved = host(1L);
        moved.setIp("192.0.2.72");
        when(assetHostMapper.selectForUpdate(1L)).thenReturn(moved);

        CollectionRunSummary summary = service.collectOnce();

        assertThat(summary.discarded()).isEqualTo(1);
        verify(assetHostMapper, never()).updateHostStatus(anyLong(), any());
    }

    @Test
    @DisplayName("实例已改绑到别的主机：身份校验失败，样本拒收")
    void instanceReboundToAnotherHostIsDiscarded() {
        giveInstance(200L, 1L, PROCESS, 5L);

        start(target -> success("RUNNING", "UP"));

        when(runtimeServiceInstanceMapper.selectForUpdate(200L))
                .thenReturn(instance(200L, 99L, PROCESS));

        CollectionRunSummary summary = service.collectOnce();

        assertThat(summary.discarded()).isEqualTo(1);
        verify(monitorCollectionStateMapper, never()).upsertObservation(any());
        verify(runtimeServiceInstanceMapper, never()).updateObservedStatus(anyLong(), any(), any());
    }

    @Test
    @DisplayName("实例类型在探测期间被改：同一实例已不是被探测的那个类型，样本拒收")
    void retypedInstanceDiscardsLateSample() {
        giveInstance(200L, 1L, PROCESS, 5L);

        start(target -> success("RUNNING", "UP"));

        when(runtimeServiceInstanceMapper.selectForUpdate(200L))
                .thenReturn(instance(200L, 1L, "DOCKER"));

        CollectionRunSummary summary = service.collectOnce();

        assertThat(summary.discarded()).isEqualTo(1);
        verify(runtimeServiceInstanceMapper, never()).updateObservedStatus(anyLong(), any(), any());
    }

    // ---------- 超时与隔离 ----------

    @Test
    @DisplayName("单目标超时：判为 NETWORK_TIMEOUT，另一个目标照常完成")
    void timeoutDoesNotBlockOtherTargets() {
        giveHost(1L, 5L);
        giveHost(2L, 6L);

        start(target -> target.targetId() == 1L ? hangUntilInterrupted() : success("ONLINE"));
        properties.setSingleTargetTimeoutMs(200L);

        CollectionRunSummary summary = service.collectOnce();

        assertThat(summary.applied()).as("另一个目标不受超时影响").isEqualTo(1);
        verify(assetHostMapper).updateHostStatus(2L, "ONLINE");
        verify(assetHostMapper, never()).updateHostStatus(1L, "ONLINE");

        MonitorCollectionState timedOut = capturedStateFor(CollectionTargetType.HOST, 1L);
        assertThat(timedOut.getLastResult()).isEqualTo(CollectionResult.FAILED.name());
        assertThat(timedOut.getLastErrorCategory()).isEqualTo(CollectionErrorCategory.NETWORK_TIMEOUT.name());
        assertThat(timedOut.getLastErrorSummary()).as("超时摘要必须写明阈值，便于现场定位").contains("200ms");
    }

    @Test
    @DisplayName("截止时刻按各任务自己的开始时刻算：先等慢任务不会给后面的任务多送一份预算")
    void perTargetDeadlineIsNotExtendedByEarlierWaits() {
        giveHost(1L, 5L);
        giveHost(2L, 6L);
        properties.setSingleTargetTimeoutMs(200L);

        // 1 号永久卡住，2 号实际要跑 600ms —— 远超它自己的 200ms 预算。
        // 旧实现逐个 future.get(200ms)：等完 1 号已是 t≈200ms，再给 2 号一份完整的 200ms，
        // 于是 2 号跑到 600ms 仍会被接受；绝对截止把它判为超时。
        start(target -> target.targetId() == 1L ? hangUntilInterrupted() : slowSuccess(600L));

        CollectionRunSummary summary = service.collectOnce();

        assertThat(summary.applied()).as("两个目标都没拿到可用结论").isZero();
        verify(assetHostMapper, never()).updateHostStatus(2L, "ONLINE");

        MonitorCollectionState overrun = capturedStateFor(CollectionTargetType.HOST, 2L);
        assertThat(overrun.getLastResult()).isEqualTo(CollectionResult.FAILED.name());
        assertThat(overrun.getLastErrorCategory())
                .as("超过自身预算的探测不得被当成本轮有效结果")
                .isEqualTo(CollectionErrorCategory.NETWORK_TIMEOUT.name());
    }

    @Test
    @DisplayName("探测卡死不恢复：任务被拒绝而不是无限排队，卡住的线程也不会一批批堆积")
    void stuckProbesAreRejectedInsteadOfQueued() {
        for (long id = 1L; id <= 3L; id++) {
            giveHost(id, 100L + id);
        }
        properties.setMaxConcurrency(1);
        properties.setSingleTargetTimeoutMs(150L);

        // 不可中断的观测器：模拟"取消只是调用方不再等、下层照跑"的情形
        start(target -> hangIgnoringInterrupt(5_000L));

        CollectionRunSummary summary = service.collectOnce();

        assertThat(summary.applied()).isZero();
        assertThat(observer.probed())
                .as("只有一个探测真正拿到线程；其余目标必须被拒绝，而不是把队列越堆越长")
                .hasSize(1);

        MonitorCollectionState rejected = capturedStateFor(CollectionTargetType.HOST, 3L);
        assertThat(rejected.getLastResult()).isEqualTo(CollectionResult.FAILED.name());
        assertThat(rejected.getLastErrorCategory())
                .as("没被调度必须显式留痕，否则该目标会一直挂着旧状态冒充新鲜")
                .isEqualTo(CollectionErrorCategory.PROBE_SCHEDULING_REJECTED.name());
    }

    @Test
    @DisplayName("长期卡死：连续多轮之后探测线程与队列都不随轮数增长，收尾仍然有界")
    void stuckProbesDoNotAccumulateAcrossRounds() {
        for (long id = 1L; id <= 4L; id++) {
            giveHost(id, 100L + id);
        }
        properties.setMaxConcurrency(1);
        properties.setSingleTargetTimeoutMs(150L);

        // 有意用"不可中断"的卡死：这正是审查复现的那类下层行为——取消只让调用方不再等，
        // 线程实际一直被占着，于是"每轮再压一层"就成了线程与队列的无限堆积。
        start(target -> hangIgnoringInterrupt(4_000L));

        long baseline = liveProbeThreads();
        for (int round = 1; round <= 3; round++) {
            CollectionRunSummary summary = service.collectOnce();

            assertThat(summary.total()).as("第 %d 轮仍要把每个目标都算进来", round).isEqualTo(4);
            assertThat(liveProbeThreads() - baseline)
                    .as("第 %d 轮之后存活的探测线程不得超过并发上限；线程数随轮数增长就是无限堆积", round)
                    .isLessThanOrEqualTo(1L);
        }

        assertThat(observer.probed())
                .as("卡住的探测占着唯一线程，后续轮次不应再放新探测进去")
                .hasSize(1);

        MonitorCollectionState neverScheduled = capturedStateFor(CollectionTargetType.HOST, 4L);
        assertThat(neverScheduled.getLastErrorCategory())
                .as("进不去线程的目标必须显式留痕，否则会挂着旧状态冒充新鲜")
                .isEqualTo(CollectionErrorCategory.PROBE_SCHEDULING_REJECTED.name());

        service.shutdown();
        assertThat(awaitProbeThreadsAtMost(baseline, 6_000L))
                .as("卡死结束后探测线程必须回到基线：关闭有界，不留漏网线程")
                .isTrue();
    }

    @Test
    @DisplayName("观测器抛异常：归类为 PROBE_ERROR，不外抛，其余目标不受影响")
    void observerExceptionIsCategorized() {
        giveHost(1L, 5L);
        giveHost(2L, 6L);

        start(target -> {
            if (target.targetId() == 1L) {
                throw new IllegalStateException("channel closed\n细节第二行不该落库");
            }
            return success("ONLINE");
        });

        CollectionRunSummary summary = service.collectOnce();

        assertThat(summary.applied()).isEqualTo(1);
        MonitorCollectionState failed = capturedStateFor(CollectionTargetType.HOST, 1L);
        assertThat(failed.getLastErrorCategory()).isEqualTo(CollectionErrorCategory.PROBE_ERROR.name());
        assertThat(failed.getLastErrorSummary())
                .as("异常摘要必须压成单行，不能把多行文本落库")
                .isEqualTo("channel closed");
    }

    @Test
    @DisplayName("单个目标的写回异常：事务回滚、计 writeFailed，其余目标继续写回")
    void writeBackFailureDoesNotStopLaterTargets() {
        giveHost(1L, 5L);
        giveHost(2L, 6L);

        start(target -> success("ONLINE"));

        when(monitorCollectionStateMapper.upsertObservation(any()))
                .thenAnswer(call -> {
                    MonitorCollectionState state = call.getArgument(0);
                    if (Long.valueOf(1L).equals(state.getTargetId())) {
                        throw new IllegalStateException("模拟目标 1 的落库失败");
                    }
                    return 1;
                });

        CollectionRunSummary summary = service.collectOnce();

        assertThat(summary.writeFailed()).as("失败目标必须留痕，不能静默吞掉").isEqualTo(1);
        assertThat(summary.total()).as("失败的目标不能终止整轮").isEqualTo(2);
        assertThat(summary.applied()).as("后续目标照常写回").isEqualTo(1);
        verify(assetHostMapper).updateHostStatus(2L, "ONLINE");
        verify(assetHostMapper, never()).updateHostStatus(1L, "ONLINE");
    }

    @Test
    @DisplayName("并发上限：同时进入观测器的目标数不超过配置值，且目标一个不落")
    void concurrencyIsCappedAtConfiguredLimit() {
        for (long id = 1; id <= 7; id++) {
            giveHost(id, 100L + id);
        }

        start(target -> {
            sleepQuietly(50L);
            return success("ONLINE");
        });

        CollectionRunSummary summary = service.collectOnce();

        assertThat(summary.total()).isEqualTo(7);
        assertThat(summary.applied()).isEqualTo(7);
        assertThat(observer.probed()).as("目标是分批提交的，但一个都不能漏").hasSize(7);
        assertThat(observer.peakConcurrency())
                .as("同时在跑的探测数不得超过 max-concurrency=%d", properties.getMaxConcurrency())
                .isLessThanOrEqualTo(properties.getMaxConcurrency());
        assertThat(observer.peakConcurrency())
                .as("批内应当真正并行，否则'并发上限'这件事没有被测到")
                .isGreaterThan(1);
    }

    // ---------- 过期结算 ----------

    @Test
    @DisplayName("过期结算：按 last_success_at 选候选，标记与条件复位在同一次调用里完成")
    void expiredTargetsAreReset() {
        start(target -> success("ONLINE"));

        when(monitorCollectionStateMapper.selectExpiredTargets(any()))
                .thenReturn(List.of(stateRow(CollectionTargetType.HOST, 5L, 4, "ONLINE", "UNKNOWN")));
        when(monitorCollectionStateMapper.selectByTargetForUpdate(CollectionTargetType.HOST, 5L))
                .thenReturn(lockedExpiredRow(CollectionTargetType.HOST, 5L, "ONLINE", "UNKNOWN"));
        when(monitorCollectionStateMapper.markExpiredOne(eq(CollectionTargetType.HOST), eq(5L), any()))
                .thenReturn(1);
        when(assetHostMapper.resetHostStatusIfObserved(5L, "ONLINE", "UNKNOWN")).thenReturn(1);

        CollectionRunSummary summary = service.collectOnce();

        assertThat(summary.total()).as("过期目标不在本轮的枚举范围里").isZero();
        assertThat(summary.expired()).isEqualTo(1);
        verify(assetHostMapper).resetHostStatusIfObserved(5L, "ONLINE", "UNKNOWN");
    }

    @Test
    @DisplayName("过期结算用当前时刻减去过期阈值作为分界")
    void expiryCutoffUsesStaleThreshold() {
        start(target -> success("ONLINE"));

        service.collectOnce();

        ArgumentCaptor<OffsetDateTime> cutoff = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(monitorCollectionStateMapper).selectExpiredTargets(cutoff.capture());
        assertThat(cutoff.getValue())
                .as("分界 = 假时钟当前时刻 - stale-threshold-ms")
                .isEqualTo(T0.minusSeconds(90));
    }

    @Test
    @DisplayName("过期目标从未成功观测过：没有可复位的历史值，不计入复位数")
    void expiredTargetWithoutObservedStatusIsNotReset() {
        start(target -> success("ONLINE"));

        when(monitorCollectionStateMapper.selectExpiredTargets(any()))
                .thenReturn(List.of(stateRow(CollectionTargetType.HOST, 5L, 2, null, null)));
        // 锁内读到的当前行确实该结算，但没有"我们写入过的值"可复位 —— 覆盖的是复位前的空值守卫，
        // 不是"因为拿不到当前行所以什么都没发生"
        when(monitorCollectionStateMapper.selectByTargetForUpdate(CollectionTargetType.HOST, 5L))
                .thenReturn(lockedExpiredRow(CollectionTargetType.HOST, 5L, null, null));
        when(monitorCollectionStateMapper.markExpiredOne(eq(CollectionTargetType.HOST), eq(5L), any()))
                .thenReturn(1);

        CollectionRunSummary summary = service.collectOnce();

        assertThat(summary.expired()).isZero();
        verify(assetHostMapper, never()).resetHostStatusIfObserved(anyLong(), any(), any());
    }

    @Test
    @DisplayName("候选已被别的执行者标记：markExpiredOne 命中 0 行，本轮不做复位也不报错")
    void alreadySettledExpiryIsSkipped() {
        start(target -> success("ONLINE"));

        when(monitorCollectionStateMapper.selectExpiredTargets(any()))
                .thenReturn(List.of(stateRow(CollectionTargetType.HOST, 5L, 2, "ONLINE", "UNKNOWN")));
        when(monitorCollectionStateMapper.selectByTargetForUpdate(CollectionTargetType.HOST, 5L))
                .thenReturn(lockedExpiredRow(CollectionTargetType.HOST, 5L, "ONLINE", "UNKNOWN"));
        when(monitorCollectionStateMapper.markExpiredOne(eq(CollectionTargetType.HOST), eq(5L), any()))
                .thenReturn(0);

        CollectionRunSummary summary = service.collectOnce();

        assertThat(summary.expired()).isZero();
        verify(assetHostMapper, never()).resetHostStatusIfObserved(anyLong(), any(), any());
    }

    @Test
    @DisplayName("过期结算里某个目标复位失败：只记日志，不影响其余过期目标，下一轮仍可重试")
    void expiryFailureDoesNotStopRemainingTargets() {
        start(target -> success("ONLINE"));

        when(monitorCollectionStateMapper.selectExpiredTargets(any())).thenReturn(List.of(
                stateRow(CollectionTargetType.HOST, 5L, 2, "ONLINE", "UNKNOWN"),
                stateRow(CollectionTargetType.HOST, 6L, 2, "ONLINE", "UNKNOWN")));
        when(monitorCollectionStateMapper.selectByTargetForUpdate(CollectionTargetType.HOST, 5L))
                .thenReturn(lockedExpiredRow(CollectionTargetType.HOST, 5L, "ONLINE", "UNKNOWN"));
        when(monitorCollectionStateMapper.selectByTargetForUpdate(CollectionTargetType.HOST, 6L))
                .thenReturn(lockedExpiredRow(CollectionTargetType.HOST, 6L, "ONLINE", "UNKNOWN"));
        when(monitorCollectionStateMapper.markExpiredOne(eq(CollectionTargetType.HOST), anyLong(), any()))
                .thenReturn(1);
        when(assetHostMapper.resetHostStatusIfObserved(5L, "ONLINE", "UNKNOWN"))
                .thenThrow(new IllegalStateException("模拟复位失败"));
        when(assetHostMapper.resetHostStatusIfObserved(6L, "ONLINE", "UNKNOWN")).thenReturn(1);

        CollectionRunSummary summary = service.collectOnce();

        assertThat(summary.expired()).as("失败的目标不算复位成功，后面的目标不受影响").isEqualTo(1);
        verify(assetHostMapper).resetHostStatusIfObserved(6L, "ONLINE", "UNKNOWN");
    }

    // ---------- 生命周期 ----------

    @Test
    @DisplayName("停机后不再发起任何一轮：不枚举、不探测、不写回、不结算过期")
    void shutdownStopsFurtherRounds() {
        giveHost(1L, 5L);

        start(target -> success("ONLINE"));

        service.shutdown();
        CollectionRunSummary summary = service.collectOnce();

        assertThat(summary).isEqualTo(CollectionRunSummary.empty());
        assertThat(observer.probed()).isEmpty();
        verify(assetHostService, never()).list();
        verify(monitorCollectionStateMapper, never()).upsertObservation(any());
        verify(monitorCollectionStateMapper, never()).selectExpiredTargets(any());
    }

    @Test
    @DisplayName("停机做有界等待：卡住的探测不会让 shutdown 无限期阻塞")
    void shutdownIsBoundedAndUnblocksHangingProbe() {
        giveHost(1L, 5L);

        CountDownLatch entered = new CountDownLatch(1);
        start(target -> {
            entered.countDown();
            return hangUntilInterrupted();
        });

        Thread round = new Thread(() -> service.collectOnce(), "test-collection-round");
        round.setDaemon(true);
        round.start();
        awaitOrFail(entered, "探测未在 5s 内进入观测器");

        long startedAt = System.nanoTime();
        service.shutdown();
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

        assertThat(elapsedMs)
                .as("shutdown 只等 single-target-timeout-ms，不做无限期等待")
                .isLessThan(10_000L);

        joinQuietly(round);
        assertThat(round.isAlive()).as("停机后本轮必须能收尾，线程不能挂住").isFalse();
    }

    // ---------- fixtures ----------

    private final List<AssetHost> hostWorld = new ArrayList<>();
    private final List<RuntimeServiceInstance> instanceWorld = new ArrayList<>();
    private final Map<Long, AssetHostSshConfig> configByHost = new HashMap<>();

    /** 登记一台主机及其启用中的 SSH 连接配置。 */
    private void giveHost(long hostId, long configId) {
        hostWorld.add(host(hostId));
        configByHost.put(hostId, sshConfig(configId, hostId));
    }

    /** 登记一台主机，但不给它任何启用中的连接配置。 */
    private void giveHostWithoutConfig(long hostId) {
        hostWorld.add(host(hostId));
    }

    /**
     * 登记一个实例，并给它所属主机配一条启用中的连接配置。
     *
     * <p>所属主机**不**因此成为采集目标——测试要看的只是这个实例，主机混进来会把
     * "本轮有几个目标"这件事搅浑。该主机行仍然可被写回查到，但它的 ip 与 hostname 都为空，
     * 于是"枚举侧解析出的地址"与"写回侧比对到的地址"同为 null，属于一致状态。
     * 真实的主机+实例同场场景由 {@code hostAndInstanceAreCollectedInOneRound} 覆盖。
     */
    private void giveInstance(long instanceId, long hostId, String instanceType, long configId) {
        instanceWorld.add(instance(instanceId, hostId, instanceType));
        configByHost.put(hostId, sshConfig(configId, hostId));
    }

    /** 登记一个实例，其所属主机没有启用中的连接配置。 */
    private void giveInstanceWithoutConfig(long instanceId, long hostId, String instanceType) {
        instanceWorld.add(instance(instanceId, hostId, instanceType));
    }

    /** 观测器只认 PROCESS 实例；主机目标一律支持。 */
    private void start(Function<CollectionTargetRef, CollectionObservation> behaviour) {
        start(target -> CollectionTargetType.HOST.equals(target.targetType())
                || PROCESS.equals(target.instanceType()), behaviour);
    }

    /**
     * 落地整个测试世界，然后装配被测服务。
     *
     * <p>世界只在 {@code start} 时一次性写成桩：逐条 {@code give*} 只登记目标，不会互相覆盖。
     * 反过来，若每条 {@code give*} 各自去桩 {@code list()}，后一条会把前一条的目标挤掉，
     * 测试就成了"只测了最后登记的那个目标"。
     *
     * <p>默认桩走"一切正常"：写回靠行锁读到的目标行与采样时一致（因此身份/地址校验通过）、
     * 配置版本一致、采集行写入成功、目标状态写入成功。需要构造迟到样本、配置被改、
     * 条件复位落空、目标被删这类现场的测试，在 {@code start} 之后再覆盖对应桩。
     */
    private void start(Predicate<CollectionTargetRef> supports,
                       Function<CollectionTargetRef, CollectionObservation> behaviour) {
        when(assetHostService.list()).thenReturn(List.copyOf(hostWorld));
        when(runtimeServiceInstanceService.list()).thenReturn(List.copyOf(instanceWorld));
        when(assetHostSshConfigService.list(anyWrapper())).thenReturn(List.copyOf(configByHost.values()));
        for (AssetHost target : hostWorld) {
            when(assetHostService.getById(target.getId())).thenReturn(target);
        }
        for (RuntimeServiceInstance target : instanceWorld) {
            when(runtimeServiceInstanceService.getById(target.getId())).thenReturn(target);
        }
        configByHost.forEach((hostId, config) ->
                when(assetHostSshConfigService.getEnabledByHostId(hostId)).thenReturn(config));

        // 写回侧读的是"行锁版本"的目标行与启用配置；与枚举侧同源，默认两者一致。
        when(assetHostMapper.selectForUpdate(anyLong()))
                .thenAnswer(call -> hostRowFor(call.getArgument(0)));
        when(runtimeServiceInstanceMapper.selectForUpdate(anyLong()))
                .thenAnswer(call -> instanceRowFor(call.getArgument(0)));
        when(assetHostSshConfigMapper.selectEnabledByHostIdForUpdate(anyLong()))
                .thenAnswer(call -> enabledConfigsFor(call.getArgument(0)));

        when(monitorCollectionStateMapper.upsertObservation(any())).thenReturn(1);
        when(assetHostMapper.updateHostStatus(anyLong(), any())).thenReturn(1);
        when(runtimeServiceInstanceMapper.updateObservedStatus(anyLong(), any(), any())).thenReturn(1);

        observer = new FakeObserver(OBSERVER, supports, behaviour);
        writeBackService = new CollectionWriteBackService(assetHostMapper, runtimeServiceInstanceMapper,
                monitorCollectionStateMapper, assetHostSshConfigMapper, properties);
        service = new HostInstanceCollectionService(assetHostService, assetHostSshConfigService,
                runtimeServiceInstanceService, monitorCollectionStateMapper, writeBackService,
                List.of(observer), properties, FIXED_CLOCK);
    }

    /** 写回时"库里那一行"主机：本轮枚举里有的就是它本身，否则给一条解析不出地址的主机行。 */
    private AssetHost hostRowFor(Long hostId) {
        return hostWorld.stream()
                .filter(host -> host.getId().equals(hostId))
                .findFirst()
                .orElseGet(() -> addressLessHost(hostId));
    }

    private RuntimeServiceInstance instanceRowFor(Long instanceId) {
        return instanceWorld.stream()
                .filter(instance -> instance.getId().equals(instanceId))
                .findFirst()
                .orElse(null);
    }

    private List<AssetHostSshConfig> enabledConfigsFor(Long hostId) {
        AssetHostSshConfig config = configByHost.get(hostId);
        return config == null ? List.of() : List.of(config);
    }

    /**
     * MyBatis-Plus 的 {@code IService} 同时声明了 {@code list(Wrapper)} 与 {@code list(IPage)}，
     * 裸 {@code any()} 会被判为二义；用固定返回类型把重载定死。
     */
    private static <T> Wrapper<T> anyWrapper() {
        return any();
    }

    /** {@link CollectionObservation} 没有 success 工厂，成功观测按记录本体构造。 */
    private static CollectionObservation success(String observedStatus) {
        return success(observedStatus, "UNKNOWN");
    }

    private static CollectionObservation success(String observedStatus, String observedHealth) {
        return new CollectionObservation(CollectionResult.SUCCESS, null, null,
                observedStatus, observedHealth, T0, OBSERVER, true);
    }

    private MonitorCollectionState capturedStateFor(String targetType, Long targetId) {
        ArgumentCaptor<MonitorCollectionState> captor = ArgumentCaptor.forClass(MonitorCollectionState.class);
        verify(monitorCollectionStateMapper, atLeastOnce()).upsertObservation(captor.capture());
        return captor.getAllValues().stream()
                .filter(state -> targetType.equals(state.getTargetType()) && targetId.equals(state.getTargetId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "没有找到目标 " + targetType + "/" + targetId + " 的写回"));
    }

    private static AssetHost host(Long id) {
        AssetHost host = new AssetHost();
        host.setId(id);
        host.setHostname("host-" + id);
        host.setIp("10.9.9." + id);
        host.setHostStatus("UNKNOWN");
        host.setAgentStatus("UNKNOWN");
        return host;
    }

    /** 存在但解析不出地址的主机行：ip 与 hostname 都为空（{@link CollectionAddress} 因此返回 null）。 */
    private static AssetHost addressLessHost(Long id) {
        AssetHost host = new AssetHost();
        host.setId(id);
        return host;
    }

    private static RuntimeServiceInstance instance(Long id, Long hostId, String instanceType) {
        RuntimeServiceInstance instance = new RuntimeServiceInstance();
        instance.setId(id);
        instance.setHostId(hostId);
        instance.setInstanceName("instance-" + id);
        instance.setInstanceType(instanceType);
        instance.setInstanceStatus("UNKNOWN");
        instance.setHealthStatus("UNKNOWN");
        return instance;
    }

    private static AssetHostSshConfig sshConfig(Long id, Long hostId) {
        AssetHostSshConfig config = new AssetHostSshConfig();
        config.setId(id);
        config.setHostId(hostId);
        config.setEnabled(Boolean.TRUE);
        config.setUpdatedAt(T0);
        return config;
    }

    private static MonitorCollectionState stateRow(String targetType, Long targetId, int failureCount,
                                                   String observedStatus, String observedHealth) {
        MonitorCollectionState state = new MonitorCollectionState();
        state.setTargetType(targetType);
        state.setTargetId(targetId);
        state.setConsecutiveFailureCount(failureCount);
        state.setLastObservedStatus(observedStatus);
        state.setLastObservedHealth(observedHealth);
        return state;
    }

    /**
     * 结算路径在目标锁内读到的**当前**采集状态行：成功观测早于过期分界（T0 - 90s），
     * 且尚未被判为 EXPIRED —— 也就是"确实还该结算"的那一行。
     *
     * <p>过期与否只由这一行决定，不由 {@code selectExpiredTargets} 选出的候选快照决定；
     * 因此过期用例必须把这个锁内读取也桩上，否则拿到的是 null，整条链路会**静默什么都不做**，
     * 断言就会因为"没发生"而恒真。
     */
    private static MonitorCollectionState lockedExpiredRow(String targetType, Long targetId,
                                                           String observedStatus, String observedHealth) {
        MonitorCollectionState state = stateRow(targetType, targetId, 0, observedStatus, observedHealth);
        state.setLastSuccessAt(T0.minusSeconds(120));
        state.setLastResult(CollectionResult.SUCCESS.name());
        return state;
    }

    /** 一直阻塞到被中断，用来构造"单目标超时"与"停机时仍在探测"两种现场。 */
    private static CollectionObservation hangUntilInterrupted() {
        try {
            Thread.sleep(60_000L);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return CollectionObservation.failure(CollectionErrorCategory.NETWORK_TIMEOUT, "被中断", OBSERVER, T0);
        }
        return success("ONLINE");
    }

    /** 慢但会成功的探测：用来验证"超过自身预算就不该被接受"。 */
    private static CollectionObservation slowSuccess(long millis) {
        sleepQuietly(millis);
        return success("ONLINE");
    }

    /** 不可中断的卡死探测：模拟"取消只让调用方不再等、下层照跑"的那种实现。 */
    private static CollectionObservation hangIgnoringInterrupt(long millis) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (System.nanoTime() < deadline) {
            try {
                Thread.sleep(50L);
            } catch (InterruptedException ignored) {
                // 刻意吞掉：本测试要的是"被取消之后仍然占着线程"的下层行为
            }
        }
        return success("ONLINE");
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    /** 当前存活的探测线程数：池线程名统一为 {@code collection-probe-N}。 */
    private static long liveProbeThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(Thread::isAlive)
                .filter(thread -> thread.getName().startsWith("collection-probe-"))
                .count();
    }

    /** 等到探测线程回落到基线以内；返回是否等到了。 */
    private static boolean awaitProbeThreadsAtMost(long baseline, long timeoutMillis) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadline) {
            if (liveProbeThreads() <= baseline) {
                return true;
            }
            sleepQuietly(100L);
        }
        return liveProbeThreads() <= baseline;
    }

    private static void awaitOrFail(CountDownLatch latch, String message) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError(message);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(message, exception);
        }
    }

    private static void joinQuietly(Thread thread) {
        try {
            thread.join(5_000L);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("等待本轮采集收尾时被中断", exception);
        }
    }

    /** 可控观测器：支持哪些目标、返回什么、并发峰值，全部由测试决定。 */
    private static final class FakeObserver implements CollectionTargetObserver {

        private final String observerType;
        private final Predicate<CollectionTargetRef> supports;
        private final Function<CollectionTargetRef, CollectionObservation> behaviour;
        private final List<Long> probed = new CopyOnWriteArrayList<>();
        private final AtomicInteger active = new AtomicInteger();
        private final AtomicInteger peak = new AtomicInteger();

        private FakeObserver(String observerType, Predicate<CollectionTargetRef> supports,
                            Function<CollectionTargetRef, CollectionObservation> behaviour) {
            this.observerType = observerType;
            this.supports = supports;
            this.behaviour = behaviour;
        }

        @Override
        public String observerType() {
            return observerType;
        }

        @Override
        public boolean supports(CollectionTargetRef target) {
            return supports.test(target);
        }

        @Override
        public CollectionObservation observe(CollectionTargetRef target) {
            probed.add(target.targetId());
            int concurrent = active.incrementAndGet();
            peak.accumulateAndGet(concurrent, Math::max);
            try {
                return behaviour.apply(target);
            } finally {
                active.decrementAndGet();
            }
        }

        List<Long> probed() {
            return List.copyOf(probed);
        }

        int peakConcurrency() {
            return peak.get();
        }
    }
}
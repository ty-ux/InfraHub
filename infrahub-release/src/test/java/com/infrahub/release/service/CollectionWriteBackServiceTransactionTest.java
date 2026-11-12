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
import com.infrahub.release.support.CollectionResult;
import com.infrahub.release.support.CollectionTargetType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 采集写回的事务边界与原子性测试：用真实 Spring 事务代理（CGLIB）+ 只记数的假事务管理器验证，
 * **不连接真实 DB / SSH / HTTP**。
 *
 * <p>这里必须是真的容器，因为要防的是"@Transactional 静默失效"这一整类缺陷：
 * 同类自调用、非懒加载自注入都会让事务注解不生效，而纯 Mockito 单测看不出来。
 *
 * <p><b>本测试验证的是调用顺序与事务边界，不验证真实数据库协议。</b>
 * 真实 MyBatis 一级缓存、行锁阻塞、提交可见性与并发交错由真实 PostgreSQL 验证
 * （{@code MonitorCollectionStateMapperPostgresTest} 的并发用例，独立复验由 Codex 负责）。
 *
 * <p>对应审查项：
 * <ul>
 *   <li><b>R2</b>：写采集表与写目标表必须在**同一个**短事务里。旧实现是两次独立提交，
 *       交错样本能让"采集表说 STOPPED、目标表说 RUNNING"同时成立。
 *       这里断言目标表写入异常时采集表写入一并回滚，且目标表写入发生在提交之前。</li>
 *   <li><b>R2 复位可重试</b>：{@code settleExpiredTarget} 的"标记 EXPIRED"与"复位目标状态"
 *       同事务；复位失败则标记一并回滚，该目标下一轮仍是候选，不会被永久跳过。</li>
 *   <li><b>R6</b>：每个目标一个短事务。整批不是一个事务，一个目标失败只回滚它自己，
 *       后续目标照常提交。</li>
 * </ul>
 *
 * @see docs/release/v2-host-instance-status-collection-contract.md 第 5.4 / 5.5 / 6 节
 */
class CollectionWriteBackServiceTransactionTest {

    private static final OffsetDateTime T0 = OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    private static final Clock FIXED_CLOCK = Clock.fixed(T0.toInstant(), ZoneOffset.UTC);
    private static final String OBSERVER = "SSH_PROCESS";
    private static final String EXPECTED_HEALTH = "UP";
    private static final long HOST_ID = 21L;
    private static final long INSTANCE_ID = 31L;

    private static final TrackingTransactionManager TRANSACTIONS = new TrackingTransactionManager();
    private static final AssetHostMapper ASSET_HOST_MAPPER = mock(AssetHostMapper.class);
    private static final RuntimeServiceInstanceMapper INSTANCE_MAPPER = mock(RuntimeServiceInstanceMapper.class);
    private static final MonitorCollectionStateMapper STATE_MAPPER = mock(MonitorCollectionStateMapper.class);
    private static final AssetHostSshConfigMapper SSH_CONFIG_MAPPER = mock(AssetHostSshConfigMapper.class);
    private static final AssetHostService ASSET_HOST_SERVICE = mock(AssetHostService.class);
    private static final AssetHostSshConfigService SSH_CONFIG_SERVICE = mock(AssetHostSshConfigService.class);
    private static final RuntimeServiceInstanceService INSTANCE_SERVICE = mock(RuntimeServiceInstanceService.class);
    private static final CollectionTargetObserver OBSERVER_STUB = mock(CollectionTargetObserver.class);

    /**
     * 只记数的事务管理器：doBegin/doCommit/doRollback 计数，不碰任何真实数据源。
     *
     * <p>与 {@code ApprovalRequestServiceTransactionTest} 同构，语义细节见该类的注释。
     */
    static class TrackingTransactionManager extends AbstractPlatformTransactionManager {

        final AtomicInteger begun = new AtomicInteger();
        final AtomicInteger committed = new AtomicInteger();
        final AtomicInteger rolledBack = new AtomicInteger();
        private final ThreadLocal<Boolean> rollbackOnly = ThreadLocal.withInitial(() -> Boolean.FALSE);

        void resetCounters() {
            begun.set(0);
            committed.set(0);
            rolledBack.set(0);
        }

        boolean isRollbackOnly() {
            return Boolean.TRUE.equals(rollbackOnly.get());
        }

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected boolean isExistingTransaction(Object transaction) {
            return TransactionSynchronizationManager.isActualTransactionActive();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            begun.incrementAndGet();
            rollbackOnly.set(Boolean.FALSE);
        }

        @Override
        protected void doSetRollbackOnly(DefaultTransactionStatus status) {
            rollbackOnly.set(Boolean.TRUE);
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            if (isRollbackOnly()) {
                throw new UnexpectedRollbackException("事务已被标记为 rollback-only，禁止提交");
            }
            committed.incrementAndGet();
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            rolledBack.incrementAndGet();
        }
    }

    @Configuration
    @EnableTransactionManagement(proxyTargetClass = true)
    static class TestConfig {

        @Bean
        PlatformTransactionManager transactionManager() {
            return TRANSACTIONS;
        }

        @Bean
        AssetHostMapper assetHostMapper() {
            return ASSET_HOST_MAPPER;
        }

        @Bean
        RuntimeServiceInstanceMapper runtimeServiceInstanceMapper() {
            return INSTANCE_MAPPER;
        }

        @Bean
        MonitorCollectionStateMapper monitorCollectionStateMapper() {
            return STATE_MAPPER;
        }

        @Bean
        AssetHostSshConfigMapper assetHostSshConfigMapper() {
            return SSH_CONFIG_MAPPER;
        }

        @Bean
        HostInstanceCollectionProperties hostInstanceCollectionProperties() {
            HostInstanceCollectionProperties properties = new HostInstanceCollectionProperties();
            properties.setEnabled(true);
            properties.setPeriodMs(30_000L);
            properties.setSingleTargetTimeoutMs(3_000L);
            properties.setMaxConcurrency(4);
            properties.setFailureThreshold(3);
            properties.setStaleThresholdMs(90_000L);
            return properties;
        }

        /**
         * 写回服务用**真实实现**：只有真实 Bean 上的 {@code @Transactional} 才会经过事务代理。
         * 这正是本测试要验的东西，因此绝不能用 mock 顶替。
         */
        @Bean
        CollectionWriteBackService collectionWriteBackService() {
            return new CollectionWriteBackService(
                    ASSET_HOST_MAPPER, INSTANCE_MAPPER, STATE_MAPPER, SSH_CONFIG_MAPPER,
                    hostInstanceCollectionProperties());
        }

        @Bean
        AssetHostService assetHostService() {
            return ASSET_HOST_SERVICE;
        }

        @Bean
        AssetHostSshConfigService assetHostSshConfigService() {
            return SSH_CONFIG_SERVICE;
        }

        @Bean
        RuntimeServiceInstanceService runtimeServiceInstanceService() {
            return INSTANCE_SERVICE;
        }

        @Bean
        CollectionTargetObserver collectionTargetObserver() {
            return OBSERVER_STUB;
        }

        @Bean
        HostInstanceCollectionService hostInstanceCollectionService(CollectionWriteBackService writeBackService) {
            return new HostInstanceCollectionService(
                    ASSET_HOST_SERVICE, SSH_CONFIG_SERVICE, INSTANCE_SERVICE,
                    STATE_MAPPER, writeBackService, List.of(OBSERVER_STUB),
                    hostInstanceCollectionProperties(), FIXED_CLOCK);
        }
    }

    private AnnotationConfigApplicationContext context;
    private CollectionWriteBackService writeBackService;
    private HostInstanceCollectionService collectionService;

    @BeforeEach
    void setUp() {
        reset(ASSET_HOST_MAPPER, INSTANCE_MAPPER, STATE_MAPPER, SSH_CONFIG_MAPPER,
                ASSET_HOST_SERVICE, SSH_CONFIG_SERVICE, INSTANCE_SERVICE, OBSERVER_STUB);
        TRANSACTIONS.resetCounters();

        context = new AnnotationConfigApplicationContext(TestConfig.class);
        writeBackService = context.getBean(CollectionWriteBackService.class);
        collectionService = context.getBean(HostInstanceCollectionService.class);
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    // ---------- R2：一次写回 = 一个短事务 ----------

    @Test
    @DisplayName("R2：采集状态与目标状态在同一短事务内写入，目标写入发生在提交之前，返回时事务已结束")
    void writeBackIsOneShortTransactionCoveringBothTables() {
        AtomicBoolean activeWhileUpsertingState = new AtomicBoolean(false);
        AtomicBoolean activeWhileWritingTarget = new AtomicBoolean(false);
        AtomicInteger commitsWhenTargetWritten = new AtomicInteger(-1);

        when(ASSET_HOST_MAPPER.selectForUpdate(HOST_ID)).thenReturn(hostRow(HOST_ID));
        when(STATE_MAPPER.upsertObservation(any())).thenAnswer(invocation -> {
            activeWhileUpsertingState.set(TransactionSynchronizationManager.isActualTransactionActive());
            return 1;
        });
        when(ASSET_HOST_MAPPER.updateHostStatus(eq(HOST_ID), eq("ONLINE"))).thenAnswer(invocation -> {
            activeWhileWritingTarget.set(TransactionSynchronizationManager.isActualTransactionActive());
            commitsWhenTargetWritten.set(TRANSACTIONS.committed.get());
            return 1;
        });

        assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),
                "进入写回之前不得已经持有事务");

        CollectionWriteBackService.WriteOutcome outcome =
                writeBackService.writeBack(hostRef(HOST_ID), success("ONLINE", EXPECTED_HEALTH));

        assertEquals(CollectionWriteBackService.WriteOutcome.APPLIED, outcome);
        assertTrue(activeWhileUpsertingState.get(), "写采集表必须在事务中");
        assertTrue(activeWhileWritingTarget.get(), "写目标表必须与写采集表同处一个事务");
        assertEquals(0, commitsWhenTargetWritten.get(),
                "目标表写入必须发生在提交之前：否则就是“采集表已提交、目标表还没写”的旧缺陷");
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),
                "返回时事务必须已经结束：网络等待与后续处理不得被事务包住");

        assertEquals(1, TRANSACTIONS.begun.get(), "一次写回只允许一个事务");
        assertEquals(1, TRANSACTIONS.committed.get());
        assertEquals(0, TRANSACTIONS.rolledBack.get());
    }

    @Test
    @DisplayName("R2：目标状态写入抛异常时，采集状态写入一并回滚，不留“采集表已写、目标表没写”的半成品")
    void writeBackRollsBackStateWhenTargetWriteFails() {
        when(ASSET_HOST_MAPPER.selectForUpdate(HOST_ID)).thenReturn(hostRow(HOST_ID));
        when(STATE_MAPPER.upsertObservation(any())).thenReturn(1);
        when(ASSET_HOST_MAPPER.updateHostStatus(eq(HOST_ID), any()))
                .thenThrow(new IllegalStateException("目标行被锁超时"));

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> writeBackService.writeBack(hostRef(HOST_ID), success("ONLINE", EXPECTED_HEALTH)));

        assertEquals("目标行被锁超时", thrown.getMessage(), "原始异常必须原样抛出，不得被吞成“已记录”");
        verify(STATE_MAPPER, times(1)).upsertObservation(any());
        assertEquals(1, TRANSACTIONS.begun.get());
        assertEquals(0, TRANSACTIONS.committed.get(), "两个写在同一事务里：目标表失败就不能提交采集表");
        assertEquals(1, TRANSACTIONS.rolledBack.get(), "必须整体回滚");
    }

    @Test
    @DisplayName("R2：迟到样本被拒收时不写目标表，但仍是一个完整提交的短事务（拒收计数要落库）")
    void discardedSampleStillCommitsItsOwnShortTransaction() {
        when(ASSET_HOST_MAPPER.selectForUpdate(HOST_ID)).thenReturn(hostRow(HOST_ID));
        when(STATE_MAPPER.upsertObservation(any())).thenReturn(0);
        when(STATE_MAPPER.incrementDiscarded(eq(CollectionTargetType.HOST), eq(HOST_ID), any())).thenReturn(1);

        CollectionWriteBackService.WriteOutcome outcome =
                writeBackService.writeBack(hostRef(HOST_ID), success("ONLINE", EXPECTED_HEALTH));

        assertEquals(CollectionWriteBackService.WriteOutcome.DISCARDED, outcome);
        verify(ASSET_HOST_MAPPER, never()).updateHostStatus(anyLong(), any());
        verify(STATE_MAPPER, times(1)).incrementDiscarded(eq(CollectionTargetType.HOST), eq(HOST_ID), any());
        assertEquals(1, TRANSACTIONS.committed.get(), "拒收也要留痕，不能回滚掉计数");
        assertEquals(0, TRANSACTIONS.rolledBack.get());
    }

    @Test
    @DisplayName("R2：目标已被删除（行锁读不到）时直接拒收，不写目标表，也不开多余事务")
    void missingTargetIsDiscarded() {
        when(ASSET_HOST_MAPPER.selectForUpdate(HOST_ID)).thenReturn(null);
        when(STATE_MAPPER.incrementDiscarded(eq(CollectionTargetType.HOST), eq(HOST_ID), any())).thenReturn(0);

        CollectionWriteBackService.WriteOutcome outcome =
                writeBackService.writeBack(hostRef(HOST_ID), success("ONLINE", EXPECTED_HEALTH));

        assertEquals(CollectionWriteBackService.WriteOutcome.DISCARDED, outcome);
        verify(STATE_MAPPER, never()).upsertObservation(any());
        assertEquals(1, TRANSACTIONS.committed.get());
    }

    // ---------- R6：每个目标一个短事务，整批不是一个事务 ----------

    @Test
    @DisplayName("R6：一轮采集按目标各自开事务；中间目标写回失败只回滚它自己，后续目标照常提交")
    void eachTargetGetsItsOwnTransactionAndFailureDoesNotStopLaterTargets() {
        List<AssetHost> hosts = List.of(hostRow(1L), hostRow(2L), hostRow(3L));
        List<AssetHostSshConfig> configs = List.of(sshConfig(101L, 1L), sshConfig(102L, 2L), sshConfig(103L, 3L));
        when(ASSET_HOST_SERVICE.list()).thenReturn(hosts);
        when(INSTANCE_SERVICE.list()).thenReturn(List.of());
        when(SSH_CONFIG_SERVICE.list(anyWrapper())).thenReturn(configs);
        when(ASSET_HOST_MAPPER.selectForUpdate(anyLong()))
                .thenAnswer(invocation -> hostRow(invocation.getArgument(0)));
        // 写回侧按主机查启用配置：与枚举侧同源，版本一致，因此样本被接受
        when(SSH_CONFIG_MAPPER.selectEnabledByHostIdForUpdate(anyLong()))
                .thenAnswer(invocation -> configs.stream()
                        .filter(config -> config.getHostId().equals(invocation.getArgument(0)))
                        .toList());

        AtomicBoolean transactionActiveWhileEnumerating = new AtomicBoolean(true);
        when(STATE_MAPPER.selectExpiredTargets(any())).thenAnswer(invocation -> {
            transactionActiveWhileEnumerating.set(TransactionSynchronizationManager.isActualTransactionActive());
            return List.of();
        });

        when(STATE_MAPPER.upsertObservation(any())).thenAnswer(invocation -> {
            MonitorCollectionState state = invocation.getArgument(0);
            if (Long.valueOf(2L).equals(state.getTargetId())) {
                throw new IllegalStateException("模拟目标 2 的落库失败");
            }
            return 1;
        });

        AtomicInteger commitsWhenThirdTargetWritten = new AtomicInteger(-1);
        when(ASSET_HOST_MAPPER.updateHostStatus(anyLong(), any())).thenAnswer(invocation -> {
            if (Long.valueOf(3L).equals(invocation.getArgument(0))) {
                commitsWhenThirdTargetWritten.set(TRANSACTIONS.committed.get());
            }
            return 1;
        });

        when(OBSERVER_STUB.supports(any())).thenReturn(true);
        when(OBSERVER_STUB.observerType()).thenReturn(OBSERVER);
        when(OBSERVER_STUB.observe(any())).thenReturn(success("ONLINE", EXPECTED_HEALTH));

        CollectionRunSummary summary = collectionService.collectOnce();

        assertFalse(transactionActiveWhileEnumerating.get(),
                "编排层不得持有事务：枚举与网络等待必须在事务之外");
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),
                "整轮结束后不得残留事务");

        assertEquals(3, TRANSACTIONS.begun.get(),
                "三个目标各自一个短事务；为 1 说明整批被塞进了一个长事务");
        assertEquals(2, TRANSACTIONS.committed.get(), "失败目标回滚，其余两个照常提交");
        assertEquals(1, TRANSACTIONS.rolledBack.get(), "失败目标只回滚它自己");

        assertEquals(1, summary.writeFailed(), "失败目标必须留痕，不能静默吞掉");
        assertEquals(2, summary.applied(), "后续目标照常写回");
        assertEquals(3, summary.total(), "失败的目标不能终止整轮");

        // 第三个目标写入时，第一个目标的事务已经独立提交过了 —— 证明不是一个覆盖整批的大事务
        assertEquals(1, commitsWhenThirdTargetWritten.get(),
                "前一个目标必须在后一个目标写回之前就已提交");
        verify(ASSET_HOST_MAPPER, times(1)).updateHostStatus(eq(3L), eq("ONLINE"));
        verify(ASSET_HOST_MAPPER, never()).updateHostStatus(eq(2L), any());
    }

    // ---------- R2 特别要求：markExpired 后复位失败须可重试 ----------

    @Test
    @DisplayName("R2：过期结算先锁目标行、再锁采集状态行读当前证据，标记与复位在同一事务内")
    void settleExpiredMarksAndResetsInsideOneTransactionAndKeepsLockOrder() {
        AtomicBoolean activeWhileReadingState = new AtomicBoolean(false);
        AtomicBoolean activeWhileMarking = new AtomicBoolean(false);
        AtomicBoolean activeWhileResetting = new AtomicBoolean(false);

        when(ASSET_HOST_MAPPER.selectForUpdate(HOST_ID)).thenReturn(hostRow(HOST_ID));
        when(STATE_MAPPER.selectByTargetForUpdate(CollectionTargetType.HOST, HOST_ID))
                .thenAnswer(invocation -> {
                    activeWhileReadingState.set(TransactionSynchronizationManager.isActualTransactionActive());
                    return expiredState(CollectionTargetType.HOST, HOST_ID, "ONLINE", EXPECTED_HEALTH);
                });
        when(STATE_MAPPER.markExpiredOne(eq(CollectionTargetType.HOST), eq(HOST_ID), any()))
                .thenAnswer(invocation -> {
                    activeWhileMarking.set(TransactionSynchronizationManager.isActualTransactionActive());
                    return 1;
                });
        when(ASSET_HOST_MAPPER.resetHostStatusIfObserved(eq(HOST_ID), eq("ONLINE"), eq("UNKNOWN")))
                .thenAnswer(invocation -> {
                    activeWhileResetting.set(TransactionSynchronizationManager.isActualTransactionActive());
                    return 1;
                });

        assertTrue(writeBackService.settleExpiredTarget(CollectionTargetType.HOST, HOST_ID, T0));

        assertTrue(activeWhileReadingState.get(), "读当前证据必须在事务中：否则读到的是没有锁保护、随时会变的快照");
        assertTrue(activeWhileMarking.get(), "标记 EXPIRED 必须在事务中");
        assertTrue(activeWhileResetting.get(), "复位必须与标记同处一个事务");
        assertEquals(1, TRANSACTIONS.begun.get());
        assertEquals(1, TRANSACTIONS.committed.get());

        InOrder order = inOrder(ASSET_HOST_MAPPER, STATE_MAPPER);
        order.verify(ASSET_HOST_MAPPER).selectForUpdate(HOST_ID);
        order.verify(STATE_MAPPER).selectByTargetForUpdate(CollectionTargetType.HOST, HOST_ID);
        order.verify(STATE_MAPPER).markExpiredOne(eq(CollectionTargetType.HOST), eq(HOST_ID), any());
        order.verify(ASSET_HOST_MAPPER).resetHostStatusIfObserved(eq(HOST_ID), eq("ONLINE"), eq("UNKNOWN"));
    }

    @Test
    @DisplayName("R2：复位失败回滚 EXPIRED 标记，该目标下一轮仍是候选、可重试，不会被永久跳过")
    void failedResetRollsBackTheExpiredMarkSoTheTargetStaysRetryable() {
        when(ASSET_HOST_MAPPER.selectForUpdate(HOST_ID)).thenReturn(hostRow(HOST_ID));
        when(STATE_MAPPER.selectByTargetForUpdate(CollectionTargetType.HOST, HOST_ID))
                .thenReturn(expiredState(CollectionTargetType.HOST, HOST_ID, "ONLINE", EXPECTED_HEALTH));
        when(STATE_MAPPER.markExpiredOne(eq(CollectionTargetType.HOST), eq(HOST_ID), any())).thenReturn(1);
        when(ASSET_HOST_MAPPER.resetHostStatusIfObserved(eq(HOST_ID), eq("ONLINE"), eq("UNKNOWN")))
                .thenThrow(new IllegalStateException("复位时目标行被锁"));

        assertThrows(IllegalStateException.class,
                () -> writeBackService.settleExpiredTarget(CollectionTargetType.HOST, HOST_ID, T0));

        assertEquals(0, TRANSACTIONS.committed.get(), "复位失败不得提交 EXPIRED 标记");
        assertEquals(1, TRANSACTIONS.rolledBack.get(), "标记必须与复位一起回滚");

        // 下一轮：同一目标重新被选中并成功结算 —— 证明它没有被“已标记”永久挡在候选集合之外
        reset(ASSET_HOST_MAPPER);
        when(ASSET_HOST_MAPPER.selectForUpdate(HOST_ID)).thenReturn(hostRow(HOST_ID));
        when(ASSET_HOST_MAPPER.resetHostStatusIfObserved(eq(HOST_ID), eq("ONLINE"), eq("UNKNOWN"))).thenReturn(1);

        assertTrue(writeBackService.settleExpiredTarget(CollectionTargetType.HOST, HOST_ID, T0),
                "回滚后重试必须能真正完成复位");

        verify(STATE_MAPPER, times(2)).markExpiredOne(eq(CollectionTargetType.HOST), eq(HOST_ID), any());
        assertEquals(1, TRANSACTIONS.committed.get(), "只有第二次（复位成功的那次）提交");
        assertEquals(1, TRANSACTIONS.rolledBack.get(), "第一次的标记随复位失败一起回滚");
    }

    @Test
    @DisplayName("R2：候选已被别的执行者标记时不重复复位，也不产生“改了别人的值”")
    void settleExpiredDoesNotResetWhenMarkWasAlreadyTaken() {
        when(ASSET_HOST_MAPPER.selectForUpdate(HOST_ID)).thenReturn(hostRow(HOST_ID));
        when(STATE_MAPPER.selectByTargetForUpdate(CollectionTargetType.HOST, HOST_ID))
                .thenReturn(expiredState(CollectionTargetType.HOST, HOST_ID, "ONLINE", EXPECTED_HEALTH));
        when(STATE_MAPPER.markExpiredOne(eq(CollectionTargetType.HOST), eq(HOST_ID), any())).thenReturn(0);

        assertFalse(writeBackService.settleExpiredTarget(CollectionTargetType.HOST, HOST_ID, T0));

        verify(ASSET_HOST_MAPPER, never()).resetHostStatusIfObserved(anyLong(), any(), any());
        assertEquals(1, TRANSACTIONS.committed.get(), "读锁与判定仍是一次完整提交的短事务");
    }

    @Test
    @DisplayName("R2：锁内读到的证据没有可复位值时不改目标表，也不写条件复位")
    void settleExpiredSkipsTargetWithoutObservedValue() {
        when(ASSET_HOST_MAPPER.selectForUpdate(HOST_ID)).thenReturn(hostRow(HOST_ID));
        when(STATE_MAPPER.selectByTargetForUpdate(CollectionTargetType.HOST, HOST_ID))
                .thenReturn(expiredState(CollectionTargetType.HOST, HOST_ID, null, null));
        when(STATE_MAPPER.markExpiredOne(eq(CollectionTargetType.HOST), eq(HOST_ID), any())).thenReturn(1);

        assertFalse(writeBackService.settleExpiredTarget(CollectionTargetType.HOST, HOST_ID, T0),
                "无可复位值时结算不算“复位成功”");

        verify(ASSET_HOST_MAPPER, never()).resetHostStatusIfObserved(anyLong(), any(), any());
        verify(ASSET_HOST_MAPPER, never()).updateHostStatus(anyLong(), any());
    }

    // ---------- F2：结算必须基于锁内读到的“当前证据” ----------

    @Test
    @DisplayName("F2：复位用的是锁内读到的当前观测值，不是候选快照携带的旧值")
    void settleExpiredResetsWithTheValueReadInsideTheLock() {
        // 候选快照说的是 ONLINE；锁内读到的当前证据已经是 OFFLINE。
        // 用旧值做 CAS 会命中 0 行，却仍提交 EXPIRED —— 目标状态卡在 OFFLINE 再也不会被复位。
        when(ASSET_HOST_MAPPER.selectForUpdate(HOST_ID)).thenReturn(hostRow(HOST_ID));
        when(STATE_MAPPER.selectByTargetForUpdate(CollectionTargetType.HOST, HOST_ID))
                .thenReturn(expiredState(CollectionTargetType.HOST, HOST_ID, "OFFLINE", "UNKNOWN"));
        when(STATE_MAPPER.markExpiredOne(eq(CollectionTargetType.HOST), eq(HOST_ID), any())).thenReturn(1);
        when(ASSET_HOST_MAPPER.resetHostStatusIfObserved(eq(HOST_ID), eq("OFFLINE"), eq("UNKNOWN"))).thenReturn(1);

        assertTrue(writeBackService.settleExpiredTarget(CollectionTargetType.HOST, HOST_ID, T0));

        verify(ASSET_HOST_MAPPER).resetHostStatusIfObserved(eq(HOST_ID), eq("OFFLINE"), eq("UNKNOWN"));
        verify(ASSET_HOST_MAPPER, never()).resetHostStatusIfObserved(anyLong(), eq("ONLINE"), any());
    }

    @Test
    @DisplayName("F2：锁内读到的证据已不再过期时不标记也不复位，留给下一轮重新评估")
    void settleExpiredDoesNothingWhenCurrentEvidenceIsFreshAgain() {
        when(ASSET_HOST_MAPPER.selectForUpdate(HOST_ID)).thenReturn(hostRow(HOST_ID));
        // 候选快照选中它时确实过期；但锁内读到的 last_success_at 已经晚于 staleBefore
        when(STATE_MAPPER.selectByTargetForUpdate(CollectionTargetType.HOST, HOST_ID))
                .thenReturn(expiredState(CollectionTargetType.HOST, HOST_ID,
                        "ONLINE", EXPECTED_HEALTH, T0.plusSeconds(60), CollectionResult.SUCCESS.name()));

        assertFalse(writeBackService.settleExpiredTarget(CollectionTargetType.HOST, HOST_ID, T0));

        verify(STATE_MAPPER, never()).markExpiredOne(any(), anyLong(), any());
        verify(ASSET_HOST_MAPPER, never()).resetHostStatusIfObserved(anyLong(), any(), any());
    }

    @Test
    @DisplayName("F2：锁内读到的行已被标为 EXPIRED 时不重复标记、不重复复位")
    void settleExpiredDoesNothingWhenCurrentRowIsAlreadyExpired() {
        when(ASSET_HOST_MAPPER.selectForUpdate(HOST_ID)).thenReturn(hostRow(HOST_ID));
        when(STATE_MAPPER.selectByTargetForUpdate(CollectionTargetType.HOST, HOST_ID))
                .thenReturn(expiredState(CollectionTargetType.HOST, HOST_ID,
                        "ONLINE", EXPECTED_HEALTH, T0.minusSeconds(600), "EXPIRED"));

        assertFalse(writeBackService.settleExpiredTarget(CollectionTargetType.HOST, HOST_ID, T0));

        verify(STATE_MAPPER, never()).markExpiredOne(any(), anyLong(), any());
        verify(ASSET_HOST_MAPPER, never()).resetHostStatusIfObserved(anyLong(), any(), any());
    }

    @Test
    @DisplayName("F2：采集状态行已不存在（目标刚被删）时不标记、不写目标表")
    void settleExpiredDoesNothingWhenStateRowIsGone() {
        when(ASSET_HOST_MAPPER.selectForUpdate(HOST_ID)).thenReturn(hostRow(HOST_ID));
        when(STATE_MAPPER.selectByTargetForUpdate(CollectionTargetType.HOST, HOST_ID)).thenReturn(null);

        assertFalse(writeBackService.settleExpiredTarget(CollectionTargetType.HOST, HOST_ID, T0));

        verify(STATE_MAPPER, never()).markExpiredOne(any(), anyLong(), any());
        verify(ASSET_HOST_MAPPER, never()).resetHostStatusIfObserved(anyLong(), any(), any());
    }

    @Test
    @DisplayName("F2：运行实例的状态与健康一起按锁内当前证据复位")
    void settleExpiredResetsInstanceStatusAndHealthFromCurrentEvidence() {
        when(INSTANCE_MAPPER.selectForUpdate(INSTANCE_ID)).thenReturn(instanceRow(INSTANCE_ID));
        when(STATE_MAPPER.selectByTargetForUpdate(CollectionTargetType.SERVICE_INSTANCE, INSTANCE_ID))
                .thenReturn(expiredState(CollectionTargetType.SERVICE_INSTANCE, INSTANCE_ID, "STOPPED", "DOWN"));
        when(STATE_MAPPER.markExpiredOne(eq(CollectionTargetType.SERVICE_INSTANCE), eq(INSTANCE_ID), any()))
                .thenReturn(1);
        when(INSTANCE_MAPPER.resetObservedStatusIfMatched(
                eq(INSTANCE_ID), eq("STOPPED"), eq("DOWN"), eq("UNKNOWN"), eq("UNKNOWN"))).thenReturn(1);

        assertTrue(writeBackService.settleExpiredTarget(CollectionTargetType.SERVICE_INSTANCE, INSTANCE_ID, T0));

        verify(INSTANCE_MAPPER).resetObservedStatusIfMatched(
                eq(INSTANCE_ID), eq("STOPPED"), eq("DOWN"), eq("UNKNOWN"), eq("UNKNOWN"));
        verify(ASSET_HOST_MAPPER, never()).resetHostStatusIfObserved(anyLong(), any(), any());
        assertEquals(1, TRANSACTIONS.committed.get());
    }

    // ---------- 结构性契约 ----------

    @Test
    @DisplayName("事务边界契约：写回与过期结算必须是事务方法，编排层不得是事务入口，且不得存在整批入口")
    void transactionBoundaryContract() throws Exception {
        assertNotNull(CollectionWriteBackService.class
                        .getMethod("writeBack", CollectionTargetRef.class, CollectionObservation.class)
                        .getAnnotation(Transactional.class),
                "写回采集状态与目标状态必须由同一个短事务提交");
        assertNotNull(CollectionWriteBackService.class
                        .getMethod("settleExpiredTarget", String.class, Long.class, OffsetDateTime.class)
                        .getAnnotation(Transactional.class),
                "过期结算的“标记 EXPIRED + 复位目标”必须由同一个短事务提交，否则复位失败即永久跳过");
        assertEquals(3, CollectionWriteBackService.class
                        .getMethod("settleExpiredTarget", String.class, Long.class, OffsetDateTime.class)
                        .getParameterCount(),
                "结算只接受候选的定位信息（类型 / id / 阈值）：一旦签名里再有调用方传来的 expected 值，"
                        + "就又会出现“用事务外快照做复位 CAS”的错位");
        assertNull(HostInstanceCollectionService.class.getMethod("collectOnce").getAnnotation(Transactional.class),
                "编排层不得持有事务：枚举与网络等待不能与数据库事务绑在一起");

        for (Method method : CollectionWriteBackService.class.getMethods()) {
            for (Class<?> parameterType : method.getParameterTypes()) {
                assertFalse(List.class.isAssignableFrom(parameterType),
                        "不得存在接收整批目标的写回入口（" + method.getName()
                                + "）：一旦存在，整批一个事务的缺陷就会从那里回来");
            }
        }
    }

    // ---------- 样本 ----------

    private static CollectionTargetRef hostRef(long hostId) {
        return new CollectionTargetRef(CollectionTargetType.HOST, hostId, hostId, null, null, null,
                "10.20.30." + hostId, null, null, null);
    }

    private static CollectionObservation success(String status, String health) {
        return new CollectionObservation(CollectionResult.SUCCESS, null, null, status, health, T0, OBSERVER, true);
    }

    private static AssetHost hostRow(long hostId) {
        AssetHost host = new AssetHost();
        host.setId(hostId);
        host.setIp("10.20.30." + hostId);
        host.setHostname("host-" + hostId);
        host.setHostStatus("ONLINE");
        return host;
    }

    private static RuntimeServiceInstance instanceRow(long instanceId) {
        RuntimeServiceInstance instance = new RuntimeServiceInstance();
        instance.setId(instanceId);
        instance.setHostId(HOST_ID);
        instance.setInstanceType("JAVA");
        instance.setInstanceStatus("RUNNING");
        instance.setHealthStatus("UP");
        return instance;
    }

    /**
     * 锁内读到的一行采集状态：默认「成功观测于 10 分钟前、尚未标记」，
     * 也就是对 {@code staleBefore = T0} 而言确实已经过期。
     */
    private static MonitorCollectionState expiredState(String targetType, long targetId,
                                                       String observedStatus, String observedHealth) {
        return expiredState(targetType, targetId, observedStatus, observedHealth,
                T0.minusSeconds(600), CollectionResult.SUCCESS.name());
    }

    private static MonitorCollectionState expiredState(String targetType, long targetId,
                                                       String observedStatus, String observedHealth,
                                                       OffsetDateTime lastSuccessAt, String lastResult) {
        MonitorCollectionState state = new MonitorCollectionState();
        state.setTargetType(targetType);
        state.setTargetId(targetId);
        state.setLastSuccessAt(lastSuccessAt);
        state.setLastResult(lastResult);
        state.setLastObservedStatus(observedStatus);
        state.setLastObservedHealth(observedHealth);
        return state;
    }

    private static AssetHostSshConfig sshConfig(long configId, long hostId) {
        AssetHostSshConfig config = new AssetHostSshConfig();
        config.setId(configId);
        config.setHostId(hostId);
        config.setEnabled(Boolean.TRUE);
        config.setAuthType("PASSWORD");
        config.setUpdatedAt(T0);
        return config;
    }

    /** {@code IService.list} 有多个重载，显式返回类型才能消歧。 */
    private static <T> Wrapper<T> anyWrapper() {
        return any();
    }
}
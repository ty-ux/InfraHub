package com.infrahub.release.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.infrahub.common.error.ErrorCode;
import com.infrahub.common.exception.BizException;
import com.infrahub.infra.ssh.SshExecutionResult;
import com.infrahub.infra.ssh.SshExecutorService;
import com.infrahub.release.config.HostInstanceCollectionProperties;
import com.infrahub.release.dto.CollectionObservation;
import com.infrahub.release.dto.CollectionScopedRunSummary;
import com.infrahub.release.dto.CollectionTargetRef;
import com.infrahub.release.dto.RuntimeServiceInstanceStatusResponse;
import com.infrahub.release.entity.AssetHostSshConfig;
import com.infrahub.release.entity.MonitorCollectionState;
import com.infrahub.release.mapper.AssetHostMapper;
import com.infrahub.release.mapper.AssetHostSshConfigMapper;
import com.infrahub.release.mapper.MonitorCollectionStateMapper;
import com.infrahub.release.mapper.RuntimeServiceInstanceMapper;
import com.infrahub.release.service.CollectionWriteBackService.WriteOutcome;
import com.infrahub.release.support.CollectionAdmissionPolicy;
import com.infrahub.release.support.CollectionErrorCategory;
import com.infrahub.release.support.CollectionResult;
import com.infrahub.release.support.CollectionTargetType;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link CollectionWriteBackService} 的**一致性边界**在真实 PostgreSQL 上的验证。
 *
 * <p>为什么不能只用 mock mapper：本类要回答的问题全都只由数据库回答 ——
 * 行锁是否真的让并发写回串行（而不是靠进程内 {@code AtomicBoolean}）、
 * 目标表写入失败时采集表那一条是否真的跟着回滚、
 * 复位失败后过期目标是否还能在下一轮被重新选出来。
 * mock 只能证明"方法按顺序被调用了"，证明不了这些语义成立。
 *
 * <p>与 {@code MonitorCollectionStateMapperPostgresTest} 的分工：
 * 那个类验证**单条 SQL 的语义**（乱序守卫、COALESCE、连续失败自增、幂等标记），
 * 本类验证**多条 SQL 组成的事务边界**（{@code @Transactional} 代理是否真的生效、
 * 跨表原子性、行锁串行化、异常隔离）。因此本类必须用真实的 Spring 事务代理，
 * 而不是直接 {@code new CollectionWriteBackService(...)} ——
 * 后者绕开代理，{@code @Transactional} 完全不生效，测试会给出虚假的绿灯。
 *
 * <p>R7 一段额外把**真实观测器**接进来（spring 上下文里没有它，是每个用例现场装配的）：
 * 那里要证明的不是 SQL，而是"缺配置的观测**根本不会**变成 FAILED"——
 * 这件事横跨观测器分类与写回计数两处，只在任一侧验证都会漏掉另一半。
 * 诊断服务在那一段是打桩的（真实诊断需要真拨 SSH），打桩复现的是它的异常类型与逐字文案。
 *
 * <p>本类自带一份隔离库解析 / 迁移装载 / 会话装配（与上述 mapper 级用例同构）。
 * 这层地基确实是重复的：本任务被明确禁止"整理工作区"，所以没有把它抽成公共基类，
 * 重复是有意为之，代价是约 150 行样板。
 *
 * <h2>隔离库来源（二选一）</h2>
 * <ol>
 *   <li>显式指定隔离 PostgreSQL，**推荐用配置文件**（免得密码出现在命令行参数里）：
 *       {@code -Dinfrahub.it.pg.config=<仓库外的 .properties 路径>}，内容为
 *       {@code url=} / {@code username=} / {@code password=}；也支持
 *       {@code -Dinfrahub.it.pg.url/.username/.password} 或环境变量
 *       {@code INFRAHUB_IT_PG_CONFIG / _URL / _USERNAME / _PASSWORD}。
 *       测试在该实例上**新建一次性数据库** {@code infrahub_it_<12 位随机>}，
 *       结束时**只删自己建的那一个库**（按记录下来的名字删除，
 *       绝不按前缀枚举后批量删除来源不明的库）。</li>
 *   <li>Testcontainers 起一个 {@code postgres:16-alpine}。</li>
 * </ol>
 *
 * <p><b>两者都不可用时逐条报告 skip</b>（{@code Tests run: N, Skipped: N}），
 * 不伪装成通过，也不静默归零成一个"本来就是空的类"。
 *
 * <p><b>安全护栏</b>：显式 URL 指向共享真实库（{@value #SHARED_REAL_DB_HOST}）时直接失败，不执行任何 DDL。
 * 凭据只从仓库外的配置文件 / 环境变量来，本类不打印它们。
 */
class CollectionWriteBackPostgresTest {

    /** 共享真实库主机。本任务未授权对其做任何写入，必须拒绝而不是"看起来通了"。 */
    private static final String SHARED_REAL_DB_HOST = "192.0.2.1";

    /** UTF-8 BOM。写成码点而不是字面量，避免源码里出现不可见字符。 */
    private static final int UTF8_BOM = 0xFEFF;

    /** 夹具自用 id 下界：清理只删这个区间，父行链（id=1）不受影响。 */
    private static final long FIXTURE_ID_FLOOR = 92000L;

    private static final String OBSERVER_TYPE = "SSH_PROCESS";

    private static final int DEFAULT_FAILURE_THRESHOLD = 3;

    /**
     * 夹具里 SSH 配置的版本时刻，等价于 SQL 里的 {@code TIMESTAMPTZ '2026-01-01 00:00:00+00'}。
     * 采样侧引用它，被改过之后引用 {@link #AT_ONE_MINUTE}，两者相差一分钟。
     */
    private static final OffsetDateTime CONFIG_VERSION = at(0);

    private static final OffsetDateTime AT_ONE_MINUTE = at(1);

    /** 夹具 {@code asset_host_ssh_config.ssh_port} 的取值；快照必须带上它才能与实际拨号一致。 */
    private static final int FIXTURE_SSH_PORT = 22;

    /** 夹具 {@code runtime_service_instance} 的服务/环境 id，枚举侧会把它们写进快照。 */
    private static final long FIXTURE_SERVICE_ID = 1L;

    private static final long FIXTURE_ENV_ID = 1L;

    private static PostgreSQLContainer<?> container;

    private static String jdbcUrl;

    /** 唯一会被删除的数据库名：只在本测试自己 CREATE 出来的那一支才非空。 */
    private static String disposableDatabaseName;

    /** 该实例的库名前缀，删除自建库时用它拼连接串。 */
    private static String serverBaseUrl;

    private static String username;

    private static String password;

    /** 无可用的隔离库时记下原因，由 {@link #resetFixtures()} 逐条跳过。 */
    private static String unavailableReason;

    private static AnnotationConfigApplicationContext context;

    private static CollectionWriteBackService writeBackService;

    private static HostInstanceCollectionProperties properties;

    private static MonitorCollectionStateMapper stateMapper;

    private static AssetHostMapper assetHostMapper;

    private static AssetHostSshConfigMapper sshConfigMapper;

    /** 受限入口的枚举要读实例表，因此这一支也要从上下文里取真 mapper。 */
    private static RuntimeServiceInstanceMapper runtimeServiceInstanceMapper;

    /** R7 夹具：观测器会用的 SSH 执行器。打桩是为了能被断言"一次都没调用过"。 */
    private static SshExecutorService sshExecutorService;

    /** R7 夹具：打桩的诊断服务（缺配置异常的真实抛出方）。 */
    private static RuntimeServiceDiagnosticsService diagnosticsService;

    /** R7 夹具：真实观测器，每个用例重建，避免桩状态跨用例残留。 */
    private static SshProcessCollectionObserver observer;

    /** R7 夹具：可推进的固定时钟，决定每轮观测时刻。 */
    private static MutableClock collectionClock;

    @BeforeAll
    static void setUp() throws Exception {
        resolveIsolatedDatabase();
        if (unavailableReason != null) {
            return;
        }
        applyMigration("V1__baseline_schema.sql");
        applyMigration("V29__create_monitor_collection_state.sql");
        applyBootstrapTableBlock();
        seedForeignKeyParents();
        startContext();
    }

    /**
     * 每个用例都从干净状态起跑：并发 / 回滚 / 阈值类断言都会看"表里到底有几行"，
     * 上一个用例残留的行会让失败原因指向错误的地方。
     *
     * <p>开头那条 assume 是"未验证就必须显眼"的落点：没有隔离库时它让每条用例被报告为 skipped。
     */
    @BeforeEach
    void resetFixtures() throws Exception {
        Assumptions.assumeTrue(unavailableReason == null, unavailableReason);
        dropFailureInjectionTrigger();
        execute("DELETE FROM monitor_collection_state");
        // 这个库是本用例独占的一次性数据库，库里每一行都由本类写入，整表清空是安全的
        execute("DELETE FROM asset_host_ssh_config");
        execute("DELETE FROM runtime_service_instance WHERE id >= " + FIXTURE_ID_FLOOR);
        execute("DELETE FROM asset_host WHERE id >= " + FIXTURE_ID_FLOOR);
        properties.setFailureThreshold(DEFAULT_FAILURE_THRESHOLD);
        installObserverFixture();
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (context != null) {
            context.close();
        }
        if (container != null) {
            container.stop();
        }
        dropOwnDisposableDatabase();
    }

    // ---------- R2：一个短事务 / 行锁串行化 / 回滚 ----------

    @Test
    @DisplayName("R2：写回在目标行锁上排队，锁未释放前别的会话看不到任何写入")
    void writeBackQueuesOnTheTargetRowLock() throws Exception {
        long hostId = insertHost(92001L, "it-wb-lock", "192.0.2.25", "ONLINE");
        long configId = insertEnabledConfig(92001L, hostId);
        CollectionTargetRef ref = hostRef(hostId, "192.0.2.25", configId);

        AtomicReference<WriteOutcome> outcome = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread writer;

        try (Connection holder = connect()) {
            holder.setAutoCommit(false);
            try (Statement statement = holder.createStatement()) {
                statement.executeQuery("SELECT id FROM asset_host WHERE id = " + hostId + " FOR UPDATE").close();
            }

            writer = inThread("it-wb-lock", () -> {
                try {
                    outcome.set(writeBackService.writeBack(ref, success("OFFLINE", "UNKNOWN", at(10))));
                } catch (Throwable throwable) {
                    failure.set(throwable);
                }
            });
            writer.start();
            writer.join(600);

            assertThat(writer.isAlive())
                    .as("目标行锁被别的会话持有时，写回必须阻塞在锁上，而不是把旧值直接写下去")
                    .isTrue();
            assertThat(countRows("SELECT COUNT(*) FROM monitor_collection_state "
                    + "WHERE target_type = 'HOST' AND target_id = " + hostId))
                    .as("提交之前，别的会话不得看到半成品写入")
                    .isZero();

            holder.commit();
        }

        writer.join(5_000);
        assertThat(writer.isAlive()).as("锁释放后写回应自行完成").isFalse();
        assertThat(failure.get()).isNull();
        assertThat(outcome.get()).isEqualTo(WriteOutcome.APPLIED);
        assertThat(hostStatusOf(hostId)).isEqualTo("OFFLINE");
        assertThat(stateMapper.selectByTarget(CollectionTargetType.HOST, hostId).getObservedAt().toInstant())
                .isEqualTo(at(10).toInstant());
    }

    @Test
    @DisplayName("R2：并发样本交错后采集表与目标表结论一致，最新观测必胜出")
    void concurrentSamplesLeaveBothTablesInAgreement() throws Exception {
        long hostId = insertHost(92002L, "it-wb-race", "192.0.2.26", "ONLINE");
        long configId = insertEnabledConfig(92002L, hostId);
        CollectionTargetRef ref = hostRef(hostId, "192.0.2.26", configId);

        int samples = 6;
        List<Integer> minutes = new ArrayList<>();
        for (int minute = 1; minute <= samples; minute++) {
            minutes.add(minute);
        }
        // 固定种子：打乱提交顺序让交错真实发生，同时保持可复现
        Collections.shuffle(minutes, new Random(20261015L));

        CountDownLatch gate = new CountDownLatch(1);
        AtomicInteger applied = new AtomicInteger();
        AtomicInteger discarded = new AtomicInteger();
        List<String> unexpected = Collections.synchronizedList(new ArrayList<>());
        List<Thread> threads = new ArrayList<>();
        for (int minute : minutes) {
            String status = minute == samples ? "ONLINE" : "OFFLINE";
            threads.add(inThread("it-wb-race-" + minute, () -> {
                try {
                    gate.await();
                    WriteOutcome result = writeBackService.writeBack(ref, success(status, "UNKNOWN", at(minute)));
                    if (result == WriteOutcome.APPLIED) {
                        applied.incrementAndGet();
                    } else if (result == WriteOutcome.DISCARDED) {
                        discarded.incrementAndGet();
                    } else {
                        unexpected.add("minute=" + minute + " outcome=" + result);
                    }
                } catch (Throwable throwable) {
                    unexpected.add("minute=" + minute + " " + throwable);
                }
            }));
        }

        threads.forEach(Thread::start);
        gate.countDown();
        for (Thread thread : threads) {
            thread.join(10_000);
        }

        assertThat(unexpected).as("并发下不应出现意外异常或其他写回结论").isEmpty();
        assertThat(threads).allSatisfy(thread -> assertThat(thread.isAlive()).isFalse());
        assertThat(applied.get() + discarded.get()).isEqualTo(samples);

        MonitorCollectionState state = stateMapper.selectByTarget(CollectionTargetType.HOST, hostId);
        assertThat(state.getObservedAt().toInstant())
                .as("采集表必须停在最新那一刻的观测上")
                .isEqualTo(at(samples).toInstant());
        assertThat(state.getLastObservedStatus())
                .as("采集表说 ONLINE，是因为最新样本是 minute=6 的 ONLINE")
                .isEqualTo("ONLINE");
        assertThat(hostStatusOf(hostId)).as("目标表必须与采集表同结论，不能一个 STOPPED 一个 RUNNING").isEqualTo("ONLINE");
        assertThat(state.getDiscardedSampleCount())
                .as("拒收计数应恰好等于被拒收的那几次写回")
                .isEqualTo(discarded.get());
    }

    @Test
    @DisplayName("R2：迟到的旧样本不得把结论改回去——被改判 STOPPED 后旧 RUNNING 被拒收")
    void lateSampleDoesNotResurrectTheOlderConclusion() throws Exception {
        long hostId = insertHost(92003L, "it-wb-late", "192.0.2.27", "ONLINE");
        long configId = insertEnabledConfig(92003L, hostId);
        long instanceId = insertInstance(92003L, hostId, "JAVA", "RUNNING", "UP");
        CollectionTargetRef ref = instanceRef(instanceId, hostId, "JAVA", "192.0.2.27", configId);

        assertThat(writeBackService.writeBack(ref, success("STOPPED", "DOWN", at(10))))
                .isEqualTo(WriteOutcome.APPLIED);
        assertThat(instanceStatusOf(instanceId)).isEqualTo("STOPPED");

        assertThat(writeBackService.writeBack(ref, success("RUNNING", "UP", at(5))))
                .as("observed_at 更旧的样本必须被拒收")
                .isEqualTo(WriteOutcome.DISCARDED);

        assertThat(instanceStatusOf(instanceId)).as("目标表不得被旧样本改回 RUNNING").isEqualTo("STOPPED");
        assertThat(instanceHealthOf(instanceId)).as("健康维度同样不得被旧样本改回 UP").isEqualTo("DOWN");
        MonitorCollectionState state = stateMapper.selectByTarget(CollectionTargetType.SERVICE_INSTANCE, instanceId);
        assertThat(state.getLastObservedStatus()).isEqualTo("STOPPED");
        assertThat(state.getObservedAt().toInstant()).isEqualTo(at(10).toInstant());
        assertThat(state.getDiscardedSampleCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("R2：目标表写入失败时同事务内的采集状态一并回滚，修复后仍可重试")
    void targetWriteFailureRollsBackTheCollectionStateWrite() throws Exception {
        long hostId = insertHost(92004L, "it-wb-rollback", "192.0.2.28", "ONLINE");
        long configId = insertEnabledConfig(92004L, hostId);
        long instanceId = insertInstance(92004L, hostId, "JAVA", "RUNNING", "UP");
        CollectionTargetRef ref = instanceRef(instanceId, hostId, "JAVA", "192.0.2.28", configId);

        // 真实数据库层的失败注入：目标表上的 BEFORE UPDATE 触发器直接 RAISE
        installFailureInjection("OLD.id = " + instanceId);

        assertThatThrownBy(() -> writeBackService.writeBack(ref, success("STOPPED", "DOWN", at(10))))
                .as("目标表写入被数据库拒绝时异常必须冒出来，不能伪装成写回成功")
                .isInstanceOf(RuntimeException.class);

        assertThat(stateMapper.selectByTarget(CollectionTargetType.SERVICE_INSTANCE, instanceId))
                .as("采集状态属于同一个事务，必须随目标表写入失败一起回滚")
                .isNull();
        assertThat(instanceStatusOf(instanceId)).isEqualTo("RUNNING");

        dropFailureInjectionTrigger();

        assertThat(writeBackService.writeBack(ref, success("STOPPED", "DOWN", at(11))))
                .as("失败不该让这个目标永久写不进去")
                .isEqualTo(WriteOutcome.APPLIED);
        assertThat(instanceStatusOf(instanceId)).isEqualTo("STOPPED");
        assertThat(stateMapper.selectByTarget(CollectionTargetType.SERVICE_INSTANCE, instanceId).getLastObservedStatus())
                .isEqualTo("STOPPED");
    }

    @Test
    @DisplayName("R2：过期标记与目标复位同事务——复位失败则标记回滚，目标下一轮仍能被选出（不会永久卡在 EXPIRED）")
    void failedResetKeepsTheExpiredTargetRetryable() throws Exception {
        long hostId = insertHost(92005L, "it-wb-expired", "192.0.2.29", "ONLINE");
        long configId = insertEnabledConfig(92005L, hostId);
        long instanceId = insertInstance(92005L, hostId, "JAVA", "RUNNING", "UP");
        CollectionTargetRef ref = instanceRef(instanceId, hostId, "JAVA", "192.0.2.29", configId);

        assertThat(writeBackService.writeBack(ref, success("RUNNING", "UP", at(0))))
                .isEqualTo(WriteOutcome.APPLIED);

        OffsetDateTime staleBefore = at(5);
        assertThat(stateMapper.selectExpiredTargets(staleBefore)).extracting(MonitorCollectionState::getTargetId)
                .as("证据已过期，应成为候选")
                .containsExactly(instanceId);

        installFailureInjection("OLD.id = " + instanceId);
        assertThatThrownBy(() -> writeBackService.settleExpiredTarget(
                CollectionTargetType.SERVICE_INSTANCE, instanceId, staleBefore))
                .isInstanceOf(RuntimeException.class);

        assertThat(stateMapper.selectByTarget(CollectionTargetType.SERVICE_INSTANCE, instanceId).getLastResult())
                .as("标记必须随复位失败一起回滚，否则该目标会被永久排除在候选之外")
                .isEqualTo("SUCCESS");
        assertThat(stateMapper.selectExpiredTargets(staleBefore)).extracting(MonitorCollectionState::getTargetId)
                .as("回滚后该目标下一轮仍是候选，可以重试")
                .containsExactly(instanceId);
        assertThat(instanceStatusOf(instanceId)).isEqualTo("RUNNING");

        dropFailureInjectionTrigger();

        assertThat(writeBackService.settleExpiredTarget(
                CollectionTargetType.SERVICE_INSTANCE, instanceId, staleBefore))
                .as("重试应成功")
                .isTrue();
        assertThat(stateMapper.selectByTarget(CollectionTargetType.SERVICE_INSTANCE, instanceId).getLastResult())
                .isEqualTo("EXPIRED");
        assertThat(instanceStatusOf(instanceId)).isEqualTo("UNKNOWN");
        assertThat(instanceHealthOf(instanceId)).isEqualTo("UNKNOWN");
    }

    // ---------- F2：结算必须基于锁内读到的“当前证据” ----------

    @Test
    @DisplayName("F2：候选选定后证据被另一个执行者刷新——按锁内当前值复位，不留下“EXPIRED + 未复位 OFFLINE + 无候选”")
    void settleExpiredResetsWithTheEvidenceReadInsideTheLock() throws Exception {
        long hostId = insertHost(92095L, "it-wb-expiry-race", "192.0.2.30", "ONLINE");
        long configId = insertEnabledConfig(92095L, hostId);
        CollectionTargetRef ref = hostRef(hostId, "192.0.2.30", configId);

        // ① 00:00 成功观测，目标 ONLINE
        assertThat(writeBackService.writeBack(ref, success("ONLINE", "UNKNOWN", at(0))))
                .isEqualTo(WriteOutcome.APPLIED);
        assertThat(hostStatusOf(hostId)).isEqualTo("ONLINE");

        // ② 结算方在这一刻选出候选（只用来定位目标）
        OffsetDateTime staleBefore = at(5);
        assertThat(stateMapper.selectExpiredTargets(staleBefore)).extracting(MonitorCollectionState::getTargetId)
                .as("此刻它确实是过期候选")
                .containsExactly(hostId);

        // ③ 候选选出之后、结算之前，另一个执行者提交了更晚但仍早于阈值的成功观测
        assertThat(writeBackService.writeBack(ref, success("OFFLINE", "UNKNOWN", at(1))))
                .as("这条观测比候选快照新，必须被接受")
                .isEqualTo(WriteOutcome.APPLIED);
        assertThat(hostStatusOf(hostId)).isEqualTo("OFFLINE");

        // ④ 旧候选开始结算：标记与复位都必须依据锁内读到的 OFFLINE，
        //    而不是候选快照携带的 ONLINE（用 ONLINE 做 CAS 会命中 0 行，却仍提交 EXPIRED）
        assertThat(writeBackService.settleExpiredTarget(CollectionTargetType.HOST, hostId, staleBefore))
                .as("锁内读到的最新观测仍是过期证据，结算应当完成")
                .isTrue();

        MonitorCollectionState settled = stateMapper.selectByTarget(CollectionTargetType.HOST, hostId);
        assertThat(settled.getLastResult()).isEqualTo("EXPIRED");
        assertThat(settled.getLastObservedStatus())
                .as("采集表记的该是锁内读到的当前观测值")
                .isEqualTo("OFFLINE");
        assertThat(hostStatusOf(hostId))
                .as("目标状态必须被复位：留在 OFFLINE 而采集表说 EXPIRED，就是 review 里那个自相矛盾的状态")
                .isEqualTo("UNKNOWN");
        assertThat(stateMapper.selectExpiredTargets(staleBefore))
                .as("已结算完毕，因此不再出现在候选集合里（这里是复位之后的正常结果，不是被永久跳过）")
                .isEmpty();
    }

    @Test
    @DisplayName("F2：候选选定后证据变成新鲜的——旧候选不得把新鲜样本判成过期")
    void settleExpiredDoesNotExpireEvidenceThatBecameFresh() throws Exception {
        long hostId = insertHost(92096L, "it-wb-expiry-fresh", "192.0.2.31", "ONLINE");
        long configId = insertEnabledConfig(92096L, hostId);
        CollectionTargetRef ref = hostRef(hostId, "192.0.2.31", configId);

        assertThat(writeBackService.writeBack(ref, success("ONLINE", "UNKNOWN", at(0))))
                .isEqualTo(WriteOutcome.APPLIED);

        OffsetDateTime staleBefore = at(5);
        assertThat(stateMapper.selectExpiredTargets(staleBefore)).extracting(MonitorCollectionState::getTargetId)
                .containsExactly(hostId);

        // 候选选出后，另一个执行者提交了一条**不再过期**的新鲜观测
        assertThat(writeBackService.writeBack(ref, success("OFFLINE", "UNKNOWN", at(6))))
                .isEqualTo(WriteOutcome.APPLIED);

        assertThat(writeBackService.settleExpiredTarget(CollectionTargetType.HOST, hostId, staleBefore))
                .as("锁内读到的 last_success_at 已经晚于阈值：这不是过期证据，不该被标记")
                .isFalse();
        assertThat(stateMapper.selectByTarget(CollectionTargetType.HOST, hostId).getLastResult())
                .as("新鲜样本不得被旧候选标成 EXPIRED")
                .isEqualTo("SUCCESS");
        assertThat(hostStatusOf(hostId))
                .as("新鲜样本写入的目标状态不得被复位")
                .isEqualTo("OFFLINE");
        assertThat(stateMapper.selectExpiredTargets(staleBefore))
                .as("它本来就不再过期")
                .isEmpty();
    }

    @Test
    @DisplayName("F2：实例的过期结算按锁内当前状态与健康一起复位")
    void settleExpiredResetsInstanceFromCurrentStatusAndHealth() throws Exception {
        long hostId = insertHost(92097L, "it-wb-expiry-instance", "192.0.2.32", "ONLINE");
        long configId = insertEnabledConfig(92097L, hostId);
        long instanceId = insertInstance(92097L, hostId, "JAVA", "RUNNING", "UP");
        CollectionTargetRef ref = instanceRef(instanceId, hostId, "JAVA", "192.0.2.32", configId);

        assertThat(writeBackService.writeBack(ref, success("RUNNING", "UP", at(0))))
                .isEqualTo(WriteOutcome.APPLIED);

        OffsetDateTime staleBefore = at(5);
        assertThat(stateMapper.selectExpiredTargets(staleBefore)).extracting(MonitorCollectionState::getTargetId)
                .containsExactly(instanceId);

        // 候选选出后证据被刷新：状态与健康都变了，且仍早于阈值
        assertThat(writeBackService.writeBack(ref, success("STOPPED", "DOWN", at(1))))
                .isEqualTo(WriteOutcome.APPLIED);
        assertThat(instanceStatusOf(instanceId)).isEqualTo("STOPPED");
        assertThat(instanceHealthOf(instanceId)).isEqualTo("DOWN");

        assertThat(writeBackService.settleExpiredTarget(CollectionTargetType.SERVICE_INSTANCE, instanceId, staleBefore))
                .isTrue();

        assertThat(instanceStatusOf(instanceId))
                .as("状态必须按锁内当前证据复位，而不是按候选快照的 RUNNING")
                .isEqualTo("UNKNOWN");
        assertThat(instanceHealthOf(instanceId))
                .as("健康维度必须一起复位")
                .isEqualTo("UNKNOWN");
        assertThat(stateMapper.selectByTarget(CollectionTargetType.SERVICE_INSTANCE, instanceId).getLastResult())
                .isEqualTo("EXPIRED");
    }

    @Test
    @DisplayName("F2：结算期间目标值被别的来源改过——条件复位不抹掉别人的结论")
    void settleExpiredDoesNotOverwriteAValueWrittenByAnotherSource() throws Exception {
        long hostId = insertHost(92098L, "it-wb-expiry-foreign", "192.0.2.33", "ONLINE");
        long configId = insertEnabledConfig(92098L, hostId);
        CollectionTargetRef ref = hostRef(hostId, "192.0.2.33", configId);

        assertThat(writeBackService.writeBack(ref, success("ONLINE", "UNKNOWN", at(0))))
                .isEqualTo(WriteOutcome.APPLIED);

        OffsetDateTime staleBefore = at(5);
        assertThat(stateMapper.selectExpiredTargets(staleBefore)).extracting(MonitorCollectionState::getTargetId)
                .containsExactly(hostId);

        // 别的来源（不是采集）把目标状态改成了自己的结论
        execute("UPDATE asset_host SET host_status = 'MAINTENANCE' WHERE id = " + hostId);

        assertThat(writeBackService.settleExpiredTarget(CollectionTargetType.HOST, hostId, staleBefore))
                .as("条件复位命中 0 行：没有真正复位，如实返回 false")
                .isFalse();
        assertThat(hostStatusOf(hostId))
                .as("采集已不是该值的所有者，不得抹掉别的来源的结论")
                .isEqualTo("MAINTENANCE");
        assertThat(stateMapper.selectByTarget(CollectionTargetType.HOST, hostId).getLastResult())
                .as("标记本身照常完成：证据确实过期了")
                .isEqualTo("EXPIRED");
    }

    // ---------- R3：采样依据的身份与配置版本 ----------

    @Test
    @DisplayName("R3：配置 id 不变但内容被改（updated_at 前进）时，旧样本被拒收")
    void editedConfigInvalidatesTheSample() throws Exception {
        Fixture fixture = fixture(92010L, "192.0.2.34");
        assertThat(enabledConfigOf(fixture.hostId()).getUpdatedAt().toInstant())
                .as("夹具写入的配置版本就是采样时依据的那个时刻")
                .isEqualTo(CONFIG_VERSION.toInstant());

        execute("UPDATE asset_host_ssh_config SET username = 'it-edited', updated_at = TIMESTAMPTZ "
                + "'2026-01-01 00:01:00+00' WHERE id = " + fixture.configId());

        assertThat(writeBackService.writeBack(fixture.ref("192.0.2.34"), success("STOPPED", "DOWN", at(10))))
                .as("同一条配置被改过，按旧参数探测的结论不得落到目标上")
                .isEqualTo(WriteOutcome.DISCARDED);
        assertThat(instanceStatusOf(fixture.instanceId())).isEqualTo("RUNNING");
        assertThat(stateMapper.selectByTarget(CollectionTargetType.SERVICE_INSTANCE, fixture.instanceId())).isNull();
    }

    @Test
    @DisplayName("R3：探测期间配置被禁用时，旧样本被拒收")
    void disabledConfigInvalidatesTheSample() throws Exception {
        Fixture fixture = fixture(92011L, "192.0.2.35");

        execute("UPDATE asset_host_ssh_config SET enabled = false WHERE id = " + fixture.configId());

        assertThat(writeBackService.writeBack(fixture.ref("192.0.2.35"), success("STOPPED", "DOWN", at(10))))
                .isEqualTo(WriteOutcome.DISCARDED);
        assertThat(instanceStatusOf(fixture.instanceId())).isEqualTo("RUNNING");
    }

    @Test
    @DisplayName("R3：探测期间配置被删除时，旧样本被拒收")
    void deletedConfigInvalidatesTheSample() throws Exception {
        Fixture fixture = fixture(92012L, "192.0.2.36");

        execute("DELETE FROM asset_host_ssh_config WHERE id = " + fixture.configId());

        assertThat(writeBackService.writeBack(fixture.ref("192.0.2.36"), success("STOPPED", "DOWN", at(10))))
                .isEqualTo(WriteOutcome.DISCARDED);
        assertThat(instanceStatusOf(fixture.instanceId())).isEqualTo("RUNNING");
    }

    @Test
    @DisplayName("R3：目标主机改址后，按旧地址采到的样本被拒收")
    void changedHostAddressInvalidatesTheSample() throws Exception {
        Fixture fixture = fixture(92013L, "192.0.2.37");

        execute("UPDATE asset_host SET ip = '192.0.2.38' WHERE id = " + fixture.hostId());

        assertThat(writeBackService.writeBack(fixture.ref("192.0.2.37"), success("STOPPED", "DOWN", at(10))))
                .as("样本来自旧地址，不能落到已经换了地址的目标上")
                .isEqualTo(WriteOutcome.DISCARDED);
        assertThat(instanceStatusOf(fixture.instanceId())).isEqualTo("RUNNING");
    }

    @Test
    @DisplayName("R3：实例被重新绑定到别的主机后，按旧绑定采到的样本被拒收")
    void reboundInstanceInvalidatesTheSample() throws Exception {
        Fixture fixture = fixture(92014L, "192.0.2.39");
        long otherHostId = insertHost(92090L, "it-r3-other-host", "192.0.2.40", "ONLINE");

        execute("UPDATE runtime_service_instance SET host_id = " + otherHostId + " WHERE id = " + fixture.instanceId());

        assertThat(writeBackService.writeBack(fixture.ref("192.0.2.39"), success("STOPPED", "DOWN", at(10))))
                .as("实例已属于别的主机，旧绑定下的样本必须作废")
                .isEqualTo(WriteOutcome.DISCARDED);
        assertThat(instanceStatusOf(fixture.instanceId())).isEqualTo("RUNNING");
    }

    @Test
    @DisplayName("R3：实例类型被改后，按旧类型采到的样本被拒收")
    void changedInstanceTypeInvalidatesTheSample() throws Exception {
        Fixture fixture = fixture(92015L, "192.0.2.41");

        execute("UPDATE runtime_service_instance SET instance_type = 'JVM' WHERE id = " + fixture.instanceId());

        assertThat(writeBackService.writeBack(fixture.ref("192.0.2.41"), success("STOPPED", "DOWN", at(10))))
                .as("探测手段与实例类型绑定，类型变了旧结论就不可比")
                .isEqualTo(WriteOutcome.DISCARDED);
        assertThat(instanceStatusOf(fixture.instanceId())).isEqualTo("RUNNING");
    }

    @Test
    @DisplayName("R3：配置未被改动时样本正常落库（拒收不是无条件的）")
    void unchangedConfigIsAccepted() throws Exception {
        Fixture fixture = fixture(92016L, "192.0.2.42");
        AssetHostSshConfig stored = enabledConfigOf(fixture.hostId());
        assertThat(stored.getUpdatedAt().toInstant()).isEqualTo(CONFIG_VERSION.toInstant());

        assertThat(writeBackService.writeBack(fixture.ref("192.0.2.42", stored.getUpdatedAt()),
                success("STOPPED", "DOWN", at(10))))
                .as("配置原样未动，样本必须落库")
                .isEqualTo(WriteOutcome.APPLIED);
        assertThat(instanceStatusOf(fixture.instanceId())).isEqualTo("STOPPED");
        assertThat(instanceHealthOf(fixture.instanceId())).isEqualTo("DOWN");
    }

    @Test
    @DisplayName("R3：配置版本按时刻比对——采样侧时区偏移与库里读回的不同，也不得误判为“配置改过”")
    void configVersionComparisonToleratesOffsetDifference() throws Exception {
        Fixture fixture = fixture(92017L, "192.0.2.43");
        OffsetDateTime readBack = enabledConfigOf(fixture.hostId()).getUpdatedAt();
        // 构造一个"时刻相同、时区偏移不同"的采样侧值：若用 OffsetDateTime.equals 比对，这里会被判成改过
        OffsetDateTime sameInstantOtherOffset = readBack.getOffset().equals(ZoneOffset.UTC)
                ? readBack.withOffsetSameInstant(ZoneOffset.ofHours(8))
                : readBack.withOffsetSameInstant(ZoneOffset.UTC);

        assertThat(sameInstantOtherOffset.getOffset()).isNotEqualTo(readBack.getOffset());
        assertThat(sameInstantOtherOffset.toInstant()).as("两个值的时刻必须完全一致").isEqualTo(readBack.toInstant());

        assertThat(writeBackService.writeBack(fixture.ref("192.0.2.43", sameInstantOtherOffset),
                success("STOPPED", "DOWN", at(10))))
                .as("按时刻比对才是本意：时区偏移不同不代表配置被改过")
                .isEqualTo(WriteOutcome.APPLIED);
        assertThat(instanceStatusOf(fixture.instanceId())).isEqualTo("STOPPED");
    }

    // ---------- R4：首次失败与失败阈值 ----------

    @Test
    @DisplayName("R4：空状态表上的第一次失败记为 1（不是 0），且阈值未到不动目标表")
    void firstFailureOnEmptyStateCountsOne() throws Exception {
        long hostId = insertHost(92020L, "it-r4-first", "192.0.2.44", "ONLINE");
        long configId = insertEnabledConfig(92020L, hostId);
        assertThat(stateMapper.selectByTarget(CollectionTargetType.HOST, hostId)).isNull();

        assertThat(writeBackService.writeBack(hostRef(hostId, "192.0.2.44", configId), failure(at(1))))
                .isEqualTo(WriteOutcome.RECORDED_ONLY);

        MonitorCollectionState state = stateMapper.selectByTarget(CollectionTargetType.HOST, hostId);
        assertThat(state.getConsecutiveFailureCount())
                .as("首次失败必须记 1；记 0 会让达到阈值多用一轮")
                .isEqualTo(1);
        assertThat(state.getLastResult()).isEqualTo(CollectionResult.FAILED.name());
        assertThat(state.getLastSuccessAt()).as("失败不推进最近成功时间").isNull();
        assertThat(hostStatusOf(hostId)).as("阈值=3 未到，目标状态保持原样").isEqualTo("ONLINE");
    }

    @Test
    @DisplayName("R4：阈值=1 时首次失败即复位；但从未成功观测过的目标没有可复位值")
    void thresholdOneResetsOnFirstFailureAfterAnObservation() throws Exception {
        properties.setFailureThreshold(1);
        long hostId = insertHost(92021L, "it-r4-one", "192.0.2.45", "ONLINE");
        long configId = insertEnabledConfig(92021L, hostId);
        CollectionTargetRef ref = hostRef(hostId, "192.0.2.45", configId);

        assertThat(writeBackService.writeBack(ref, failure(at(1)))).isEqualTo(WriteOutcome.RECORDED_ONLY);
        assertThat(stateMapper.selectByTarget(CollectionTargetType.HOST, hostId).getConsecutiveFailureCount()).isEqualTo(1);
        assertThat(hostStatusOf(hostId))
                .as("还没观测到过任何值，就没有我们写入的值可复位")
                .isEqualTo("ONLINE");

        assertThat(writeBackService.writeBack(ref, success("OFFLINE", "UNKNOWN", at(2))))
                .isEqualTo(WriteOutcome.APPLIED);
        assertThat(hostStatusOf(hostId)).isEqualTo("OFFLINE");

        assertThat(writeBackService.writeBack(ref, failure(at(3))))
                .as("阈值=1，这一次失败就该复位")
                .isEqualTo(WriteOutcome.RESET);
        assertThat(stateMapper.selectByTarget(CollectionTargetType.HOST, hostId).getConsecutiveFailureCount())
                .as("成功那次把计数清零，本次失败重新从 1 起算")
                .isEqualTo(1);
        assertThat(hostStatusOf(hostId)).isEqualTo(CollectionWriteBackService.STATUS_UNKNOWN);
    }

    @Test
    @DisplayName("R4：阈值=3 时恰好第三次失败才复位，复位后重复失败不再改动目标表")
    void thresholdThreeNeedsExactlyThreeFailures() throws Exception {
        long hostId = insertHost(92022L, "it-r4-three", "192.0.2.46", "ONLINE");
        long configId = insertEnabledConfig(92022L, hostId);
        CollectionTargetRef ref = hostRef(hostId, "192.0.2.46", configId);

        assertThat(writeBackService.writeBack(ref, success("ONLINE", "UNKNOWN", at(0))))
                .isEqualTo(WriteOutcome.APPLIED);

        for (int attempt = 1; attempt <= 2; attempt++) {
            assertThat(writeBackService.writeBack(ref, failure(at(attempt))))
                    .as("第 %s 次失败还没到阈值", attempt)
                    .isEqualTo(WriteOutcome.RECORDED_ONLY);
            assertThat(stateMapper.selectByTarget(CollectionTargetType.HOST, hostId).getConsecutiveFailureCount())
                    .isEqualTo(attempt);
            assertThat(hostStatusOf(hostId)).isEqualTo("ONLINE");
        }

        assertThat(writeBackService.writeBack(ref, failure(at(3))))
                .as("第 3 次失败达到阈值，应复位")
                .isEqualTo(WriteOutcome.RESET);
        assertThat(stateMapper.selectByTarget(CollectionTargetType.HOST, hostId).getConsecutiveFailureCount()).isEqualTo(3);
        assertThat(hostStatusOf(hostId)).isEqualTo(CollectionWriteBackService.STATUS_UNKNOWN);

        assertThat(writeBackService.writeBack(ref, failure(at(4))))
                .as("目标值已不是采集写入的那个值，复位无可改写维度，只记录")
                .isEqualTo(WriteOutcome.RECORDED_ONLY);
        assertThat(stateMapper.selectByTarget(CollectionTargetType.HOST, hostId).getConsecutiveFailureCount()).isEqualTo(4);
        assertThat(hostStatusOf(hostId)).isEqualTo(CollectionWriteBackService.STATUS_UNKNOWN);
    }

    @Test
    @DisplayName("R4：跳过类结果把连续失败计数清零，且不谎称来自探测")
    void skipResultResetsTheFailureCounter() throws Exception {
        long hostId = insertHost(92023L, "it-r4-skip", "192.0.2.47", "ONLINE");
        long configId = insertEnabledConfig(92023L, hostId);

        writeBackService.writeBack(hostRef(hostId, "192.0.2.47", configId), failure(at(1)));
        writeBackService.writeBack(hostRef(hostId, "192.0.2.47", configId), failure(at(2)));
        assertThat(stateMapper.selectByTarget(CollectionTargetType.HOST, hostId).getConsecutiveFailureCount()).isEqualTo(2);

        assertThat(writeBackService.writeBack(hostRef(hostId, "192.0.2.47", null),
                CollectionObservation.unconfigured(OBSERVER_TYPE, at(3))))
                .isEqualTo(WriteOutcome.RECORDED_ONLY);

        MonitorCollectionState state = stateMapper.selectByTarget(CollectionTargetType.HOST, hostId);
        assertThat(state.getConsecutiveFailureCount()).as("跳过不是失败，计数应清零").isZero();
        assertThat(state.getLastResult()).isEqualTo(CollectionResult.SKIPPED_UNCONFIGURED.name());
        assertThat(state.getLastSource()).as("跳过没有真正探测过，不得记成探测来源").isEqualTo("NONE");
        assertThat(hostStatusOf(hostId)).isEqualTo("ONLINE");
    }

    // ---------- R6：单目标异常边界 ----------

    @Test
    @DisplayName("R6：单个目标写回抛异常时其余目标照常提交，失败目标修复后可重试")
    void oneFailingTargetDoesNotBlockTheRest() throws Exception {
        long hostId = insertHost(92030L, "it-r6-host", "192.0.2.48", "ONLINE");
        long configId = insertEnabledConfig(92030L, hostId);
        long first = insertInstance(92031L, hostId, "JAVA", "RUNNING", "UP");
        long failing = insertInstance(92032L, hostId, "JAVA", "RUNNING", "UP");
        long last = insertInstance(92033L, hostId, "JAVA", "RUNNING", "UP");

        installFailureInjection("OLD.id = " + failing);

        List<Long> ids = List.of(first, failing, last);
        List<String> failures = new ArrayList<>();
        int applied = 0;
        for (long targetId : ids) {
            // 与编排层相同的边界：每目标一个短事务，异常既不吞掉、也不外溢到其余目标
            try {
                WriteOutcome outcome = writeBackService.writeBack(
                        instanceRef(targetId, hostId, "JAVA", "192.0.2.48", configId),
                        success("STOPPED", "DOWN", at(10)));
                if (outcome == WriteOutcome.APPLIED) {
                    applied++;
                }
            } catch (RuntimeException exception) {
                failures.add(targetId + ":" + exception.getClass().getSimpleName());
            }
        }

        assertThat(applied).as("其余两个目标应照常写入").isEqualTo(2);
        assertThat(failures).as("只有失败的那个目标报错，且异常没有被静默吞掉").hasSize(1);
        assertThat(failures.get(0)).startsWith(failing + ":");
        assertThat(stateMapper.selectByTarget(CollectionTargetType.SERVICE_INSTANCE, first)).isNotNull();
        assertThat(stateMapper.selectByTarget(CollectionTargetType.SERVICE_INSTANCE, last)).isNotNull();
        assertThat(stateMapper.selectByTarget(CollectionTargetType.SERVICE_INSTANCE, failing))
                .as("失败目标的采集状态随它自己的事务回滚，不影响同一批的其余目标")
                .isNull();
        assertThat(instanceStatusOf(first)).isEqualTo("STOPPED");
        assertThat(instanceStatusOf(last)).isEqualTo("STOPPED");
        assertThat(instanceStatusOf(failing)).isEqualTo("RUNNING");

        dropFailureInjectionTrigger();

        assertThat(writeBackService.writeBack(instanceRef(failing, hostId, "JAVA", "192.0.2.48", configId),
                success("STOPPED", "DOWN", at(11))))
                .as("失败的目标在下一轮应能正常写回")
                .isEqualTo(WriteOutcome.APPLIED);
        assertThat(instanceStatusOf(failing)).isEqualTo("STOPPED");
    }

    // ---------- R7：缺配置不得被算作探测失败（真实观测器 → 真实写回） ----------

    /**
     * 缺配置文案取自 {@code RuntimeServiceDiagnosticsService} 的真实抛出点，逐字照抄：
     * <ul>
     *   <li>{@code RuntimeServiceDiagnosticsService.java:130} —— {@code getRequiredSshTarget}，
     *       主机没有启用的 SSH 配置；</li>
     *   <li>{@code RuntimeServiceDiagnosticsService.java:151} —— {@code resolveRuntimeConfig}，
     *       SSH 配置就位但部署诊断参数没配齐。</li>
     * </ul>
     *
     * <p>为什么要逐字照抄：观测器的分类是**文本匹配**，用一句自己编的文案就测不出
     * "真实文案里到底命中了哪条规则"。抄错一个字，这里就会退化成只验证测试自己。
     */
    private static final String NO_SSH_CONFIG_MESSAGE =
            "目标主机(192.0.2.11)未配置 SSH 连接信息，请先在主机管理中配置 SSH 认证";

    private static final String NO_DIAGNOSTIC_CONFIG_MESSAGE =
            "服务(it-writeback-svc)未配置部署诊断参数，请在环境配置中添加 deploy.root、deploy.port 等参数";

    private static final String RUNNING_MARKER = "__INFRAHUB_RUNNING__=";

    /**
     * 主机探测的成功标记，逐字照抄自 {@code SshProcessCollectionObserver}：观测器只认 stdout 里
     * 有没有这个标记，桩里少一个字就会变成"探测未返回结果标记"，用例验的就不是成功路径了。
     */
    private static final String REACHABLE_MARKER = "__INFRAHUB_HOST_REACHABLE__=";

    @Test
    @DisplayName("R7：成功 RUNNING/UP 之后连续 3 轮缺诊断参数——不触发失败阈值、状态保持、零 SSH 调用")
    void diagnosticsConfigMissingNeverTripsTheFailureThreshold() throws Exception {
        long hostId = insertHost(92040L, "it-r7-diag", "192.0.2.11", "ONLINE");
        long configId = insertEnabledConfig(92040L, hostId);
        long instanceId = insertInstance(92041L, hostId, "PROCESS", "RUNNING", "UP");
        CollectionTargetRef ref = instanceRef(instanceId, hostId, "PROCESS", "192.0.2.11", configId);

        // 1) 先真的成功一次：RUNNING / UP
        stubDiagnosticsSuccess();
        collectionClock.advanceTo(at(1));
        assertThat(probeThenWriteBack(ref)).isEqualTo(WriteOutcome.APPLIED);
        assertThat(instanceStatusOf(instanceId)).isEqualTo("RUNNING");
        assertThat(instanceHealthOf(instanceId)).isEqualTo("UP");
        MonitorCollectionState afterSuccess = stateMapper.selectByTarget(CollectionTargetType.SERVICE_INSTANCE, instanceId);
        assertThat(afterSuccess.getLastResult()).isEqualTo(CollectionResult.SUCCESS.name());
        assertThat(afterSuccess.getLastSuccessAt().toInstant()).isEqualTo(at(1).toInstant());
        assertThat(afterSuccess.getLastSource()).isEqualTo("SSH_PROBE");

        // 2) 之后连续 3 轮（正好是失败阈值）诊断参数都没配齐
        stubDiagnosticsConfigMissing();
        for (int round = 1; round <= DEFAULT_FAILURE_THRESHOLD; round++) {
            collectionClock.advanceTo(at(1 + round));

            assertThat(probeThenWriteBack(ref))
                    .as("第 %s 轮缺配置不该产生任何目标侧改动", round)
                    .isEqualTo(WriteOutcome.RECORDED_ONLY);

            MonitorCollectionState state = stateMapper.selectByTarget(CollectionTargetType.SERVICE_INSTANCE, instanceId);
            assertThat(state.getLastResult())
                    .as("缺配置是跳过，不是失败 —— 这正是修前被误判成 FAILED 的那一处")
                    .isEqualTo(CollectionResult.SKIPPED_UNCONFIGURED.name());
            assertThat(state.getLastErrorCategory())
                    .isEqualTo(CollectionErrorCategory.DIAGNOSTIC_CONFIG_MISSING.name());
            assertThat(state.getConsecutiveFailureCount())
                    .as("修前这里是 round（第 3 轮就达到阈值并复位目标），修后必须恒为 0")
                    .isZero();
            assertThat(state.getLastSuccessAt().toInstant())
                    .as("跳过既不推进、也不清空历史成功时间：不伪造成功时刻")
                    .isEqualTo(at(1).toInstant());
            assertThat(state.getLastSource())
                    .as("没有发生真实探测，来源不得谎称 SSH 探测")
                    .isEqualTo("NONE");
            assertThat(state.getConfigPresent())
                    .as("SSH 连接配置确实在，缺的是诊断参数：config_present 保持 true")
                    .isTrue();
            assertThat(state.getLastErrorSummary())
                    .as("保留可定位的未配置原因")
                    .contains("未配置部署诊断参数");
            assertThat(instanceStatusOf(instanceId))
                    .as("修前第 3 轮会被失败阈值复位成 UNKNOWN")
                    .isEqualTo("RUNNING");
            assertThat(instanceHealthOf(instanceId)).as("健康维度同样保持").isEqualTo("UP");
        }

        verify(diagnosticsService, times(1 + DEFAULT_FAILURE_THRESHOLD)).getStatusForCollection(any(), anyLong());
        assertNoSshCall();
    }

    @Test
    @DisplayName("R7：从未成功观测过的目标连续缺配置——既不计数也不复位（首次未配置）")
    void firstTimeUnconfiguredNeverTouchesTargetState() throws Exception {
        long hostId = insertHost(92042L, "it-r7-first", "192.0.2.49", "ONLINE");
        long configId = insertEnabledConfig(92042L, hostId);
        long instanceId = insertInstance(92043L, hostId, "PROCESS", "STOPPED", "UNKNOWN");
        CollectionTargetRef ref = instanceRef(instanceId, hostId, "PROCESS", "192.0.2.49", configId);

        stubDiagnosticsConfigMissing();
        for (int round = 1; round <= DEFAULT_FAILURE_THRESHOLD + 1; round++) {
            collectionClock.advanceTo(at(10 + round));
            assertThat(probeThenWriteBack(ref)).isEqualTo(WriteOutcome.RECORDED_ONLY);
        }

        MonitorCollectionState state = stateMapper.selectByTarget(CollectionTargetType.SERVICE_INSTANCE, instanceId);
        assertThat(state.getConsecutiveFailureCount()).as("一轮都没算作失败").isZero();
        assertThat(state.getLastSuccessAt()).as("从未成功过，就不能凭空出现成功时间").isNull();
        assertThat(state.getLastResult()).isEqualTo(CollectionResult.SKIPPED_UNCONFIGURED.name());
        assertThat(state.getLastObservedStatus()).as("跳过不写观测结论").isNull();
        assertThat(state.getLastObservedHealth()).isNull();
        assertThat(instanceStatusOf(instanceId))
                .as("没有可复位的观测值，也不该把 STOPPED 改成 UNKNOWN")
                .isEqualTo("STOPPED");
        assertThat(instanceHealthOf(instanceId)).isEqualTo("UNKNOWN");
        assertNoSshCall();
    }

    @Test
    @DisplayName("R7：压根没有 SSH 配置——config_present=false，且同样不计数、不改状态")
    void missingSshConfigIsAlsoSkipNotFailure() throws Exception {
        long hostId = insertHost(92044L, "it-r7-noconfig", "192.0.2.50", "ONLINE");
        long instanceId = insertInstance(92045L, hostId, "PROCESS", "RUNNING", "UP");
        // 枚举侧拿不到启用配置：引用里没有 configId，与 HostInstanceCollectionService.enumerateTargets 的
        // newRef(..., config == null ? null : ...) 同形
        CollectionTargetRef ref = instanceRef(instanceId, hostId, "PROCESS", "192.0.2.50", null);

        for (int round = 1; round <= DEFAULT_FAILURE_THRESHOLD + 1; round++) {
            collectionClock.advanceTo(at(20 + round));
            assertThat(probeThenWriteBack(ref)).isEqualTo(WriteOutcome.RECORDED_ONLY);
        }

        MonitorCollectionState state = stateMapper.selectByTarget(CollectionTargetType.SERVICE_INSTANCE, instanceId);
        assertThat(state.getLastResult()).isEqualTo(CollectionResult.SKIPPED_UNCONFIGURED.name());
        assertThat(state.getLastErrorCategory())
                .as("这一类才是真的没有可用 SSH 配置，与缺诊断参数必须可区分")
                .isEqualTo(CollectionErrorCategory.CONFIG_MISSING.name());
        assertThat(state.getConfigPresent())
                .as("如实反映'没有连接配置'")
                .isFalse();
        assertThat(state.getConsecutiveFailureCount()).isZero();
        assertThat(state.getLastSource()).isEqualTo("NONE");
        assertThat(instanceStatusOf(instanceId)).isEqualTo("RUNNING");
        assertThat(instanceHealthOf(instanceId)).isEqualTo("UP");
        verify(diagnosticsService, never()).getStatusForCollection(any(), anyLong());
        assertNoSshCall();
    }

    @Test
    @DisplayName("R7：真实认证失败仍按原规则累计，第 3 次照样按阈值复位（缺配置的跳过不影响它）")
    void realAuthFailureStillTripsTheThreshold() throws Exception {
        long hostId = insertHost(92046L, "it-r7-auth", "192.0.2.51", "ONLINE");
        long configId = insertEnabledConfig(92046L, hostId);
        long instanceId = insertInstance(92047L, hostId, "PROCESS", "RUNNING", "UP");
        CollectionTargetRef ref = instanceRef(instanceId, hostId, "PROCESS", "192.0.2.51", configId);

        stubDiagnosticsSuccess();
        collectionClock.advanceTo(at(30));
        assertThat(probeThenWriteBack(ref)).isEqualTo(WriteOutcome.APPLIED);

        stubDiagnosticsFailure("Auth fail for user deploy");
        for (int round = 1; round <= 2; round++) {
            collectionClock.advanceTo(at(30 + round));
            assertThat(probeThenWriteBack(ref))
                    .as("第 %s 次真实失败还没到阈值", round)
                    .isEqualTo(WriteOutcome.RECORDED_ONLY);
            MonitorCollectionState state = stateMapper.selectByTarget(CollectionTargetType.SERVICE_INSTANCE, instanceId);
            assertThat(state.getConsecutiveFailureCount()).isEqualTo(round);
            assertThat(state.getLastErrorCategory()).isEqualTo(CollectionErrorCategory.AUTH_FAILED.name());
            assertThat(state.getLastSuccessAt().toInstant())
                    .as("瞬时失败不清除最近成功时间")
                    .isEqualTo(at(30).toInstant());
            assertThat(instanceStatusOf(instanceId)).isEqualTo("RUNNING");
        }

        collectionClock.advanceTo(at(33));
        assertThat(probeThenWriteBack(ref))
                .as("真实失败涨到阈值就该复位：修缺配置没有把真失败也一起放过")
                .isEqualTo(WriteOutcome.RESET);
        assertThat(instanceStatusOf(instanceId)).isEqualTo(CollectionWriteBackService.STATUS_UNKNOWN);
        assertThat(instanceHealthOf(instanceId)).isEqualTo(CollectionWriteBackService.STATUS_UNKNOWN);
        assertThat(stateMapper.selectByTarget(CollectionTargetType.SERVICE_INSTANCE, instanceId)
                .getConsecutiveFailureCount()).isEqualTo(DEFAULT_FAILURE_THRESHOLD);
        assertNoSshCall();
    }

    @Test
    @DisplayName("R7：缺配置若干轮后诊断参数补齐——观察恢复 SUCCESS，计数归零、成功时间推进")
    void configRecoveredSucccesContinuesFromTheSkip() throws Exception {
        long hostId = insertHost(92048L, "it-r7-recover", "192.0.2.52", "ONLINE");
        long configId = insertEnabledConfig(92048L, hostId);
        long instanceId = insertInstance(92049L, hostId, "PROCESS", "RUNNING", "UP");
        CollectionTargetRef ref = instanceRef(instanceId, hostId, "PROCESS", "192.0.2.52", configId);

        stubDiagnosticsSuccess();
        collectionClock.advanceTo(at(40));
        assertThat(probeThenWriteBack(ref)).isEqualTo(WriteOutcome.APPLIED);

        stubDiagnosticsConfigMissing();
        for (int round = 1; round <= DEFAULT_FAILURE_THRESHOLD; round++) {
            collectionClock.advanceTo(at(40 + round));
            assertThat(probeThenWriteBack(ref)).isEqualTo(WriteOutcome.RECORDED_ONLY);
        }

        stubDiagnosticsSuccess();
        collectionClock.advanceTo(at(50));
        assertThat(probeThenWriteBack(ref))
                .as("配置补齐后应立刻恢复正常观测")
                .isEqualTo(WriteOutcome.APPLIED);

        MonitorCollectionState state = stateMapper.selectByTarget(CollectionTargetType.SERVICE_INSTANCE, instanceId);
        assertThat(state.getLastResult()).isEqualTo(CollectionResult.SUCCESS.name());
        assertThat(state.getLastSuccessAt().toInstant()).as("成功时间推进到恢复那一刻").isEqualTo(at(50).toInstant());
        assertThat(state.getLastSource()).isEqualTo("SSH_PROBE");
        assertThat(state.getConsecutiveFailureCount()).isZero();
        assertThat(instanceStatusOf(instanceId)).isEqualTo("RUNNING");
        assertThat(instanceHealthOf(instanceId)).isEqualTo("UP");
        assertNoSshCall();
    }

    @Test
    @DisplayName("R7：跳过后不借失败阈值清状态，但 90 秒正常过期规则独立适用")
    void normalExpiryStillAppliesAfterSkips() throws Exception {
        long hostId = insertHost(92050L, "it-r7-expiry", "192.0.2.53", "ONLINE");
        long configId = insertEnabledConfig(92050L, hostId);
        long instanceId = insertInstance(92051L, hostId, "PROCESS", "RUNNING", "UP");
        CollectionTargetRef ref = instanceRef(instanceId, hostId, "PROCESS", "192.0.2.53", configId);

        stubDiagnosticsSuccess();
        collectionClock.advanceTo(at(60));
        assertThat(probeThenWriteBack(ref)).isEqualTo(WriteOutcome.APPLIED);

        stubDiagnosticsConfigMissing();
        for (int round = 1; round <= DEFAULT_FAILURE_THRESHOLD + 2; round++) {
            collectionClock.advanceTo(at(60 + round));
            assertThat(probeThenWriteBack(ref)).isEqualTo(WriteOutcome.RECORDED_ONLY);
        }
        assertThat(instanceStatusOf(instanceId))
                .as("跳过的轮数超过阈值也不会复位")
                .isEqualTo("RUNNING");

        // 过期结算与失败阈值是两条独立规则：这里直接调用写回层的结算入口，
        // staleBefore 取自"最近成功之后"（真实编排里是 now - staleThresholdMs）
        assertThat(writeBackService.settleExpiredTarget(CollectionTargetType.SERVICE_INSTANCE, instanceId, at(61)))
                .as("证据已超出新鲜度阈值，应可结算")
                .isTrue();

        MonitorCollectionState state = stateMapper.selectByTarget(CollectionTargetType.SERVICE_INSTANCE, instanceId);
        assertThat(state.getLastResult()).isEqualTo(CollectionResult.EXPIRED.name());
        assertThat(instanceStatusOf(instanceId))
                .as("过期复位用的是最近成功观测值 RUNNING，说明跳过没有把它清掉")
                .isEqualTo(CollectionWriteBackService.STATUS_UNKNOWN);
        assertThat(instanceHealthOf(instanceId)).isEqualTo(CollectionWriteBackService.STATUS_UNKNOWN);
        assertNoSshCall();
    }

    // ---------- R8：准入快照绑定到实际拨号（写回侧覆盖新维度 + 取消不计失败） ----------

    /**
     * R8 的取样口径：**真实观测器 + 真实写回**（{@link #probeThenWriteBack}），
     * 观测器的拨号两端也都是真的（{@link CollectionDialResolver} 直接读本用例的隔离库），
     * 只有最底层的 {@link SshExecutorService} 是桩 —— 它同时是"到底有没有拨出去"的判据。
     *
     * <p>R3 已经验过"配置/身份被改过就拒收"。本轮补的三类维度是准入快照**新增**的：
     * SSH 生效端口、实例所属服务、实例所属环境。后两者决定的不只是"连哪儿"，
     * 更是"用哪一份诊断参数"—— 所以它们必须与地址一样参与写回比对。
     */

    @Test
    @DisplayName("R8：SSH 生效端口在采样后被单独改写（配置版本时刻没动）——旧样本被拒收")
    void changedSshPortInvalidatesTheSample() throws Exception {
        Fixture fixture = fixture(92070L, "192.0.2.54");

        execute("UPDATE asset_host_ssh_config SET ssh_port = 2222 WHERE id = " + fixture.configId());

        assertThat(writeBackService.writeBack(fixture.ref("192.0.2.54"), success("STOPPED", "DOWN", at(10))))
                .as("端口是拨号端点的一部分：只改端口、不改 updated_at，旧样本也不得落到目标上")
                .isEqualTo(WriteOutcome.DISCARDED);
        assertThat(instanceStatusOf(fixture.instanceId())).isEqualTo("RUNNING");
        assertThat(stateMapper.selectByTarget(CollectionTargetType.SERVICE_INSTANCE, fixture.instanceId())).isNull();
    }

    @Test
    @DisplayName("R8：实例在采样后被改绑到别的服务——按旧服务诊断参数采到的样本被拒收")
    void changedInstanceServiceInvalidatesTheSample() throws Exception {
        Fixture fixture = fixture(92071L, "192.0.2.55");
        seedSecondServiceAndEnv();

        execute("UPDATE runtime_service_instance SET service_id = 2 WHERE id = " + fixture.instanceId());

        assertThat(writeBackService.writeBack(fixture.ref("192.0.2.55"), success("STOPPED", "DOWN", at(10))))
                .as("服务决定这次探测用的是哪一份诊断参数：换了服务，旧结论描述的是另一套部署")
                .isEqualTo(WriteOutcome.DISCARDED);
        assertThat(instanceStatusOf(fixture.instanceId())).isEqualTo("RUNNING");
    }

    @Test
    @DisplayName("R8：实例在采样后被改绑到别的环境——同样被拒收")
    void changedInstanceEnvInvalidatesTheSample() throws Exception {
        Fixture fixture = fixture(92072L, "192.0.2.56");
        seedSecondServiceAndEnv();

        execute("UPDATE runtime_service_instance SET env_id = 2 WHERE id = " + fixture.instanceId());

        assertThat(writeBackService.writeBack(fixture.ref("192.0.2.56"), success("STOPPED", "DOWN", at(10))))
                .as("环境与归属一样决定诊断参数来源，换环境即换了一份参数")
                .isEqualTo(WriteOutcome.DISCARDED);
        assertThat(instanceStatusOf(fixture.instanceId())).isEqualTo("RUNNING");
    }

    @Test
    @DisplayName("R8：迟到样本原样带回采样时的 ref，写回仍按当前库比对；拒收且不覆盖更新的结论")
    void lateSampleCarryingTheOriginalRefIsDiscardedAndDoesNotOverwriteTheNewerConclusion() throws Exception {
        Fixture fixture = fixture(92073L, "192.0.2.57");
        assertThat(writeBackService.writeBack(fixture.ref("192.0.2.57"), success("STOPPED", "DOWN", at(5))))
                .isEqualTo(WriteOutcome.APPLIED);
        assertThat(instanceStatusOf(fixture.instanceId())).isEqualTo("STOPPED");

        // 采样之后目标被改址；这一份迟到样本仍原样携带采样那一刻的 ref（地址还是旧地址）
        execute("UPDATE asset_host SET ip = '192.0.2.58' WHERE id = " + fixture.hostId());

        assertThat(writeBackService.writeBack(fixture.ref("192.0.2.57"), success("RUNNING", "UP", at(2))))
                .as("携带原 ref 不等于放行：写回拿它比对**当前**库，对不上就拒收")
                .isEqualTo(WriteOutcome.DISCARDED);

        MonitorCollectionState state = stateMapper.selectByTarget(CollectionTargetType.SERVICE_INSTANCE,
                fixture.instanceId());
        assertThat(state.getLastSuccessAt().toInstant())
                .as("更新的成功时刻不被旧样本改写")
                .isEqualTo(at(5).toInstant());
        assertThat(state.getObservedAt().toInstant())
                .as("库里那一笔样本仍是 at(5)，没有被 at(2) 顶掉")
                .isEqualTo(at(5).toInstant());
        assertThat(state.getLastObservedStatus()).isEqualTo("STOPPED");
        assertThat(state.getDiscardedSampleCount())
                .as("库中确有比它更新的样本时才计这一笔拒收（迟到样本的口径）")
                .isEqualTo(1);
        assertThat(instanceStatusOf(fixture.instanceId())).as("目标状态仍是最新结论").isEqualTo("STOPPED");
        assertThat(instanceHealthOf(fixture.instanceId())).isEqualTo("DOWN");
    }

    @Test
    @DisplayName("R8：准入后主机改址——真实观测器在拨号前取消：失败计数恒为 0、成功时刻与来源不被刷新、主机状态不动；真实失败仍照常累计到阈值")
    void admissionChangeCancelsBeforeDialingAndRealFailuresStillAccumulate() throws Exception {
        long hostId = insertHost(92074L, "it-r8-cancel", "192.0.2.59", "ONLINE");
        long configId = insertEnabledConfig(92074L, hostId);
        CollectionTargetRef ref = hostRef(hostId, "192.0.2.59", configId);

        doReturn(SshExecutionResult.success(REACHABLE_MARKER + "true\n", "", at(1), at(1)))
                .when(sshExecutorService).execute(any());
        collectionClock.advanceTo(at(1));
        assertThat(probeThenWriteBack(ref)).isEqualTo(WriteOutcome.APPLIED);
        MonitorCollectionState afterSuccess = stateMapper.selectByTarget(CollectionTargetType.HOST, hostId);
        assertThat(afterSuccess.getLastSuccessAt().toInstant()).isEqualTo(at(1).toInstant());
        assertThat(afterSuccess.getLastSource()).isEqualTo("SSH_PROBE");

        // 采样那一刻之后主机换了地址：这正是"准入快照与实际拨号脱钩"要拦住的那一类变化
        execute("UPDATE asset_host SET ip = '192.0.2.58' WHERE id = " + hostId);

        for (int round = 1; round <= DEFAULT_FAILURE_THRESHOLD + 1; round++) {
            collectionClock.advanceTo(at(1 + round));
            assertThat(probeThenWriteBack(ref))
                    .as("第 %s 轮：准入已变更，本轮在拨号前取消，不产生任何目标侧改动", round)
                    .isEqualTo(WriteOutcome.DISCARDED);
            assertCancelDidNotTouchAnything(hostId, at(1));
            verify(sshExecutorService, times(1)).execute(any());
        }
        assertThat(hostStatusOf(hostId))
                .as("取消轮数已超过失败阈值，主机仍未被判成离线/无法确认")
                .isEqualTo("ONLINE");

        // 再补一轮真正的迟到样本：观测时刻比库里现有的样本更早，这一笔拒收要计入拒收计数
        collectionClock.advanceTo(at(0));
        assertThat(probeThenWriteBack(ref)).isEqualTo(WriteOutcome.DISCARDED);
        assertCancelDidNotTouchAnything(hostId, at(1));
        assertThat(stateMapper.selectByTarget(CollectionTargetType.HOST, hostId).getDiscardedSampleCount())
                .as("库中确有更新的样本时才计入拒收，口径与迟到样本一致")
                .isEqualTo(1);

        // 真实失败仍按既有规则累计：前面几轮的取消没有垫高计数，所以这里必须正好从 1 数起
        execute("UPDATE asset_host SET ip = '192.0.2.59' WHERE id = " + hostId);
        collectionClock.advanceTo(at(10));
        doThrow(new BizException(ErrorCode.INTERNAL_ERROR, "connect timed out")).when(sshExecutorService).execute(any());
        assertThat(probeThenWriteBack(ref)).as("第 1 次真实失败还没到阈值").isEqualTo(WriteOutcome.RECORDED_ONLY);
        MonitorCollectionState firstFailure = stateMapper.selectByTarget(CollectionTargetType.HOST, hostId);
        assertThat(firstFailure.getConsecutiveFailureCount())
                .as("取消那几轮一轮都没算成失败，所以这里正好是 1")
                .isEqualTo(1);
        assertThat(firstFailure.getLastErrorCategory()).isEqualTo(CollectionErrorCategory.NETWORK_TIMEOUT.name());
        assertThat(firstFailure.getLastSuccessAt().toInstant())
                .as("瞬时失败不清除最近成功时刻")
                .isEqualTo(at(1).toInstant());

        doThrow(new BizException(ErrorCode.INTERNAL_ERROR, "Auth fail for user deploy")).when(sshExecutorService).execute(any());
        collectionClock.advanceTo(at(11));
        assertThat(probeThenWriteBack(ref)).isEqualTo(WriteOutcome.RECORDED_ONLY);
        MonitorCollectionState secondFailure = stateMapper.selectByTarget(CollectionTargetType.HOST, hostId);
        assertThat(secondFailure.getConsecutiveFailureCount()).isEqualTo(2);
        assertThat(secondFailure.getLastErrorCategory()).isEqualTo(CollectionErrorCategory.AUTH_FAILED.name());

        collectionClock.advanceTo(at(12));
        assertThat(probeThenWriteBack(ref))
                .as("真实失败涨到阈值就该复位：取消没有把真失败也一起放过")
                .isEqualTo(WriteOutcome.RESET);
        assertThat(hostStatusOf(hostId)).isEqualTo(CollectionWriteBackService.STATUS_UNKNOWN);
        assertThat(stateMapper.selectByTarget(CollectionTargetType.HOST, hostId).getConsecutiveFailureCount())
                .isEqualTo(DEFAULT_FAILURE_THRESHOLD);
        verify(sshExecutorService, times(1 + DEFAULT_FAILURE_THRESHOLD)).execute(any());
    }

    @Test
    @DisplayName("R8：准入后 SSH 配置被停用——观测器同样在拨号前取消，且不退化成'缺配置'口径")
    void disabledConfigCancelBeforeDialingLeavesStateAndTargetUntouched() throws Exception {
        long hostId = insertHost(92075L, "it-r8-disabled", "192.0.2.60", "ONLINE");
        long configId = insertEnabledConfig(92075L, hostId);
        CollectionTargetRef ref = hostRef(hostId, "192.0.2.60", configId);

        doReturn(SshExecutionResult.success(REACHABLE_MARKER + "true\n", "", at(20), at(20)))
                .when(sshExecutorService).execute(any());
        collectionClock.advanceTo(at(20));
        assertThat(probeThenWriteBack(ref)).isEqualTo(WriteOutcome.APPLIED);

        execute("UPDATE asset_host_ssh_config SET enabled = false WHERE id = " + configId);
        collectionClock.advanceTo(at(21));

        assertThat(probeThenWriteBack(ref))
                .as("配置被停用属于准入失效，与'这台主机压根没配过 SSH'是两回事")
                .isEqualTo(WriteOutcome.DISCARDED);
        assertCancelDidNotTouchAnything(hostId, at(20));
        assertThat(stateMapper.selectByTarget(CollectionTargetType.HOST, hostId).getLastErrorCategory())
                .as("上一轮是真实成功；取消不得凭空给它添一个错误类别")
                .isNull();
        verify(sshExecutorService, times(1)).execute(any());
    }

    /**
     * 取消样本必须一个字段都没动：失败计数、最近结论、成功时刻、来源、配置在位标记、主机状态。
     *
     * <p>这六项正是"取消被误判成失败"会一起崩掉的地方：一旦回落到 FAILED，失败计数会推进，
     * 涨到阈值就把主机状态复位成无法确认 —— 而这台主机可能从头到尾一次都没拨通过。
     */
    private static void assertCancelDidNotTouchAnything(long hostId, OffsetDateTime successAt) throws Exception {
        MonitorCollectionState state = stateMapper.selectByTarget(CollectionTargetType.HOST, hostId);
        assertThat(state.getConsecutiveFailureCount()).as("取消不是失败，绝不推进失败计数").isZero();
        assertThat(state.getLastSuccessAt().toInstant())
                .as("没有拨号，不得刷新成功时刻")
                .isEqualTo(successAt.toInstant());
        assertThat(state.getLastSource())
                .as("没有发生真实探测：来源仍是上一次真实探测，不得被伪造成新来源")
                .isEqualTo("SSH_PROBE");
        assertThat(state.getConfigPresent())
                .as("采样时确实拿到了可用配置，取消不是'从来没配好'")
                .isTrue();
        assertThat(state.getLastResult()).as("取消不改写最近一次结论").isEqualTo(CollectionResult.SUCCESS.name());
        assertThat(state.getLastObservedStatus()).isEqualTo("ONLINE");
        assertThat(hostStatusOf(hostId)).as("不借失败阈值把主机改写成离线").isEqualTo("ONLINE");
    }

    // ---------- R9：受限单轮入口在真实库上的写入边界 ----------

    /**
     * 本段的分工与前面几段不同：前面验的是"写回服务本身的一致性"，这一段验的是
     * **受限入口把范围限制真的落到了库上**——允许集外的行不仅不拨号，连一个列都不许动；
     * 允许集内的行则仍然走原来那套事务边界。
     *
     * <p>为什么必须落库验证：{@code WriteOutcome.OUT_OF_SCOPE} 只说明方法返回了什么。
     * "拒绝时零写入"是一个关于**数据库最终状态**的断言，尤其要盯住
     * {@code updated_at} 与 {@code discarded_sample_count} —— 只在内存里断言
     * {@code host_status} 没变，会漏掉"先记一次丢弃再声称没写"这种半截写入。
     */
    @Test
    @DisplayName("R9：受限入口端到端——允许集内的主机在真实库上写回 APPLIED，采集状态按原规则落库")
    void scopedRoundAppliesInScopeHostAgainstTheRealDatabase() throws Exception {
        long hostId = insertHost(92101L, "it-r9-in-scope", "192.0.2.61", "UNKNOWN");
        long configId = insertEnabledConfig(92101L, hostId);
        CollectionAdmissionPolicy policy =
                CollectionAdmissionPolicy.of(List.of(scopedHostRef(hostId, "192.0.2.61", configId)));
        doReturn(SshExecutionResult.success(REACHABLE_MARKER + "true\n", "", at(0), at(0)))
                .when(sshExecutorService).execute(any());

        CollectionScopedRunSummary summary = runScoped(policy);

        assertThat(summary.enumerated()).isEqualTo(1);
        assertThat(summary.admitted()).isEqualTo(1);
        assertThat(summary.probed()).isEqualTo(1);
        assertThat(summary.applied()).isEqualTo(1);
        assertThat(summary.rejected()).isZero();
        assertThat(summary.expiredSettled()).as("受限轮次跳过全部过期结算").isZero();
        assertThat(summary.expiredSettlementSkipped()).isTrue();

        assertThat(hostStatusOf(hostId)).isEqualTo("ONLINE");
        MonitorCollectionState state = stateMapper.selectByTarget(CollectionTargetType.HOST, hostId);
        assertThat(state.getLastResult()).isEqualTo(CollectionResult.SUCCESS.name());
        assertThat(state.getLastObservedStatus()).isEqualTo("ONLINE");
        assertThat(state.getConsecutiveFailureCount()).isZero();
        assertThat(state.getDiscardedSampleCount()).isZero();
        verify(sshExecutorService, times(1)).execute(any());
    }

    @Test
    @DisplayName("R9：采样后、写回事务前主机被改址——OUT_OF_SCOPE，且目标行与采集状态行（含丢弃计数）逐列不变")
    void scopedWriteBackBoundaryRejectsAfterSamplingAndWritesNothing() throws Exception {
        long hostId = insertHost(92102L, "it-r9-changed", "192.0.2.62", "ONLINE");
        long configId = insertEnabledConfig(92102L, hostId);
        CollectionTargetRef sampled = scopedHostRef(hostId, "192.0.2.62", configId);
        CollectionAdmissionPolicy policy = CollectionAdmissionPolicy.of(List.of(sampled));

        // 先真实落一条采集状态行，再把它做成"若被拒收就会暴露"的形态
        assertThat(writeBackService.writeBackScoped(sampled, success("ONLINE", "UNKNOWN", at(0)), policy))
                .isEqualTo(WriteOutcome.APPLIED);
        execute("UPDATE monitor_collection_state SET discarded_sample_count = 7 "
                + "WHERE target_type = 'HOST' AND target_id = " + hostId);

        // 采样已经成立；写回事务开始之前目标被改址。
        // 快照取在改址**之后**：这一步是测试自己做的，基线必须包含它，否则断言只是在比对我自己的改动。
        execute("UPDATE asset_host SET ip = '192.0.2.63' WHERE id = " + hostId);
        Map<String, Object> hostBefore = snapshotRow("SELECT * FROM asset_host WHERE id = " + hostId);
        Map<String, Object> stateBefore = snapshotRow(stateRowSql(hostId));

        assertThat(writeBackService.writeBackScoped(sampled, success("ONLINE", "UNKNOWN", at(1)), policy))
                .as("身份已变，写回边界必须拒收")
                .isEqualTo(WriteOutcome.OUT_OF_SCOPE);

        assertThat(snapshotRow("SELECT * FROM asset_host WHERE id = " + hostId))
                .as("目标行逐列不变（含 updated_at）").isEqualTo(hostBefore);
        Map<String, Object> stateAfter = snapshotRow(stateRowSql(hostId));
        assertThat(stateAfter).as("采集状态行逐列不变").isEqualTo(stateBefore);
        assertThat(stateAfter.get("discarded_sample_count"))
                .as("越界拒绝不得先记一次丢弃再声称没写").isEqualTo("7");
    }

    @Test
    @DisplayName("R9：同一轮里范围外主机与“等量换 id”的替身行逐列不变，且不产生采集状态行")
    void scopedRoundLeavesOutOfScopeAndReplacementTargetsByteIdentical() throws Exception {
        long inScope = insertHost(92103L, "it-r9-a", "192.0.2.64", "UNKNOWN");
        long inScopeConfig = insertEnabledConfig(92103L, inScope);
        long outOfScope = insertHost(92104L, "it-r9-b", "192.0.2.65", "ONLINE");
        insertEnabledConfig(92104L, outOfScope);
        long replacement = insertHost(92105L, "it-r9-c", "192.0.2.66", "OFFLINE");
        insertEnabledConfig(92105L, replacement);

        Map<String, Object> outBefore = snapshotRow("SELECT * FROM asset_host WHERE id = " + outOfScope);
        Map<String, Object> replacementBefore = snapshotRow("SELECT * FROM asset_host WHERE id = " + replacement);
        doReturn(SshExecutionResult.success(REACHABLE_MARKER + "true\n", "", at(0), at(0)))
                .when(sshExecutorService).execute(any());

        CollectionScopedRunSummary summary = runScoped(
                CollectionAdmissionPolicy.of(List.of(scopedHostRef(inScope, "192.0.2.64", inScopeConfig))));

        assertThat(summary.enumerated()).as("枚举是全量的").isEqualTo(3);
        assertThat(summary.admitted()).as("数量相同不等于没有越界").isEqualTo(1);
        assertThat(summary.applied()).isEqualTo(1);
        assertThat(summary.rejected()).isEqualTo(2);

        assertThat(countRows("SELECT count(*) FROM monitor_collection_state WHERE target_type = 'HOST' "
                + "AND target_id = " + outOfScope)).as("范围外目标不得留下采集状态行").isZero();
        assertThat(countRows("SELECT count(*) FROM monitor_collection_state WHERE target_type = 'HOST' "
                + "AND target_id = " + replacement)).as("替身目标同样不得留下采集状态行").isZero();
        assertThat(snapshotRow("SELECT * FROM asset_host WHERE id = " + outOfScope))
                .as("范围外主机逐列不变").isEqualTo(outBefore);
        assertThat(snapshotRow("SELECT * FROM asset_host WHERE id = " + replacement))
                .as("等量换 id 的替身行逐列不变").isEqualTo(replacementBefore);
        verify(sshExecutorService, times(1)).execute(any());
    }

    @Test
    @DisplayName("R9：范围外“已过期”行——受限轮次根本不做过期结算，目标表与采集状态逐列不变")
    void scopedRoundPerformsNoExpirySettlementAgainstTheRealDatabase() throws Exception {
        long inScope = insertHost(92106L, "it-r9-exp-a", "192.0.2.67", "UNKNOWN");
        long inScopeConfig = insertEnabledConfig(92106L, inScope);
        long expired = insertHost(92107L, "it-r9-exp-b", "192.0.2.68", "OFFLINE");
        long expiredConfig = insertEnabledConfig(92107L, expired);
        CollectionTargetRef expiredRef = scopedHostRef(expired, "192.0.2.68", expiredConfig);

        // 造一条"成功过、但证据已远超 stale_threshold"的状态行；若本轮走全量口径它必被结算
        assertThat(writeBackService.writeBackScoped(expiredRef, success("ONLINE", "UNKNOWN", at(0)),
                CollectionAdmissionPolicy.of(List.of(expiredRef)))).isEqualTo(WriteOutcome.APPLIED);
        execute("UPDATE monitor_collection_state SET last_success_at = TIMESTAMPTZ '2026-01-01 00:00:00+00', "
                + "observed_at = TIMESTAMPTZ '2026-01-01 00:00:00+00', last_result = 'SUCCESS' "
                + "WHERE target_type = 'HOST' AND target_id = " + expired);
        collectionClock.advanceTo(at(30));

        Map<String, Object> expiredHostBefore = snapshotRow("SELECT * FROM asset_host WHERE id = " + expired);
        Map<String, Object> expiredStateBefore = snapshotRow(stateRowSql(expired));
        doReturn(SshExecutionResult.success(REACHABLE_MARKER + "true\n", "", at(30), at(30)))
                .when(sshExecutorService).execute(any());

        CollectionScopedRunSummary summary = runScoped(
                CollectionAdmissionPolicy.of(List.of(scopedHostRef(inScope, "192.0.2.67", inScopeConfig))));

        assertThat(summary.applied()).isEqualTo(1);
        assertThat(summary.expiredSettled()).as("受限轮次跳过全部过期结算").isZero();
        assertThat(summary.expiredSettlementSkipped()).isTrue();

        assertThat(snapshotRow(stateRowSql(expired)))
                .as("范围外过期行逐列不变").isEqualTo(expiredStateBefore);
        assertThat(snapshotRow("SELECT * FROM asset_host WHERE id = " + expired))
                .as("不借过期结算把范围外主机复位").isEqualTo(expiredHostBefore);
    }

    @Test
    @DisplayName("R9：采样所依据的配置在写回前被改版本——OUT_OF_SCOPE，目标行与采集状态行逐列不变")
    void scopedWriteBackRejectsWhenTheSampledConfigWasEdited() throws Exception {
        long hostId = insertHost(92108L, "it-r9-cfg", "192.0.2.69", "ONLINE");
        long configId = insertEnabledConfig(92108L, hostId);
        CollectionTargetRef sampled = scopedHostRef(hostId, "192.0.2.69", configId);
        CollectionAdmissionPolicy policy = CollectionAdmissionPolicy.of(List.of(sampled));

        assertThat(writeBackService.writeBackScoped(sampled, success("ONLINE", "UNKNOWN", at(0)), policy))
                .isEqualTo(WriteOutcome.APPLIED);
        Map<String, Object> hostBefore = snapshotRow("SELECT * FROM asset_host WHERE id = " + hostId);
        Map<String, Object> stateBefore = snapshotRow(stateRowSql(hostId));

        execute("UPDATE asset_host_ssh_config SET updated_at = TIMESTAMPTZ '2026-01-01 00:01:00+00' "
                + "WHERE id = " + configId);

        assertThat(writeBackService.writeBackScoped(sampled, success("ONLINE", "UNKNOWN", at(1)), policy))
                .as("配置版本前进，旧样本必须被拒收").isEqualTo(WriteOutcome.OUT_OF_SCOPE);
        assertThat(snapshotRow("SELECT * FROM asset_host WHERE id = " + hostId)).isEqualTo(hostBefore);
        assertThat(snapshotRow(stateRowSql(hostId))).isEqualTo(stateBefore);
    }

    @Test
    @DisplayName("R9：实例在采样后被改绑到别的服务——OUT_OF_SCOPE，实例行与采集状态行逐列不变")
    void scopedWriteBackRejectsWhenTheInstanceChangedService() throws Exception {
        long hostId = insertHost(92109L, "it-r9-svc", "192.0.2.70", "RUNNING");
        long configId = insertEnabledConfig(92109L, hostId);
        long instanceId = insertInstance(92109L, hostId, "JAVA", "RUNNING", "UP");
        CollectionTargetRef sampled = instanceRef(instanceId, hostId, "JAVA", "192.0.2.70", configId);
        CollectionAdmissionPolicy policy = CollectionAdmissionPolicy.of(List.of(sampled));
        // 改绑目标需要的父行（base_service id=2）
        seedSecondServiceAndEnv();

        assertThat(writeBackService.writeBackScoped(sampled, success("RUNNING", "UP", at(0)), policy))
                .isEqualTo(WriteOutcome.APPLIED);

        // 同上：快照取在测试自己那次改绑之后，基线才不是"我的改动"
        execute("UPDATE runtime_service_instance SET service_id = 2 WHERE id = " + instanceId);
        Map<String, Object> instanceBefore =
                snapshotRow("SELECT * FROM runtime_service_instance WHERE id = " + instanceId);
        Map<String, Object> stateBefore = snapshotRow(stateRowSql(instanceId, CollectionTargetType.SERVICE_INSTANCE));

        assertThat(writeBackService.writeBackScoped(sampled, success("RUNNING", "UP", at(1)), policy))
                .as("服务归属已变，旧样本必须被拒收").isEqualTo(WriteOutcome.OUT_OF_SCOPE);
        assertThat(snapshotRow("SELECT * FROM runtime_service_instance WHERE id = " + instanceId))
                .as("实例行逐列不变").isEqualTo(instanceBefore);
        assertThat(snapshotRow(stateRowSql(instanceId, CollectionTargetType.SERVICE_INSTANCE)))
                .as("采集状态行逐列不变").isEqualTo(stateBefore);
    }

    @Test
    @DisplayName("R9：受限入口不改变普通失败的口径——首轮失败计数记为 1，阈值未到不动目标表")
    void scopedRoundKeepsTheOriginalFailureSemantics() throws Exception {
        long hostId = insertHost(92110L, "it-r9-fail", "192.0.2.71", "ONLINE");
        long configId = insertEnabledConfig(92110L, hostId);
        // 拨通了但探针标记不在输出里 → 观测器按既有规则判 FAILED（真实网络/认证失败的口径）
        doReturn(SshExecutionResult.success("bash: probe marker missing\n", "", at(0), at(0)))
                .when(sshExecutorService).execute(any());

        CollectionScopedRunSummary summary = runScoped(
                CollectionAdmissionPolicy.of(List.of(scopedHostRef(hostId, "192.0.2.71", configId))));

        assertThat(summary.probed()).isEqualTo(1);
        assertThat(summary.applied()).isZero();
        assertThat(summary.rejections()).as("普通失败不是越界拒绝").isEmpty();
        assertThat(summary.writeFailed()).as("FAILED 是正常写回结果，不是写回异常").isZero();

        MonitorCollectionState state = stateMapper.selectByTarget(CollectionTargetType.HOST, hostId);
        assertThat(state.getLastResult()).isEqualTo(CollectionResult.FAILED.name());
        assertThat(state.getConsecutiveFailureCount()).as("阈值=3，首次失败只记 1").isEqualTo(1);
        assertThat(hostStatusOf(hostId)).as("阈值未到，不动目标表").isEqualTo("ONLINE");
    }

    /** 现场装配真实受限入口（枚举侧读隔离库，写回侧是本类的真事务代理 Bean）。 */
    private static CollectionScopedRunSummary runScoped(CollectionAdmissionPolicy policy) {
        HostInstanceCollectionService service = new HostInstanceCollectionService(
                new DbBackedHostService(assetHostMapper),
                new DbBackedSshConfigService(sshConfigMapper),
                new DbBackedInstanceService(runtimeServiceInstanceMapper),
                stateMapper,
                writeBackService,
                List.of(observer),
                properties,
                collectionClock);
        try {
            return service.collectOnceForScope(policy);
        } finally {
            service.shutdown();
        }
    }

    private static String stateRowSql(long targetId) {
        return stateRowSql(targetId, CollectionTargetType.HOST);
    }

    private static String stateRowSql(long targetId, String targetType) {
        return "SELECT * FROM monitor_collection_state WHERE target_type = '" + targetType
                + "' AND target_id = " + targetId;
    }

    /**
     * 受限入口用的 HOST 授权身份：**主机归属要填成主机自己**——枚举
     * ({@code newRef(HOST, host.getId(), host.getId(), ...)}) 就是这么产的，缺了这一项会被
     * 判成 {@code HOST_BINDING_CHANGED}。既有的 {@code hostRef} 不带主机归属，留给不受限用例。
     */
    private static CollectionTargetRef scopedHostRef(long hostId, String address, Long configId) {
        return new CollectionTargetRef(CollectionTargetType.HOST, hostId, hostId, null,
                configId, configId == null ? null : CONFIG_VERSION, address,
                configId == null ? null : FIXTURE_SSH_PORT, null, null);
    }

    /**
     * 整行快照：列名 → 值，{@code SELECT *} 而不是逐列列举。
     *
     * <p>逐列列举会把"我恰巧记得的列没变"当成"没写入"；{@code updated_at}、各类计数、
     * 以及将来新增的列都在这条断言之外。值统一取字符串，避免不同驱动类型下的相等性歧义。
     */
    private static Map<String, Object> snapshotRow(String sql) throws Exception {
        try (Connection connection = connect();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            assertThat(rs.next()).as("夹具行应存在：" + sql).isTrue();
            ResultSetMetaData meta = rs.getMetaData();
            Map<String, Object> row = new LinkedHashMap<>();
            for (int column = 1; column <= meta.getColumnCount(); column++) {
                Object value = rs.getObject(column);
                row.put(meta.getColumnName(column), value == null ? null : value.toString());
            }
            assertThat(rs.next()).as("整行快照只接受唯一一行：" + sql).isFalse();
            return row;
        }
    }

    /**
     * 改绑用例需要 id=2 的服务/环境父行：{@code runtime_service_instance.service_id / env_id}
     * 上有真实外键（见 {@code db/flyway/sql/V1__baseline_schema.sql}），不给父行就改不动这一列。
     * 固定 id + {@code ON CONFLICT DO NOTHING}，重复执行安全；id 远小于清理下限，不受整表清理影响。
     */
    private static void seedSecondServiceAndEnv() throws Exception {
        execute("INSERT INTO base_env (id, env_code, env_name, env_type, created_by, updated_by) "
                + "VALUES (2, 'it-r8-env-2', 'IT R8 Env 2', 'DEV', 1, 1) ON CONFLICT (id) DO NOTHING");
        execute("INSERT INTO base_service (id, app_id, service_code, service_name, service_type, runtime_type, "
                + "created_by, updated_by) "
                + "VALUES (2, 1, 'it-r8-svc-2', 'IT R8 Service 2', 'APP', 'SPRING_BOOT', 1, 1) "
                + "ON CONFLICT (id) DO NOTHING");
    }

    // ---------- R7 夹具：真实观测器 + 打桩诊断 ----------

    /**
     * 装一个**真实**的 {@link SshProcessCollectionObserver}。
     *
     * <p>为什么诊断服务必须打桩：真实诊断要先真拨 SSH 才能读到诊断参数，集成环境里没有可拨的目标；
     * 而"缺部署诊断参数"恰恰是在**真拨之前**抛出的 —— 采集专用入口
     * {@code getStatusForCollection} 的顺序是"校验实例身份 → resolveRuntimeConfig → 拨号"，
     * 因此这里打桩复现同一个异常类型与同一段文案。
     *
     * <p>拨号请求的构造者（{@link CollectionDialResolver}）则是**真的**，且它的两个台账读口直接落在本用例的
     * 隔离库上（见 {@link DbBackedHostService}）：这组用例要验的正是"库里改了行之后这一轮会怎样"，
     * 桩掉它等于把被测判断换掉。
     *
     * <p>{@link SshExecutorService} 打桩是**为了被断言**：它一旦被调用就说明观测器真的走了 SSH 路径，
     * 用 {@link #assertNoSshCall()} 把它钉死。
     */
    private static void installObserverFixture() {
        sshExecutorService = mock(SshExecutorService.class);
        diagnosticsService = mock(RuntimeServiceDiagnosticsService.class);
        assertThat(mockingDetails(diagnosticsService).isMock())
                .as("诊断服务夹具必须是桩：真被 Bean 顶替时，打桩会变成真的拨 SSH")
                .isTrue();
        collectionClock = new MutableClock(at(0));
        observer = new SshProcessCollectionObserver(
                new CollectionDialResolver(new DbBackedHostService(assetHostMapper),
                        new DbBackedSshConfigService(sshConfigMapper), mock(SecretValueResolver.class)),
                sshExecutorService, diagnosticsService, properties, collectionClock);
    }

    /**
     * 台账服务：真实现 + 真 mapper，只把 Spring 注入的那一步改成手动装配。
     *
     * <p>为什么不能用 {@code mock(AssetHostService.class)}：拨号层要按快照判断"主机/连接配置是否在采样后被改过"，
     * 这个判断的输入只能是本用例那个隔离库。换成桩以后，库里改了行也传不进拨号层，本组用例就退化成"在验桩"。
     *
     * <p>构造参数里的环境/资源池服务在本路径上一律不被调用（{@code getById} 只走 {@code baseMapper}），
     * 因此传 {@code null}：宁可空着，也不假装它们配好了。
     */
    private static final class DbBackedHostService extends AssetHostService {

        private DbBackedHostService(AssetHostMapper mapper) {
            super(null, null);
            this.baseMapper = mapper;
        }
    }

    private static final class DbBackedSshConfigService extends AssetHostSshConfigService {

        private DbBackedSshConfigService(AssetHostSshConfigMapper mapper) {
            this.baseMapper = mapper;
        }
    }

    /**
     * 同上，供受限入口的枚举侧使用：枚举要真的读 {@code runtime_service_instance}。
     * 本类自带的方法（{@code listByServiceAndEnv} 等）在本路径上不被调用，因此不额外装配依赖。
     */
    private static final class DbBackedInstanceService extends RuntimeServiceInstanceService {

        private DbBackedInstanceService(RuntimeServiceInstanceMapper mapper) {
            this.baseMapper = mapper;
        }
    }

    /** 与编排层相同的边界：真实观测器探测 → 真实写回服务写回（等价于 HostInstanceCollectionService 的两步）。 */
    private static WriteOutcome probeThenWriteBack(CollectionTargetRef ref) {
        CollectionObservation observation = observer.observe(ref);
        return writeBackService.writeBack(ref, observation);
    }

    private static void stubDiagnosticsSuccess() {
        doReturn(runningStatusResponse()).when(diagnosticsService).getStatusForCollection(any(), anyLong());
    }

    /**
     * 必须用 {@code doThrow(...).when(...)}，不能用 {@code when(...).thenThrow(...)}。
     *
     * <p>同一个用例会在"缺配置"与"配置补齐"之间来回改写这同一个桩（R7 的"配置恢复"用例就是这么走的），
     * 而 {@code when(mock.method())} 的参数表达式会**先真的调用一次桩**：若桩当前是
     * {@code thenThrow}，这一次改写会直接把上一个异常抛出来，用例失败在一个与被测行为无关的地方。
     * {@code doThrow/doReturn} 形式不经过参数表达式，改写任意状态的桩都安全。
     */
    private static void stubDiagnosticsConfigMissing() {
        doThrow(new BizException(ErrorCode.VALIDATION_ERROR, NO_DIAGNOSTIC_CONFIG_MESSAGE))
                .when(diagnosticsService).getStatusForCollection(any(), anyLong());
    }

    private static void stubDiagnosticsFailure(String message) {
        doThrow(new BizException(ErrorCode.INTERNAL_ERROR, message))
                .when(diagnosticsService).getStatusForCollection(any(), anyLong());
    }

    private static RuntimeServiceInstanceStatusResponse runningStatusResponse() {
        RuntimeServiceInstanceStatusResponse response = new RuntimeServiceInstanceStatusResponse();
        response.setStdout(RUNNING_MARKER + "true\n");
        response.setRunning(true);
        response.setHealthStatus("UP");
        return response;
    }

    private static void assertNoSshCall() {
        verify(sshExecutorService, never()).execute(any());
    }

    /** 可推进的固定时钟：让每一轮观测时刻精确落在夹具时间轴上，不靠"真实时间恰好不重复"。 */
    private static final class MutableClock extends Clock {

        private Instant instant;

        MutableClock(OffsetDateTime start) {
            this.instant = start.toInstant();
        }

        void advanceTo(OffsetDateTime moment) {
            this.instant = moment.toInstant();
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    // ---------- Spring 会话装配 ----------

    /**
     * 真实 Spring 事务装配：{@code @EnableTransactionManagement} 让 {@link CollectionWriteBackService}
     * 被 CGLIB 代理包住，{@code @Transactional} 才会真正开启事务。
     *
     * <p>{@code DataSourceTransactionManager} 与 MyBatis 的 {@code Environment} 必须共用**同一个**
     * {@link DataSource} 实例：Spring 的事务把连接绑在 DataSource 上，{@code SpringManagedTransaction}
     * 再去同一个 DataSource 上取，两边不一致就会各自开新连接、事务形同虚设。
     */
    @Configuration
    @EnableTransactionManagement(proxyTargetClass = true)
    static class ItConfiguration {

        @Bean
        DataSource dataSource() {
            return new UnpooledDataSource("org.postgresql.Driver", jdbcUrl, username, password);
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource dataSource) {
            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.addMapper(MonitorCollectionStateMapper.class);
            configuration.addMapper(AssetHostMapper.class);
            configuration.addMapper(RuntimeServiceInstanceMapper.class);
            configuration.addMapper(AssetHostSshConfigMapper.class);
            configuration.setEnvironment(new Environment("postgres-it-writeback",
                    new SpringManagedTransactionFactory(), dataSource));
            return new MybatisSqlSessionFactoryBuilder().build(configuration);
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory sqlSessionFactory) {
            return new SqlSessionTemplate(sqlSessionFactory);
        }

        @Bean
        MonitorCollectionStateMapper monitorCollectionStateMapper(SqlSessionTemplate template) {
            return template.getMapper(MonitorCollectionStateMapper.class);
        }

        @Bean
        AssetHostMapper assetHostMapper(SqlSessionTemplate template) {
            return template.getMapper(AssetHostMapper.class);
        }

        @Bean
        RuntimeServiceInstanceMapper runtimeServiceInstanceMapper(SqlSessionTemplate template) {
            return template.getMapper(RuntimeServiceInstanceMapper.class);
        }

        @Bean
        AssetHostSshConfigMapper assetHostSshConfigMapper(SqlSessionTemplate template) {
            return template.getMapper(AssetHostSshConfigMapper.class);
        }

        /** 取值必须落在 {@code HostInstanceCollectionProperties.validate()} 的合法区间内。 */
        @Bean
        HostInstanceCollectionProperties hostInstanceCollectionProperties() {
            HostInstanceCollectionProperties settings = new HostInstanceCollectionProperties();
            settings.setPeriodMs(1000);
            settings.setSingleTargetTimeoutMs(100);
            settings.setMaxConcurrency(4);
            settings.setFailureThreshold(DEFAULT_FAILURE_THRESHOLD);
            settings.setStaleThresholdMs(1000);
            return settings;
        }

        @Bean
        CollectionWriteBackService collectionWriteBackService(AssetHostMapper assetHostMapper,
                                                             RuntimeServiceInstanceMapper runtimeServiceInstanceMapper,
                                                             MonitorCollectionStateMapper monitorCollectionStateMapper,
                                                             AssetHostSshConfigMapper assetHostSshConfigMapper,
                                                             HostInstanceCollectionProperties settings) {
            return new CollectionWriteBackService(assetHostMapper, runtimeServiceInstanceMapper,
                    monitorCollectionStateMapper, assetHostSshConfigMapper, settings);
        }
    }

    private static void startContext() {
        context = new AnnotationConfigApplicationContext(ItConfiguration.class);
        properties = context.getBean(HostInstanceCollectionProperties.class);
        writeBackService = context.getBean(CollectionWriteBackService.class);
        stateMapper = context.getBean(MonitorCollectionStateMapper.class);
        assetHostMapper = context.getBean(AssetHostMapper.class);
        sshConfigMapper = context.getBean(AssetHostSshConfigMapper.class);
        runtimeServiceInstanceMapper = context.getBean(RuntimeServiceInstanceMapper.class);
    }

    // ---------- 隔离库解析 ----------

    private static void resolveIsolatedDatabase() throws Exception {
        Properties settings = loadExplicitSettings();
        if (settings != null) {
            String explicitUrl = settings.getProperty("url");
            String user = settings.getProperty("username");
            String pass = settings.getProperty("password");
            rejectSharedRealDatabase(explicitUrl);
            disposableDatabaseName = createDisposableDatabase(explicitUrl, user, pass);
            serverBaseUrl = explicitUrl.substring(0, explicitUrl.lastIndexOf('/'));
            jdbcUrl = serverBaseUrl + "/" + disposableDatabaseName;
            username = user;
            password = pass;
            return;
        }

        try {
            container = new PostgreSQLContainer<>("postgres:16-alpine");
            container.start();
        } catch (Throwable throwable) {
            unavailableReason = "没有可用的隔离 PostgreSQL：既未提供 infrahub.it.pg.config / infrahub.it.pg.url，"
                    + "Testcontainers 也起不来（" + throwable.getClass().getSimpleName() + ": "
                    + throwable.getMessage() + "）。本测试跳过，事务边界语义**未被验证**。";
            return;
        }
        jdbcUrl = container.getJdbcUrl();
        username = container.getUsername();
        password = container.getPassword();
    }

    /** 优先读配置文件（避免密码出现在命令行），退回逐项的系统属性 / 环境变量。 */
    private static Properties loadExplicitSettings() throws Exception {
        String configPath = firstNonBlank(System.getProperty("infrahub.it.pg.config"),
                System.getenv("INFRAHUB_IT_PG_CONFIG"));
        if (configPath != null) {
            Properties settings = new Properties();
            try (Reader reader = Files.newBufferedReader(Path.of(configPath), StandardCharsets.UTF_8)) {
                settings.load(reader);
            }
            if (settings.getProperty("url") == null) {
                throw new IllegalStateException("配置文件 " + configPath + " 缺少 url= 项");
            }
            return settings;
        }

        String url = firstNonBlank(System.getProperty("infrahub.it.pg.url"), System.getenv("INFRAHUB_IT_PG_URL"));
        if (url == null) {
            return null;
        }
        Properties settings = new Properties();
        settings.setProperty("url", url);
        settings.setProperty("username", nullToEmpty(firstNonBlank(
                System.getProperty("infrahub.it.pg.username"), System.getenv("INFRAHUB_IT_PG_USERNAME"))));
        settings.setProperty("password", nullToEmpty(firstNonBlank(
                System.getProperty("infrahub.it.pg.password"), System.getenv("INFRAHUB_IT_PG_PASSWORD"))));
        return settings;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static void rejectSharedRealDatabase(String url) {
        if (SHARED_REAL_DB_HOST.equals(hostOf(url))) {
            throw new IllegalStateException(
                    "拒绝在共享真实库上运行集成测试：" + url + "。请指向隔离的测试 PostgreSQL 实例。");
        }
    }

    private static String hostOf(String jdbcUrl) {
        int start = jdbcUrl.indexOf("//");
        if (start < 0) {
            return "";
        }
        String rest = jdbcUrl.substring(start + 2);
        int end = rest.length();
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c == ':' || c == '/' || c == '?') {
                end = i;
                break;
            }
        }
        return rest.substring(0, end);
    }

    /** 在隔离实例上新建一次性数据库，只返回名字；删除时也只用这个名字。 */
    private static String createDisposableDatabase(String anyUrlOnServer, String user, String pass) throws Exception {
        String databaseName = "infrahub_it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        try (Connection connection = DriverManager.getConnection(anyUrlOnServer, user, pass);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + databaseName);
        }
        return databaseName;
    }

    /**
     * 只删除本测试自己创建、并且记下了名字的那一个库。
     *
     * <p>刻意不按 {@code infrahub_it_} 前缀枚举后批量删除：隔离实例上可能有来源不明的库
     * （例如别的用例留下的），按前缀清库是破坏性的。
     */
    private static void dropOwnDisposableDatabase() {
        if (disposableDatabaseName == null || serverBaseUrl == null) {
            return;
        }
        try (Connection connection = DriverManager.getConnection(serverBaseUrl + "/postgres", username, password);
             Statement statement = connection.createStatement()) {
            statement.execute("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '"
                    + disposableDatabaseName + "' AND pid <> pg_backend_pid()");
            statement.execute("DROP DATABASE IF EXISTS " + disposableDatabaseName);
            System.out.println("[it-writeback] dropped own disposable database " + disposableDatabaseName);
        } catch (Exception exception) {
            System.out.println("[it-writeback] WARN could not drop own disposable database "
                    + disposableDatabaseName + ": " + exception);
        }
    }

    // ---------- 迁移装载 ----------

    private static void applyMigration(String fileName) throws Exception {
        String sql = loadMigrationSql(locateSqlFile("db/flyway/sql/" + fileName));
        try (Connection connection = connect();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    /**
     * {@code asset_host_ssh_config} 只存在于 {@code db/bootstrap/04-01-add-missing-tables.sql}
     * （{@code db/flyway/sql} 下没有任何迁移建它），而写回的身份比对要读这张表。
     *
     * <p>这里从**真实脚本**里按语句筛出提到这张表的语句执行，而不是在测试里另抄一份 DDL ——
     * 抄一份就意味着"测试验的是抄本、不是仓库"。筛选是逐语句做的，一条都没筛到时直接失败。
     */
    private static void applyBootstrapTableBlock() throws Exception {
        Path script = locateSqlFile("db/bootstrap/04-01-add-missing-tables.sql");
        List<String> statements = extractStatementsMentioning(script, "asset_host_ssh_config");
        try (Connection connection = connect();
             Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
        System.out.println("[it-writeback] applied " + statements.size()
                + " statement(s) mentioning asset_host_ssh_config from db/bootstrap/04-01-add-missing-tables.sql");
    }

    private static List<String> extractStatementsMentioning(Path sqlFile, String tableName) throws IOException {
        String sql = Files.readString(sqlFile, StandardCharsets.UTF_8);
        if (!sql.isEmpty() && sql.charAt(0) == UTF8_BOM) {
            sql = sql.substring(1);
        }
        List<String> matched = new ArrayList<>();
        List<String> pending = new ArrayList<>();
        int firstLineNo = 0;
        String[] lines = sql.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (pending.isEmpty()) {
                firstLineNo = i + 1;
            }
            pending.add(lines[i]);
            String statement = String.join("\n", pending);
            if (!statement.stripTrailing().endsWith(";")) {
                continue;
            }
            if (countSingleQuotes(statement) % 2 != 0) {
                throw new IllegalStateException("脚本 " + sqlFile.getFileName() + " 第 " + firstLineNo
                        + " 行起的语句单引号不闭合，无法安全提取。");
            }
            if (statement.contains(tableName)) {
                matched.add(statement);
            }
            pending.clear();
        }
        // V1__baseline_schema.sql 以 ";\r\n\r\n" 结尾，split 之后会剩下一个纯空白的尾巴。
        // 空白尾巴不是"没写完的语句"；只有真的留下了有内容的片段才是。
        if (pending.stream().anyMatch(line -> !line.isBlank())) {
            throw new IllegalStateException("脚本 " + sqlFile.getFileName() + " 以未结束的语句收尾，请人工确认。");
        }
        if (matched.isEmpty()) {
            throw new IllegalStateException("脚本 " + sqlFile.getFileName() + " 里没有任何提到 " + tableName
                    + " 的语句；夹具依赖它建表，请先确认脚本是否还在原处。");
        }
        return matched;
    }

    /**
     * 允许被丢弃的"历史缺陷"**区段**：只有历史脚本 {@code V1__baseline_schema.sql} 的 605–615 行。
     *
     * <p>四条坏 COMMENT 位于 609–612 行，解析时每条前面那条空行会被并入语句，所以上报的起始行是 608；
     * 用区段而不是逐行清单，正是为了不被这种一行之差不必要地绊住。锁定缺陷**范围**靠两件事：
     * 只有这个文件、这个区段允许丢弃；且丢弃总数必须**正好是 4**（见 {@link #HISTORICAL_DEFECT_COUNT}）。
     * 于是在 {@code V29} 或今后任何新迁移里出现引号不闭合的语句，都不可能被悄悄吃掉。
     */
    private static final String HISTORICAL_DEFECT_FILE = "V1__baseline_schema.sql";
    private static final int HISTORICAL_DEFECT_FIRST_LINE = 605;
    private static final int HISTORICAL_DEFECT_LAST_LINE = 615;
    private static final int HISTORICAL_DEFECT_COUNT = 4;

    /**
     * 读取 Flyway 迁移文件，**不改动仓库里的文件**。
     *
     * <p>只丢弃 V1 历史缺陷区段内的语句（第 609–612 行的 4 条 COMMENT 文本损坏），
     * 其他任何引号不闭合的语句一律抛错。
     * 绝不能因为 V1 坏了就习惯性过滤掉所有 COMMENT —— 那会把新迁移里真正的问题一起掩盖。
     *
     * <p>注意：本测试的过滤只是"用裸 JDBC 跑迁移夹具"的权宜手段，**不能**当作用真实 Flyway 验证过。
     * 真实 Flyway 在空库上跑 V1 会直接失败（见 {@code FlywayIsolatedPostgresTest}）。
     */
    private static String loadMigrationSql(Path sqlFile) throws IOException {
        String sql = Files.readString(sqlFile, StandardCharsets.UTF_8);
        if (!sql.isEmpty() && sql.charAt(0) == UTF8_BOM) {
            sql = sql.substring(1);
        }
        String fileName = sqlFile.getFileName().toString();
        boolean historicalDefectFile = HISTORICAL_DEFECT_FILE.equals(fileName);
        StringBuilder kept = new StringBuilder(sql.length());
        List<String> pending = new ArrayList<>();
        int firstLineNo = 0;
        int dropped = 0;
        int keptStatements = 0;
        String[] lines = sql.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (pending.isEmpty()) {
                firstLineNo = i + 1;
            }
            pending.add(lines[i]);
            String statement = String.join("\n", pending);
            if (!statement.stripTrailing().endsWith(";")) {
                continue;
            }
            boolean unbalanced = countSingleQuotes(statement) % 2 != 0;
            boolean isComment = statement.stripLeading().toUpperCase(java.util.Locale.ROOT).startsWith("COMMENT ON");
            if (unbalanced && !isComment) {
                throw new IllegalStateException("迁移文件 " + fileName + " 第 " + firstLineNo
                        + " 行起的语句单引号不闭合，且不是 COMMENT 语句，无法安全跳过；"
                        + "请修复该迁移，而不是在测试里过滤。");
            }
            if (unbalanced) {
                boolean insideKnownDefect = historicalDefectFile
                        && firstLineNo >= HISTORICAL_DEFECT_FIRST_LINE
                        && firstLineNo <= HISTORICAL_DEFECT_LAST_LINE;
                if (!insideKnownDefect) {
                    throw new IllegalStateException("迁移文件 " + fileName + " 第 " + firstLineNo
                            + " 行起的 COMMENT 单引号不闭合，却不在已知历史缺陷（"
                            + HISTORICAL_DEFECT_FILE + " 第 " + HISTORICAL_DEFECT_FIRST_LINE + "–"
                            + HISTORICAL_DEFECT_LAST_LINE + " 行）之内；这是**待处理的迁移错误**，"
                            + "请修复该迁移，不要在测试里过滤。");
                }
                dropped++;
                System.out.println("[it-writeback] " + fileName + " line " + firstLineNo
                        + ": dropped 1 known-historical broken COMMENT (V1 metadata only, quotes never closed)");
            } else {
                kept.append(statement).append('\n');
                keptStatements++;
            }
            pending.clear();
        }
        // V1__baseline_schema.sql 以 ";\r\n\r\n" 结尾，split 之后会剩下一个纯空白的尾巴。
        // 空白尾巴不是"没写完的语句"；只有真的留下了有内容的片段才是。
        if (pending.stream().anyMatch(line -> !line.isBlank())) {
            throw new IllegalStateException("迁移文件 " + fileName + " 以未结束的语句收尾，请人工确认。");
        }
        if (dropped != 0 && dropped != HISTORICAL_DEFECT_COUNT) {
            throw new IllegalStateException("丢弃的缺陷语句数与已知缺陷不符：实际 " + dropped
                    + " 条，已知 " + HISTORICAL_DEFECT_COUNT + " 条；说明 V1 的损坏范围变了，"
                    + "或有新的坏语句混进来，请人工确认后再更新本测试。");
        }
        System.out.println("[it-writeback] " + fileName + ": executed " + keptStatements
                + " statement(s), dropped " + dropped + "; " + (dropped == 0
                        ? "whole file executed verbatim including all COMMENT"
                        : "drops limited to the known historical V1 defect region"));
        return kept.toString();
    }

    private static int countSingleQuotes(String text) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\'') {
                count++;
            }
        }
        return count;
    }

    private static Path locateSqlFile(String relativePath) {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (Path candidate = dir; candidate != null; candidate = candidate.getParent()) {
            Path sqlFile = candidate.resolve(relativePath.replace('/', java.io.File.separatorChar));
            if (Files.isRegularFile(sqlFile)) {
                return sqlFile;
            }
        }
        throw new IllegalStateException("找不到脚本 " + relativePath + "（自 " + dir + " 向上查找）");
    }

    // ---------- 夹具 ----------

    /**
     * asset_host / runtime_service_instance 上有真实外键，夹具只造最小的父行链。
     * 固定 id + ON CONFLICT DO NOTHING，重复执行安全；这些行不参与 {@code >= 92000} 的清理。
     */
    private static void seedForeignKeyParents() throws Exception {
        execute("INSERT INTO sys_user (id, username, display_name, status) "
                + "VALUES (1, 'it-writeback-user', 'IT Writeback User', 'ENABLED') ON CONFLICT (id) DO NOTHING");
        execute("INSERT INTO base_app (id, app_code, app_name, owner_id, created_by, updated_by) "
                + "VALUES (1, 'it-writeback-app', 'IT Writeback App', 1, 1, 1) ON CONFLICT (id) DO NOTHING");
        execute("INSERT INTO base_env (id, env_code, env_name, env_type, created_by, updated_by) "
                + "VALUES (1, 'it-writeback-env', 'IT Writeback Env', 'DEV', 1, 1) ON CONFLICT (id) DO NOTHING");
        execute("INSERT INTO base_service (id, app_id, service_code, service_name, service_type, runtime_type, "
                + "created_by, updated_by) "
                + "VALUES (1, 1, 'it-writeback-svc', 'IT Writeback Service', 'APP', 'SPRING_BOOT', 1, 1) "
                + "ON CONFLICT (id) DO NOTHING");
    }

    private static void execute(String sql) throws Exception {
        try (Connection connection = connect();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection(jdbcUrl, username, password);
    }

    private static int countRows(String sql) throws Exception {
        try (Connection connection = connect();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static String hostStatusOf(long hostId) throws Exception {
        return stringOf("SELECT host_status FROM asset_host WHERE id = " + hostId);
    }

    private static String instanceStatusOf(long instanceId) throws Exception {
        return stringOf("SELECT instance_status FROM runtime_service_instance WHERE id = " + instanceId);
    }

    private static String instanceHealthOf(long instanceId) throws Exception {
        return stringOf("SELECT health_status FROM runtime_service_instance WHERE id = " + instanceId);
    }

    private static String stringOf(String sql) throws Exception {
        try (Connection connection = connect();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            assertThat(rs.next()).as("夹具行应存在：" + sql).isTrue();
            return rs.getString(1);
        }
    }

    /** 走服务自己用的读取路径取回启用配置，保证"采样侧拿到的版本"与库内读法一致。 */
    private static AssetHostSshConfig enabledConfigOf(long hostId) {
        List<AssetHostSshConfig> enabled = sshConfigMapper.selectEnabledByHostIdForUpdate(hostId);
        assertThat(enabled).as("夹具应有一条启用配置").hasSize(1);
        return enabled.get(0);
    }

    /**
     * 数据库层的失败注入：目标表上的 {@code BEFORE UPDATE ... FOR EACH ROW} 触发器直接 RAISE。
     * 只有满足条件的行会失败，其余行不受影响。
     */
    private static void installFailureInjection(String condition) throws Exception {
        execute("CREATE OR REPLACE FUNCTION it_fail_instance_write() RETURNS trigger AS $$ "
                + "BEGIN RAISE EXCEPTION 'it-injected failure (rollback and isolation evidence)'; END; "
                + "$$ LANGUAGE plpgsql");
        execute("DROP TRIGGER IF EXISTS it_fail_instance_write ON runtime_service_instance");
        execute("CREATE TRIGGER it_fail_instance_write BEFORE UPDATE ON runtime_service_instance "
                + "FOR EACH ROW WHEN (" + condition + ") EXECUTE FUNCTION it_fail_instance_write()");
    }

    private static void dropFailureInjectionTrigger() throws Exception {
        execute("DROP TRIGGER IF EXISTS it_fail_instance_write ON runtime_service_instance");
        execute("DROP FUNCTION IF EXISTS it_fail_instance_write()");
    }

    private static long insertHost(long id, String hostname, String ip, String hostStatus) throws Exception {
        execute("INSERT INTO asset_host (id, hostname, ip, os_type, host_type, cpu_cores, memory_gb, disk_gb, "
                + "host_status, env_id, created_at, updated_at) VALUES ("
                + id + ", '" + hostname + "', '" + ip + "', 'LINUX', 'PHYSICAL', 4.00, 8.00, 100.00, '"
                + hostStatus + "', 1, TIMESTAMPTZ '2020-01-01 00:00:00+00', TIMESTAMPTZ '2020-01-01 00:00:00+00')");
        return id;
    }

    private static long insertInstance(long id, long hostId, String instanceType, String status, String health)
            throws Exception {
        execute("INSERT INTO runtime_service_instance (id, service_id, env_id, host_id, instance_name, "
                + "instance_type, instance_status, health_status, created_at, updated_at) VALUES ("
                + id + ", 1, 1, " + hostId + ", '" + instanceName(id) + "', '" + instanceType + "', '" + status
                + "', '" + health + "', TIMESTAMPTZ '2020-01-01 00:00:00+00', "
                + "TIMESTAMPTZ '2020-01-01 00:00:00+00')");
        return id;
    }

    private static long insertEnabledConfig(long id, long hostId) throws Exception {
        execute("INSERT INTO asset_host_ssh_config (id, host_id, ssh_port, auth_type, username, enabled, "
                + "created_at, updated_at) VALUES ("
                + id + ", " + hostId + ", 22, 'PASSWORD', 'it-user', TRUE, "
                + "TIMESTAMPTZ '2026-01-01 00:00:00+00', TIMESTAMPTZ '2026-01-01 00:00:00+00')");
        return id;
    }

    private static String instanceName(long id) {
        return "instance-writeback-" + id;
    }

    /** 一套最小 R3 夹具：主机 + 启用配置 + 该主机上的运行实例，三张表共用同一个 id。 */
    private static Fixture fixture(long id, String address) throws Exception {
        long hostId = insertHost(id, "it-r3-" + id, address, "ONLINE");
        long configId = insertEnabledConfig(id, hostId);
        long instanceId = insertInstance(id, hostId, "JAVA", "RUNNING", "UP");
        return new Fixture(hostId, configId, instanceId);
    }

    private record Fixture(long hostId, long configId, long instanceId) {

        CollectionTargetRef ref(String address) {
            return ref(address, CONFIG_VERSION);
        }

        CollectionTargetRef ref(String address, OffsetDateTime configVersion) {
            return instanceRef(instanceId, hostId, "JAVA", address, configId, configVersion);
        }
    }

    private static CollectionTargetRef hostRef(long hostId, String address, Long configId) {
        return new CollectionTargetRef(CollectionTargetType.HOST, hostId, null, null,
                configId, configId == null ? null : CONFIG_VERSION, address,
                configId == null ? null : FIXTURE_SSH_PORT, null, null);
    }

    private static CollectionTargetRef instanceRef(long instanceId, long hostId, String instanceType,
                                                   String address, Long configId) {
        return instanceRef(instanceId, hostId, instanceType, address, configId, CONFIG_VERSION);
    }

    private static CollectionTargetRef instanceRef(long instanceId, long hostId, String instanceType,
                                                   String address, Long configId, OffsetDateTime configVersion) {
        return new CollectionTargetRef(CollectionTargetType.SERVICE_INSTANCE, instanceId, hostId, instanceType,
                configId, configVersion, address,
                configId == null ? null : FIXTURE_SSH_PORT, FIXTURE_SERVICE_ID, FIXTURE_ENV_ID);
    }

    private static CollectionObservation success(String status, String health, OffsetDateTime observedAt) {
        return new CollectionObservation(CollectionResult.SUCCESS, null, null, status, health, observedAt,
                OBSERVER_TYPE, true);
    }

    private static CollectionObservation failure(OffsetDateTime observedAt) {
        return CollectionObservation.failure(CollectionErrorCategory.NETWORK_TIMEOUT, "it failure", OBSERVER_TYPE,
                observedAt);
    }

    private static Thread inThread(String name, Runnable body) {
        Thread thread = new Thread(body, name);
        thread.setDaemon(true);
        return thread;
    }

    private static OffsetDateTime at(int minutesFromEpoch) {
        return OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC).plusMinutes(minutesFromEpoch);
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second != null && !second.isBlank() ? second : null;
    }
}
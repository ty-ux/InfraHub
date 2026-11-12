package com.infrahub.release.mapper;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.infrahub.release.entity.MonitorCollectionState;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 采集写回 SQL 的**真实 PostgreSQL** 验证。
 *
 * <p>为什么必须有这一层：{@code upsertObservation} 的乱序守卫、{@code COALESCE} 保留最近成功时间、
 * 连续失败在 SQL 内自增、{@code markExpired} 的幂等，全都写在 SQL 里。用 mock mapper 断言调用次数
 * 只能证明"方法被调用了"，不能证明这些语义在数据库里真的成立。
 *
 * <p>建表用的是仓库里的**真实迁移文件**（{@code db/flyway/sql/V1__baseline_schema.sql} 与
 * {@code V29__create_monitor_collection_state.sql}），不是在测试里另抄一份 DDL。
 *
 * <h2>隔离库来源（二选一）</h2>
 * <ol>
 *   <li>显式指定隔离 PostgreSQL。**推荐用配置文件**，免得密码出现在命令行参数里：
 *       {@code -Dinfrahub.it.pg.config=<仓库外的 .properties 路径>}，文件内容为
 *       {@code url=} / {@code username=} / {@code password=}。
 *       也支持直接给 {@code -Dinfrahub.it.pg.url} / {@code .username} / {@code .password}
 *       （或环境变量 {@code INFRAHUB_IT_PG_CONFIG} / {@code INFRAHUB_IT_PG_URL} / {@code _USERNAME} /
 *       {@code _PASSWORD}）。测试会在该实例上**新建一个一次性数据库**（{@code infrahub_it_<随机>}）
 *       并在其中建表，不碰实例上任何既有库。</li>
 *   <li>Testcontainers 起一个 {@code postgres:16-alpine}。</li>
 * </ol>
 *
 * <p><b>两者都不可用时不伪装成通过，但也不静默归零</b>：早期实现在 {@code @BeforeAll} 里直接
 * abort，surefire 会输出 {@code Tests run: 0, Skipped: 0} —— 构建是绿的、却连"跳过了"都没写，
 * 读的人会以为这个类本来就是空的。现在改为记录原因、由 {@code @BeforeEach} 逐条跳过，
 * 报告里会明确出现 {@code Tests run: 12, Skipped: 12}，未验证这件事不会被藏起来。
 *
 * <p><b>安全护栏</b>：显式 URL 指向共享真实库（{@value #SHARED_REAL_DB_HOST}）时直接失败，不会执行任何 DDL。
 */
class MonitorCollectionStateMapperPostgresTest {

    /** 共享真实库主机。本任务不授权对其做任何写入，测试必须拒绝而不是"看起来通了"。 */
    private static final String SHARED_REAL_DB_HOST = "192.0.2.1";

    /** UTF-8 BOM。写成码点而不是字面量，避免源码里出现不可见字符。 */
    private static final int UTF8_BOM = 0xFEFF;

    /** 夹具自用 id 下界：清理只删这个区间，父行链（id=1）不受影响。 */
    private static final long FIXTURE_ID_FLOOR = 91000L;

    private static PostgreSQLContainer<?> container;
    private static String jdbcUrl;
    private static String username;
    private static String password;
    private static SqlSessionFactory sqlSessionFactory;

    /** 无可用的隔离库时记下原因，由 {@link #clearFixtures()} 逐条跳过，而不是在 @BeforeAll 里静默归零。 */
    private static String unavailableReason;

    @BeforeAll
    static void setUp() throws Exception {
        resolveIsolatedDatabase();
        if (unavailableReason != null) {
            return;
        }
        applyMigration("V1__baseline_schema.sql");
        applyMigration("V29__create_monitor_collection_state.sql");
        buildSessionFactory();
        seedForeignKeyParents();
    }

    /**
     * 每个测试方法都从头开始：否则 markExpired 这类"全表结算"的断言会看到别的用例留下的行，
     * 测试之间互相污染，失败原因指向错误的地方。
     *
     * <p>开头那条 assume 是"未验证就必须显眼"的落点：没有隔离库时它让每条用例被报告为
     * skipped（而不是让整个类变成 {@code Tests run: 0}）。
     */
    @BeforeEach
    void clearFixtures() throws Exception {
        Assumptions.assumeTrue(unavailableReason == null, unavailableReason);
        execute("DELETE FROM monitor_collection_state");
        execute("DELETE FROM runtime_service_instance WHERE id >= " + FIXTURE_ID_FLOOR);
        execute("DELETE FROM asset_host WHERE id >= " + FIXTURE_ID_FLOOR);
    }

    /**
     * asset_host / runtime_service_instance 上有真实外键（env / service / app / user），
     * 夹具只造最小的父行链。用固定 id + ON CONFLICT DO NOTHING，重复执行安全。
     */
    private static void seedForeignKeyParents() throws Exception {
        execute("INSERT INTO sys_user (id, username, display_name, status) "
                + "VALUES (1, 'it-fixture-user', 'IT Fixture User', 'ENABLED') ON CONFLICT (id) DO NOTHING");
        execute("INSERT INTO base_app (id, app_code, app_name, owner_id, created_by, updated_by) "
                + "VALUES (1, 'it-fixture-app', 'IT Fixture App', 1, 1, 1) ON CONFLICT (id) DO NOTHING");
        execute("INSERT INTO base_env (id, env_code, env_name, env_type, created_by, updated_by) "
                + "VALUES (1, 'it-fixture-env', 'IT Fixture Env', 'DEV', 1, 1) ON CONFLICT (id) DO NOTHING");
        execute("INSERT INTO base_service (id, app_id, service_code, service_name, service_type, runtime_type, "
                + "created_by, updated_by) "
                + "VALUES (1, 1, 'it-fixture-svc', 'IT Fixture Service', 'APP', 'SPRING_BOOT', 1, 1) "
                + "ON CONFLICT (id) DO NOTHING");
        // 实例夹具要求 host_id 非空且真实存在，所以留一台 id=1 的父主机，不参与 91000+ 的清理。
        execute("INSERT INTO asset_host (id, hostname, ip, os_type, host_type, cpu_cores, memory_gb, disk_gb, "
                + "host_status, env_id) "
                + "VALUES (1, 'it-fixture-host', '192.0.2.21', 'LINUX', 'PHYSICAL', 4.00, 8.00, 100.00, "
                + "'ONLINE', 1) ON CONFLICT (id) DO NOTHING");
    }

    private static void execute(String sql) throws Exception {
        try (Connection connection = connect();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    @AfterAll
    static void tearDown() {
        if (container != null) {
            container.stop();
        }
    }

    // ---------- 隔离库解析 ----------

    private static void resolveIsolatedDatabase() throws Exception {
        Properties settings = loadExplicitSettings();
        if (settings != null) {
            String explicitUrl = settings.getProperty("url");
            String user = settings.getProperty("username");
            String pass = settings.getProperty("password");
            rejectSharedRealDatabase(explicitUrl);
            jdbcUrl = createDisposableDatabase(explicitUrl, user, pass);
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
                    + throwable.getMessage() + "）。本测试跳过，数据库写回语义**未被验证**。";
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
        String host = hostOf(url);
        if (SHARED_REAL_DB_HOST.equals(host)) {
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

    /** 在隔离实例上新建一次性数据库，避免与实例上既有库混在一起。 */
    private static String createDisposableDatabase(String anyUrlOnServer, String user, String pass) throws Exception {
        String databaseName = "infrahub_it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String serverBase = anyUrlOnServer.substring(0, anyUrlOnServer.lastIndexOf('/'));
        try (Connection connection = DriverManager.getConnection(anyUrlOnServer, user, pass);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + databaseName);
        }
        return serverBase + "/" + databaseName;
    }

    private static void buildSessionFactory() {
        DataSource dataSource = new UnpooledDataSource("org.postgresql.Driver", jdbcUrl, username, password);
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(MonitorCollectionStateMapper.class);
        configuration.addMapper(AssetHostMapper.class);
        configuration.addMapper(RuntimeServiceInstanceMapper.class);
        configuration.setEnvironment(new Environment("postgres-it", new JdbcTransactionFactory(), dataSource));
        sqlSessionFactory = new MybatisSqlSessionFactoryBuilder().build(configuration);
    }

    /** 从仓库里读取真实迁移文件并执行，避免测试里出现第二份 DDL。 */
    private static void applyMigration(String fileName) throws Exception {
        String sql = loadMigrationSql(locateSqlFile(fileName));
        try (Connection connection = connect();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
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
     * 读取迁移文件，对齐 Flyway 的行为，**不改动仓库里的文件**：
     * <ul>
     *   <li>去掉 UTF-8 BOM：{@code V1__baseline_schema.sql} 开头带 BOM，Flyway 会跳过，裸 JDBC 不会。</li>
     *   <li>逐条语句检查单引号是否闭合。<b>只有</b> V1 历史缺陷区段内的语句会被丢弃，并逐条打印文件名与行号；
     *       其他任何引号不闭合的语句一律抛错，避免把待处理的迁移问题静默吃掉。</li>
     * </ul>
     *
     * <p>为什么要区分：历史脚本 {@code V1__baseline_schema.sql} 第 609–612 行有 4 条 COMMENT 文本损坏
     * （UTF-8 字节被按 GBK 解释，收尾单引号被吞），这是**历史文件本身的缺陷**；
     * 而新增的 {@code V29__create_monitor_collection_state.sql} 的 COMMENT 文本完好，
     * 必须整文件原样执行（含全部 COMMENT），不能因为 V1 坏了就习惯性地过滤掉全部 COMMENT —— 那会把
     * 新迁移里真正的问题一起掩盖。
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
        List<String> statementLines = new ArrayList<>();
        int firstLineNo = 0;
        int dropped = 0;
        int keptStatements = 0;
        String[] lines = sql.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (statementLines.isEmpty()) {
                firstLineNo = i + 1;
            }
            statementLines.add(lines[i]);
            String statement = String.join("\n", statementLines);
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
                System.out.println("[it] " + fileName + " 第 " + firstLineNo
                        + " 行：丢弃 1 条历史缺陷 COMMENT（V1 已知损坏，纯元数据，不影响表结构）");
            } else {
                kept.append(statement).append('\n');
                keptStatements++;
            }
            statementLines.clear();
        }
        // V1__baseline_schema.sql 以 ";\r\n\r\n" 结尾，split 之后会剩下一个纯空白的尾巴。
        // 空白尾巴不是"没写完的语句"；只有真的留下了有内容的片段才是。
        if (statementLines.stream().anyMatch(line -> !line.isBlank())) {
            throw new IllegalStateException("迁移文件 " + fileName
                    + " 以未结束的语句收尾，请人工确认。");
        }
        if (dropped != 0 && dropped != HISTORICAL_DEFECT_COUNT) {
            throw new IllegalStateException("丢弃的缺陷语句数与已知缺陷不符：实际 " + dropped
                    + " 条，已知 " + HISTORICAL_DEFECT_COUNT + " 条；说明 V1 的损坏范围变了，"
                    + "或有新的坏语句混进来，请人工确认后再更新本测试。");
        }
        System.out.println("[it] " + fileName + "：执行 " + keptStatements + " 条语句，丢弃 " + dropped
                + " 条；" + (dropped == 0
                        ? "整文件原样执行（含全部 COMMENT），无过滤"
                        : "丢弃仅限历史 V1 的已知缺陷区段，其它文件出现不闭合引号会直接失败"));
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

    private static Path locateSqlFile(String fileName) {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (Path candidate = dir; candidate != null; candidate = candidate.getParent()) {
            Path sqlFile = candidate.resolve("db").resolve("flyway").resolve("sql").resolve(fileName);
            if (Files.isRegularFile(sqlFile)) {
                return sqlFile;
            }
        }
        throw new IllegalStateException("找不到迁移文件 " + fileName + "（自 " + dir + " 向上查找）");
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection(jdbcUrl, username, password);
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second != null && !second.isBlank() ? second : null;
    }

    private static <T> void withMapper(Class<T> mapperType, Consumer<T> work) {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            work.accept(session.getMapper(mapperType));
        }
    }

    // ---------- V29 迁移本身 ----------

    @Test
    @DisplayName("V29 迁移建出 monitor_collection_state，且同目标重复写入只有一行")
    void migrationCreatesTableWithUniqueTarget() throws Exception {
        try (Connection connection = connect();
             ResultSet columns = connection.getMetaData()
                     .getColumns(null, null, "monitor_collection_state", null)) {
            assertThat(columns.next()).as("monitor_collection_state 应已建出").isTrue();
        }

        withMapper(MonitorCollectionStateMapper.class, mapper -> {
            mapper.upsertObservation(state("HOST", 1L, "SUCCESS", at(0), "ONLINE", "UNKNOWN"));
            // 同目标再写一次走 ON CONFLICT 分支；若唯一约束缺失，这里会插入第二行
            mapper.upsertObservation(state("HOST", 1L, "SUCCESS", at(1), "ONLINE", "UNKNOWN"));
        });

        assertThat(countRows("SELECT COUNT(*) FROM monitor_collection_state WHERE target_type='HOST' AND target_id=1"))
                .as("唯一约束应保证每目标一行").isEqualTo(1);
    }

    // ---------- 乱序守卫 ----------

    @Test
    @DisplayName("迟到的旧样本命中 0 行，绝不覆盖更新的状态")
    void lateOldSampleIsRejected() {
        long id = 91001L;
        withMapper(MonitorCollectionStateMapper.class, mapper -> {
            assertThat(mapper.upsertObservation(state("HOST", id, "SUCCESS", at(10), "ONLINE", "UNKNOWN")))
                    .as("首个样本应写入").isEqualTo(1);

            assertThat(mapper.upsertObservation(state("HOST", id, "SUCCESS", at(5), "OFFLINE", "UNKNOWN")))
                    .as("迟到的旧样本应命中 0 行").isZero();

            assertThat(mapper.incrementDiscarded("HOST", id, at(5)))
                    .as("应记一次拒收").isEqualTo(1);

            MonitorCollectionState stored = mapper.selectByTarget("HOST", id);
            assertThat(stored.getObservedAt()).as("观测时刻仍应是最新那次").isEqualTo(at(10));
            assertThat(stored.getLastObservedStatus()).as("状态不得被旧样本改写").isEqualTo("ONLINE");
            assertThat(stored.getDiscardedSampleCount()).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("被拒收的样本不会污染连续失败计数")
    void discardedSampleDoesNotCountAsFailure() {
        long id = 91011L;
        withMapper(MonitorCollectionStateMapper.class, mapper -> {
            mapper.upsertObservation(state("HOST", id, "SUCCESS", at(10), "ONLINE", "UNKNOWN"));
            // 迟到的失败样本：应被守卫拒绝，且不得把失败计数加一
            mapper.upsertObservation(failedState("HOST", id, at(1)));
            mapper.incrementDiscarded("HOST", id, at(1));

            MonitorCollectionState stored = mapper.selectByTarget("HOST", id);
            assertThat(stored.getConsecutiveFailureCount()).as("迟到样本不算一次失败").isZero();
            assertThat(stored.getLastResult()).as("最近结果不得被迟到样本改写").isEqualTo("SUCCESS");
            assertThat(stored.getDiscardedSampleCount()).isEqualTo(1);
        });
    }

    // ---------- 失败不清除最近成功时间 ----------

    @Test
    @DisplayName("单次瞬时失败不清除 last_success_at，连续失败在 SQL 内自增")
    void failureKeepsLastSuccessAndCountsUp() {
        long id = 91002L;
        withMapper(MonitorCollectionStateMapper.class, mapper -> {
            mapper.upsertObservation(state("HOST", id, "SUCCESS", at(0), "ONLINE", "UNKNOWN"));
            for (int i = 1; i <= 3; i++) {
                assertThat(mapper.upsertObservation(failedState("HOST", id, at(i))))
                        .as("失败样本本身应写入成功").isEqualTo(1);
            }

            MonitorCollectionState stored = mapper.selectByTarget("HOST", id);
            assertThat(stored.getLastSuccessAt()).as("失败不得清除最近成功观察时间").isEqualTo(at(0));
            assertThat(stored.getConsecutiveFailureCount()).as("连续失败应累计到 3").isEqualTo(3);
            assertThat(stored.getLastResult()).isEqualTo("FAILED");
            assertThat(stored.getLastErrorCategory()).isEqualTo("NETWORK_TIMEOUT");

            mapper.upsertObservation(state("HOST", id, "SUCCESS", at(4), "ONLINE", "UNKNOWN"));
            MonitorCollectionState recovered = mapper.selectByTarget("HOST", id);
            assertThat(recovered.getConsecutiveFailureCount()).as("成功后失败计数应归零").isZero();
            assertThat(recovered.getLastSuccessAt()).isEqualTo(at(4));
        });
    }

    @Test
    @DisplayName("失败样本不覆盖上一次成功观测到的状态（COALESCE 保留）")
    void failureDoesNotEraseLastObservedStatus() {
        long id = 91012L;
        withMapper(MonitorCollectionStateMapper.class, mapper -> {
            mapper.upsertObservation(state("HOST", id, "SUCCESS", at(0), "ONLINE", "UNKNOWN"));
            mapper.upsertObservation(failedState("HOST", id, at(1)));

            MonitorCollectionState stored = mapper.selectByTarget("HOST", id);
            assertThat(stored.getLastObservedStatus())
                    .as("失败样本没带状态，不该把上次观测抹成空").isEqualTo("ONLINE");
        });
    }

    // ---------- 过期结算 ----------

    @Test
    @DisplayName("过期候选只选出过期目标；markExpiredOne 幂等，重复标记返回 0")
    void markExpiredIsIdempotent() {
        long fresh = 91003L;
        long stale = 91004L;
        withMapper(MonitorCollectionStateMapper.class, mapper -> {
            mapper.upsertObservation(state("HOST", fresh, "SUCCESS", at(100), "ONLINE", "UNKNOWN"));
            mapper.upsertObservation(state("HOST", stale, "SUCCESS", at(0), "ONLINE", "UNKNOWN"));

            OffsetDateTime staleBefore = at(50);
            List<MonitorCollectionState> candidates = mapper.selectExpiredTargets(staleBefore);
            assertThat(candidates).extracting(MonitorCollectionState::getTargetId)
                    .as("只应选出过期目标").contains(stale).doesNotContain(fresh);
            assertThat(candidates).filteredOn(s -> s.getTargetId() == stale)
                    .singleElement()
                    .satisfies(s -> assertThat(s.getLastObservedStatus())
                            .as("应回传上次观测状态供条件复位").isEqualTo("ONLINE"));

            assertThat(mapper.markExpiredOne("HOST", stale, staleBefore))
                    .as("首次标记应生效").isEqualTo(1);
            assertThat(mapper.markExpiredOne("HOST", stale, staleBefore))
                    .as("已标记为 EXPIRED 的不应重复标记").isZero();
            assertThat(mapper.selectExpiredTargets(staleBefore))
                    .extracting(MonitorCollectionState::getTargetId)
                    .as("已标记的不应再进入过期候选").doesNotContain(stale);
            assertThat(mapper.selectByTarget("HOST", stale).getLastResult()).isEqualTo("EXPIRED");
        });
    }

    @Test
    @DisplayName("从未成功过的目标不会被判过期（无 last_success_at 即无新鲜度可言）")
    void neverSucceededTargetIsNotExpired() {
        long id = 91013L;
        withMapper(MonitorCollectionStateMapper.class, mapper -> {
            mapper.upsertObservation(failedState("HOST", id, at(0)));
            assertThat(mapper.selectExpiredTargets(at(9999)))
                    .as("没有成功记录的目标不应进入过期集合").isEmpty();
        });
    }

    // ---------- 批量读取 ----------

    @Test
    @DisplayName("selectByTargets 按目标类型与 id 批量取回")
    void selectByTargetsFiltersByType() {
        withMapper(MonitorCollectionStateMapper.class, mapper -> {
            mapper.upsertObservation(state("HOST", 91005L, "SUCCESS", at(0), "ONLINE", "UNKNOWN"));
            mapper.upsertObservation(state("SERVICE_INSTANCE", 91005L, "FAILED", at(0), null, null));

            List<MonitorCollectionState> hosts = mapper.selectByTargets("HOST", List.of(91005L, 91006L));
            assertThat(hosts).extracting(MonitorCollectionState::getTargetType).containsOnly("HOST");
            assertThat(hosts).extracting(MonitorCollectionState::getTargetId).containsExactly(91005L);
        });
    }

    // ---------- 目标状态写回只动采集字段 ----------

    @Test
    @DisplayName("主机状态写回只改 host_status 与 updated_at，人工资产字段与 Agent 字段原样不动")
    void hostStatusWriteOnlyTouchesCollectionFields() throws Exception {
        long hostId = insertHost(91007L, "host-collect-a", "192.0.2.22", "ONLINE-OLD", "AGENT-OLD");

        withMapper(AssetHostMapper.class, mapper ->
                assertThat(mapper.updateHostStatus(hostId, "ONLINE")).isEqualTo(1));

        try (Connection connection = connect();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT hostname, ip, os_type, host_type, cpu_cores, memory_gb, disk_gb, "
                             + "host_status, agent_status, last_heartbeat, updated_at "
                             + "FROM asset_host WHERE id = " + hostId)) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("host_status")).isEqualTo("ONLINE");
            assertThat(rs.getString("hostname")).as("主机名不得被采集改写").isEqualTo("host-collect-a");
            assertThat(rs.getString("ip")).as("IP 不得被采集改写").isEqualTo("192.0.2.22");
            assertThat(rs.getString("os_type")).as("规格类字段不得被采集改写").isEqualTo("LINUX");
            assertThat(rs.getString("host_type")).isEqualTo("PHYSICAL");
            assertThat(rs.getBigDecimal("cpu_cores")).isEqualByComparingTo("4.00");
            assertThat(rs.getString("agent_status"))
                    .as("agent_status 只能由真实 Agent 上报，采集不得改写").isEqualTo("AGENT-OLD");
            assertThat(rs.getObject("last_heartbeat"))
                    .as("last_heartbeat 不是采集的职责").isNotNull();
            assertThat(rs.getObject("updated_at")).isNotNull();
        }
    }

    @Test
    @DisplayName("主机状态复位是条件更新：值已被他方改写时不动")
    void hostResetIsConditional() throws Exception {
        long hostId = insertHost(91008L, "host-collect-b", "192.0.2.23", "ONLINE", null);

        withMapper(AssetHostMapper.class, mapper -> {
            assertThat(mapper.resetHostStatusIfObserved(hostId, "ONLINE", "UNKNOWN"))
                    .as("值仍是采集写入的值，应复位").isEqualTo(1);
            assertThat(mapper.resetHostStatusIfObserved(hostId, "ONLINE", "UNKNOWN"))
                    .as("值已变成 UNKNOWN，不该再匹配 ONLINE").isZero();
        });
    }

    @Test
    @DisplayName("实例两个维度独立复位：被他方改写的维度不动，仍匹配的维度才复位")
    void instanceResetHandlesDimensionsIndependently() throws Exception {
        long instanceId = insertInstance(91009L, "RUNNING", "UP", "SPRING_BOOT");

        withMapper(RuntimeServiceInstanceMapper.class, mapper ->
                assertThat(mapper.updateObservedStatus(instanceId, "RUNNING", "DOWN")).isEqualTo(1));

        // 他方（例如发布执行）把 instance_status 改成 DEPLOYING；health_status 仍是采集写入的 DOWN
        execute("UPDATE runtime_service_instance SET instance_status = 'DEPLOYING' WHERE id = " + instanceId);

        withMapper(RuntimeServiceInstanceMapper.class, mapper ->
                assertThat(mapper.resetObservedStatusIfMatched(instanceId, "RUNNING", "DOWN", "UNKNOWN", "UNKNOWN"))
                        .as("health 维度仍匹配，应发生复位").isEqualTo(1));

        try (Connection connection = connect();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT instance_status, health_status FROM runtime_service_instance WHERE id = " + instanceId)) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("instance_status"))
                    .as("该维度已是他方的值，不得被采集复位").isEqualTo("DEPLOYING");
            assertThat(rs.getString("health_status"))
                    .as("该维度仍匹配，应被复位").isEqualTo("UNKNOWN");
        }

        // 两个维度都被他方改写时，整行不动、返回 0
        execute("UPDATE runtime_service_instance SET instance_status = 'EXTERNAL', health_status = 'EXTERNAL' "
                + "WHERE id = " + instanceId);
        withMapper(RuntimeServiceInstanceMapper.class, mapper ->
                assertThat(mapper.resetObservedStatusIfMatched(instanceId, "RUNNING", "DOWN", "UNKNOWN", "UNKNOWN"))
                        .as("两个维度都不再匹配，应一行不改").isZero());
    }

    @Test
    @DisplayName("实例状态写回不动 instance_type / host_id 等身份字段")
    void instanceStatusWriteKeepsIdentityFields() throws Exception {
        long instanceId = insertInstance(91010L, "RUNNING", "UP", "PROCESS");

        withMapper(RuntimeServiceInstanceMapper.class, mapper ->
                assertThat(mapper.updateObservedStatus(instanceId, "STOPPED", "UNKNOWN")).isEqualTo(1));

        try (Connection connection = connect();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT instance_name, instance_type, instance_status, health_status, version_id, host_id "
                             + "FROM runtime_service_instance WHERE id = " + instanceId)) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("instance_status")).isEqualTo("STOPPED");
            assertThat(rs.getString("instance_type")).as("实例类型是身份信息，采集不得改写").isEqualTo("PROCESS");
            assertThat(rs.getString("instance_name")).isEqualTo(instanceName(instanceId));
            assertThat(rs.getObject("version_id")).as("部署版本来自发布记录，采集不得改写").isNull();
            assertThat(rs.getObject("host_id")).isNotNull();
        }
    }

    // ---------- fixtures ----------

    private static MonitorCollectionState state(String targetType, long targetId, String result,
                                                OffsetDateTime observedAt, String status, String health) {
        MonitorCollectionState state = new MonitorCollectionState();
        state.setTargetType(targetType);
        state.setTargetId(targetId);
        state.setObserverType("SSH_PROCESS");
        state.setConfigPresent(true);
        state.setLastSource("SSH_PROBE");
        state.setLastResult(result);
        state.setLastAttemptAt(observedAt);
        state.setLastSuccessAt("SUCCESS".equals(result) ? observedAt : null);
        state.setConsecutiveFailureCount(0);
        state.setLastObservedStatus(status);
        state.setLastObservedHealth(health);
        state.setObservedAt(observedAt);
        return state;
    }

    private static MonitorCollectionState failedState(String targetType, long targetId, OffsetDateTime observedAt) {
        MonitorCollectionState state = state(targetType, targetId, "FAILED", observedAt, null, null);
        state.setLastErrorCategory("NETWORK_TIMEOUT");
        state.setLastErrorSummary("连接超时");
        return state;
    }

    private static OffsetDateTime at(int minutesFromEpoch) {
        return OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC).plusMinutes(minutesFromEpoch);
    }

    private long insertHost(long id, String hostname, String ip, String hostStatus, String agentStatus)
            throws Exception {
        try (Connection connection = connect();
             Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO asset_host (id, hostname, ip, os_type, host_type, cpu_cores, "
                    + "memory_gb, disk_gb, host_status, agent_status, last_heartbeat, env_id, "
                    + "created_at, updated_at) VALUES ("
                    + id + ", '" + hostname + "', '" + ip + "', 'LINUX', 'PHYSICAL', 4.00, 8.00, 100.00, '"
                    + hostStatus + "', " + (agentStatus == null ? "NULL" : "'" + agentStatus + "'")
                    + ", TIMESTAMPTZ '2020-01-01 00:00:00+00', 1, "
                    + "TIMESTAMPTZ '2020-01-01 00:00:00+00', TIMESTAMPTZ '2020-01-01 00:00:00+00')");
        }
        return id;
    }

    private long insertInstance(long id, String instanceStatus, String healthStatus, String instanceType)
            throws Exception {
        // (env_id, instance_name) 上有唯一约束，名字带上 id 才不会跨用例撞车；host_id=1 是父行链里的主机。
        execute("INSERT INTO runtime_service_instance (id, service_id, env_id, host_id, "
                + "instance_name, instance_type, instance_status, health_status, created_at, updated_at) VALUES ("
                + id + ", 1, 1, 1, '" + instanceName(id) + "', '" + instanceType + "', '" + instanceStatus + "', '"
                + healthStatus + "', TIMESTAMPTZ '2020-01-01 00:00:00+00', "
                + "TIMESTAMPTZ '2020-01-01 00:00:00+00')");
        return id;
    }

    private static String instanceName(long id) {
        return "instance-collect-" + id;
    }

    private int countRows(String sql) throws Exception {
        try (Connection connection = connect();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
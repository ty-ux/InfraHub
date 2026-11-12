package com.infrahub.web.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * R7 的真实 Flyway 证据：打包约定、正常启动查表、新库路径、既有库升级路径。
 *
 * <p><b>为什么必须真的跑 Flyway</b>：R7 的原话是"实际在隔离实例上跑 Flyway，而不是只手工
 * 执行过滤后的 DDL"。手工执行过滤过的 DDL 恰好绕开了唯一会让它失败的东西，所以那类"验证"
 * 证明不了任何事。本类一律通过 {@link Flyway} 本身执行，连失败也照原样记录。
 *
 * <p><b>R7 实测结论（重要）</b>：新库路径目前**是断的**。
 * {@code V1__baseline_schema.sql} 第 609–612 行有四条 {@code COMMENT ON} 语句是坏的——
 * 文件内容曾被按错误字符集转换，中文变成乱码且收尾的单引号丢失，PostgreSQL 报
 * {@code 42601 syntax error}。因此"空库跑 Flyway"会在 V1 停下。本类不掩盖这一点：
 * {@link #historicalV1CommentsBlockFreshMigrationAndRollBackCleanly()} 把这个阻塞点写成断言，
 * 谁修好了 V1 就会看到它失败，从而必须显式更新结论，而不是让问题悄悄消失。
 *
 * <p><b>已有库路径仍然是通的</b>：按 {@code db/flyway/README.md} 记载的做法
 * （{@code baseline -baselineVersion=2} 然后 {@code migrate}），V28、V29 以及本轮新增的
 * {@code V30__add_alert_title_to_governance_alert_notification_record.sql} 都能被真实 Flyway
 * 正常执行，见 {@link #existingDatabaseFollowsDocumentedBaselineThenMigrate()}。
 * V30 用前向迁移补齐 V28 建表时漏掉的 {@code alert_title}（实体
 * {@code GovernanceAlertNotificationRecord#getAlertTitle()} 在用该列），
 * 而不是改写已应用的 V28——细节与隔离复现见
 * {@code NotificationAlertTitleIsolatedTest}。
 *
 * <p><b>为什么从 V28 起编号</b>：共享真实库的历史表由另一条迁移线写入，已占用 v3..v27，
 * 且其中 v3/v4 与本仓库重建的同号脚本同名不同内容 —— 在共享库上启动会被 checksum 校验直接拒绝。
 * 本仓库的迁移因此从 28 继续编号，永不与那条线冲突。细节与实测矩阵见
 * {@code docs/database-migration-paths.md} 的"共享真实库纪律"一节。
 *
 * <p><b>隔离纪律</b>：只在 127.0.0.1 的隔离实例上自建 {@code infrahub_it_*} 库；连接串来自
 * 仓库外的属性文件（经 {@code -Dinfrahub.it.pg.config} 传入，凭据不进 argv/日志）；
 * 绝不指向共享真实库；除了"模拟既有库"的那条用例显式对自己的临时库 baseline 之外，
 * 任何地方都不 baseline。每条用例一个独立库，用例结束只按**自己记录过的名字**删除，
 * 绝不按前缀枚举批量删除来源不明的库。
 */
class FlywayIsolatedPostgresTest {

    /** 共享真实库主机：出现即拒绝，避免误连 */
    private static final String SHARED_REAL_DB_HOST = "192.0.2.1";

    /** 版本受控的迁移脚本单一来源（仓库根相对路径） */
    private static final Path VERSION_CONTROLLED_SQL_DIRECTORY = Path.of("..", "db", "flyway", "sql");

    private static final List<String> SCRIPT_NAMES = List.of(
            "V1__baseline_schema.sql",
            "V2__seed_reference_data.sql",
            "V28__create_governance_alert_notification_record.sql",
            "V29__create_monitor_collection_state.sql",
            "V30__add_alert_title_to_governance_alert_notification_record.sql");

    private static String serverBaseUrl;
    private static String username;
    private static String password;
    private static String unavailableReason;

    /** 本类创建过的库名，仅用于按名删除自己造的东西 */
    private final List<String> ownedDatabases = new ArrayList<>();

    @BeforeAll
    static void loadSettings() {
        try {
            loadIsolatedSettings();
        } catch (RuntimeException exception) {
            unavailableReason = exception.getMessage();
        }
    }

    @AfterEach
    void tearDown() {
        for (String database : ownedDatabases) {
            dropOwnDisposableDatabase(database);
        }
        ownedDatabases.clear();
    }

    @Test
    @DisplayName("正常启动查表：application.yml 声明的 locations 能解析到打包进 classpath 的迁移脚本")
    void startupLocationsResolveOnTheClasspath() throws IOException {
        assumeAvailable();

        String location = declaredLocations(readClasspathText("application.yml"));
        assertThat(location)
                .as("配置里必须有一条 flyway.locations，且与本轮交付的打包目标目录一致")
                .isEqualTo("classpath:db/migration");

        for (String name : SCRIPT_NAMES) {
            assertThat(getClass().getClassLoader().getResource("db/migration/" + name))
                    .as("启动时按 %s 查表必须能查到 %s", location, name)
                    .isNotNull();
        }
        System.out.println("[it-flyway] locations=" + location
                + " resolvableScripts=" + SCRIPT_NAMES.size());
    }

    @Test
    @DisplayName("打包进 classpath 的脚本与版本受控源文件逐字节一致（没有会漂移的第二份副本）")
    void packagedScriptsAreByteIdenticalToVersionControlledSources() throws IOException {
        assumeAvailable();

        Path sourceDirectory = VERSION_CONTROLLED_SQL_DIRECTORY.toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isDirectory(sourceDirectory),
                "找不到版本受控脚本目录，跳过字节比对：" + sourceDirectory);

        for (String name : SCRIPT_NAMES) {
            String packagedHash = sha256(readClasspathBytes("db/migration/" + name));
            String sourceHash = sha256(Files.readAllBytes(sourceDirectory.resolve(name)));
            System.out.println("[it-flyway] " + name + " classpath=" + packagedHash + " source=" + sourceHash);
            assertThat(packagedHash)
                    .as("%s 在 classpath 与版本受控源文件之间必须一致，否则打包的就是另一份内容", name)
                    .isEqualTo(sourceHash);
        }
    }

    @Test
    @DisplayName("既有库路径：按文档 baseline -baselineVersion=2 后 migrate，只补做 V28/V29/V30")
    void existingDatabaseFollowsDocumentedBaselineThenMigrate() {
        assumeAvailable();
        String database = createOwnedDatabase();

        // db/flyway/README.md 记载的既有库做法：V1/V2 视为已建立的基线，之后从 V28 开始补。
        // 这里对**自己临时造的库**执行 baseline，是唯一一次 baseline，且不涉及任何共享库。
        Flyway baselined = configuration(database, null).baselineVersion("2").load();
        baselined.baseline();
        assertThat(appliedEntries(database))
                .as("baseline 只应写一条版本 2 的基线记录，不执行 V1/V2 的脚本内容")
                .containsExactly("2:BASELINE");

        MigrateResult upgraded = migrate(database, null);
        System.out.println("[it-flyway] existing-db upgrade database=" + database
                + " migrationsExecuted=" + upgraded.migrationsExecuted
                + " targetSchemaVersion=" + upgraded.targetSchemaVersion);
        assertThat(upgraded.migrationsExecuted)
                .as("既有库升级只应补做尚未应用的 V28/V29/V30，而不是从头执行 V1。"
                        + "这个数字刻意写死：新增迁移时必须显式回来更新，否则链条变了没人察觉")
                .isEqualTo(3);
        assertThat(appliedEntries(database))
                .as("历史表最终应为 2 基线 + V28 + V29 + V30")
                .containsExactly("2:BASELINE", "28:SQL", "29:SQL", "30:SQL");

        assertThat(existingTables(database))
                .as("V29 建的采集状态表必须在升级后真的存在")
                .contains("monitor_collection_state");
        for (String column : List.of("target_type", "target_id", "consecutive_failure_count",
                "last_observed_status", "observed_at")) {
            assertThat(columnExists(database, "monitor_collection_state", column))
                    .as("V29 的列 %s 必须存在，否则采集写回无处落脚", column)
                    .isTrue();
        }

        assertThat(migrate(database, null).migrationsExecuted)
                .as("再迁一次必须是空操作：迁移脚本不得依赖重复执行")
                .isZero();
    }

    @Test
    @DisplayName("新库路径当前被历史 V1 的坏 COMMENT 阻塞，且失败后整个迁移回滚干净")
    void historicalV1CommentsBlockFreshMigrationAndRollBackCleanly() {
        assumeAvailable();
        String database = createOwnedDatabase();

        assertThatThrownBy(() -> migrate(database, null))
                .as("空库上必须亲自跑一遍才会暴露 V1 的问题，这条断言就是这个已知阻塞点的记录")
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("V1__baseline_schema.sql failed")
                .hasMessageContaining("42601")
                .hasMessageContaining("Line");

        List<String> tables = existingTables(database);
        System.out.println("[it-flyway] fresh-db migration blocked at V1 database=" + database
                + " remainingTables=" + tables + " historyRows=" + appliedEntries(database));
        assertThat(tables)
                .as("迁移失败后 PostgreSQL 必须把整个 V1 回滚干净，不能留下半截 schema")
                .isEmpty();
        assertThat(appliedEntries(database))
                .as("失败的迁移不得在历史表里留下成功记录")
                .isEmpty();
    }

    @Test
    @DisplayName("既有库若没有历史表：Flyway 必须拒绝，不能被静默 baseline 掉")
    void existingDatabaseWithoutHistoryIsNotSilentlyBaselined() {
        assumeAvailable();
        String database = createOwnedDatabase();

        // 造一个"已经有表但没有 flyway_schema_history"的库：这正是手工 bootstrap 出来的形态。
        execute(database, "CREATE TABLE IF NOT EXISTS preexisting_object (id BIGINT PRIMARY KEY)");

        assertThatThrownBy(() -> migrate(database, null))
                .as("非空库且无历史表时，默认配置必须报错——这正是防止误动共享真实库的护栏")
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("non-empty schema");
        System.out.println("[it-flyway] refused-to-baseline database=" + database);

        assertThat(existingTables(database))
                .as("被拒绝之后不得留下任何迁移产物，除了它自己那张既有的表")
                .containsExactly("preexisting_object");
    }

    // ---------- Flyway 调用 ----------

    private static MigrateResult migrate(String database, String target) {
        return configuration(database, target).load().migrate();
    }

    private static org.flywaydb.core.api.configuration.FluentConfiguration configuration(
            String database, String target) {
        org.flywaydb.core.api.configuration.FluentConfiguration configuration = Flyway.configure()
                .dataSource(serverBaseUrl + "/" + database, username, password)
                .locations("classpath:db/migration")
                // 与 application.yml 一致：不自动 baseline、不允许 clean，避免"顺手把既有库抹平"
                .baselineOnMigrate(false)
                .cleanDisabled(true);
        if (target != null) {
            configuration = configuration.target(target);
        }
        return configuration;
    }

    /** 历史表里的 (版本:类型)，按安装顺序；用来直接看到"哪些真的执行过、哪些只是基线记录"。 */
    private static List<String> appliedEntries(String database) {
        if (!tableExists(database, "flyway_schema_history")) {
            return List.of();
        }
        List<String> entries = new ArrayList<>();
        try (Connection connection = connect(database);
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT version, type FROM flyway_schema_history WHERE success = true ORDER BY installed_rank")) {
            while (rows.next()) {
                entries.add(rows.getString(1) + ":" + rows.getString(2));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("读取历史表失败：" + exception.getMessage(), exception);
        }
        return entries;
    }

    private static List<String> existingTables(String database) {
        List<String> tables = new ArrayList<>();
        try (Connection connection = connect(database);
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'"
                             + " AND table_name <> 'flyway_schema_history' ORDER BY table_name")) {
            while (rows.next()) {
                tables.add(rows.getString(1));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("列出表失败：" + exception.getMessage(), exception);
        }
        return tables;
    }

    private static boolean tableExists(String database, String table) {
        return existingTables(database).contains(table) || "flyway_schema_history".equals(table)
                && rawTableExists(database, table);
    }

    private static boolean rawTableExists(String database, String table) {
        try (Connection connection = connect(database);
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT 1 FROM information_schema.tables WHERE table_schema = 'public'"
                             + " AND table_name = '" + table + "'")) {
            return rows.next();
        } catch (SQLException exception) {
            throw new IllegalStateException("检查表是否存在失败：" + exception.getMessage(), exception);
        }
    }

    private static boolean columnExists(String database, String table, String column) {
        try (Connection connection = connect(database);
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT 1 FROM information_schema.columns WHERE table_schema = 'public'"
                             + " AND table_name = '" + table + "' AND column_name = '" + column + "'")) {
            return rows.next();
        } catch (SQLException exception) {
            throw new IllegalStateException("检查列是否存在失败：" + exception.getMessage(), exception);
        }
    }

    // ---------- 隔离实例 ----------

    private void assumeAvailable() {
        Assumptions.assumeTrue(unavailableReason == null, unavailableReason);
    }

    private static void loadIsolatedSettings() {
        String configPath = System.getProperty("infrahub.it.pg.config");
        if (configPath == null || configPath.isBlank()) {
            throw new IllegalStateException(
                    "未提供 -Dinfrahub.it.pg.config，跳过隔离库验证（本类绝不连默认配置里的库）");
        }
        Path path = Path.of(configPath);
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("隔离库配置文件不存在：" + path);
        }
        try (InputStream input = Files.newInputStream(path)) {
            Properties properties = new Properties();
            properties.load(input);
            String url = required(properties, "url");
            username = required(properties, "username");
            password = properties.getProperty("password", "");
            if (url.contains(SHARED_REAL_DB_HOST)) {
                throw new IllegalStateException("拒绝连接共享真实库：" + SHARED_REAL_DB_HOST);
            }
            int lastSlash = url.lastIndexOf('/');
            if (lastSlash < 0) {
                throw new IllegalStateException("隔离库 url 缺少数据库名：" + url);
            }
            serverBaseUrl = url.substring(0, lastSlash);
        } catch (IOException exception) {
            throw new IllegalStateException("读取隔离库配置失败：" + exception.getMessage(), exception);
        }
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("隔离库配置缺少 " + key);
        }
        return value;
    }

    private String createOwnedDatabase() {
        String name = "infrahub_it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        try (Connection connection = DriverManager.getConnection(serverBaseUrl + "/postgres", username, password);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + name);
        } catch (SQLException exception) {
            throw new IllegalStateException("创建隔离库失败：" + exception.getMessage(), exception);
        }
        ownedDatabases.add(name);
        System.out.println("[it-flyway] created own disposable database " + name);
        return name;
    }

    /** 只删本类**自己创建并记录过名字**的库；绝不按前缀枚举批量删除。 */
    private static void dropOwnDisposableDatabase(String name) {
        if (name == null || serverBaseUrl == null) {
            return;
        }
        try (Connection connection = DriverManager.getConnection(serverBaseUrl + "/postgres", username, password);
             Statement statement = connection.createStatement()) {
            statement.execute("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '"
                    + name + "' AND pid <> pg_backend_pid()");
            statement.execute("DROP DATABASE IF EXISTS " + name);
            System.out.println("[it-flyway] dropped own disposable database " + name);
        } catch (SQLException exception) {
            System.out.println("[it-flyway] WARN could not drop own disposable database " + name
                    + ": " + exception.getMessage());
        }
    }

    private static Connection connect(String database) throws SQLException {
        return DriverManager.getConnection(serverBaseUrl + "/" + database, username, password);
    }

    private static void execute(String database, String sql) {
        try (Connection connection = connect(database);
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException exception) {
            throw new IllegalStateException("在隔离库上执行语句失败：" + exception.getMessage(), exception);
        }
    }

    // ---------- classpath 读取 ----------

    private static byte[] readClasspathBytes(String resource) throws IOException {
        try (InputStream input = getClassLoaderResource(resource)) {
            return input.readAllBytes();
        }
    }

    private static String readClasspathText(String resource) throws IOException {
        return new String(readClasspathBytes(resource), StandardCharsets.UTF_8);
    }

    private static InputStream getClassLoaderResource(String resource) {
        InputStream input = FlywayIsolatedPostgresTest.class.getClassLoader().getResourceAsStream(resource);
        if (input == null) {
            throw new IllegalStateException("classpath 上找不到 " + resource);
        }
        return input;
    }

    /** 从 application.yml 里取出 flyway 段声明的 locations，避免测试写死一个可能与配置不符的值。 */
    private static String declaredLocations(String configuration) {
        boolean inFlywaySection = false;
        for (String rawLine : configuration.split("\n")) {
            String line = rawLine.strip();
            if (line.startsWith("flyway:")) {
                inFlywaySection = true;
                continue;
            }
            if (inFlywaySection) {
                if (!rawLine.startsWith(" ") || line.isEmpty()) {
                    inFlywaySection = false;
                    continue;
                }
                if (line.startsWith("locations:")) {
                    return line.substring("locations:".length()).strip();
                }
            }
        }
        throw new IllegalStateException("application.yml 里没有找到 flyway.locations");
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("缺少 SHA-256 实现", exception);
        }
    }
}
package com.infrahub.web.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 新迁移治理测试共用的隔离设施：连接、自建一次性库、脚本副本、历史造数。
 *
 * <p>集中放一份的原因：隔离纪律本身是安全关键代码（连错库、删错库的代价是真实的）。
 * 把它摊在每个测试类里各写一遍，等于让"护栏"有多个可能悄悄分叉的副本。
 * 已有的 {@link FlywayIsolatedPostgresTest} 保持原样不动，本类只服务本轮新增的用例。
 *
 * <p><b>隔离纪律（与既有测试一致）</b>：
 * <ul>
 *   <li>只在 {@code -Dinfrahub.it.pg.config} 指向的隔离实例上工作，连接串里出现共享真实库
 *       主机名即拒绝加载，测试报 Skipped 而不是去连默认配置里的库；</li>
 *   <li>每条用例一个自建 {@code infrahub_it_*} 库，用例结束<b>只按自己记录过的名字</b>删除，
 *       绝不按前缀枚举批量删除来源不明的库；</li>
 *   <li>要"删脚本 / 改脚本"的用例一律在 {@code java.io.tmpdir} 下的脚本副本上做，
 *       <b>绝不触碰版本受控的仓库文件</b>；同样只删自己创建并记录过的临时目录；</li>
 *   <li>除"模拟既有库"用例显式对自己的临时库 baseline 之外，任何地方都不 baseline，
 *       且始终 {@code cleanDisabled(true)}。</li>
 * </ul>
 */
abstract class MigrationIsolationSupport {

    /** 共享真实库主机：出现即拒绝，避免误连 */
    static final String SHARED_REAL_DB_HOST = "192.0.2.1";

    /** 版本受控的迁移脚本单一来源（模块目录相对路径；surefire 的工作目录是模块根） */
    static final Path VERSION_CONTROLLED_SQL_DIRECTORY = Path.of("..", "db", "flyway", "sql");

    /** 版本受控的缺失迁移例外清单 */
    static final Path VERSION_CONTROLLED_ALLOWLIST =
            Path.of("..", "db", "flyway", "legacy-missing-migration-allowlist.txt");

    /** 与 application.yml 里 {@code ignore-migration-patterns} 一致的兼容策略 */
    static final String COMPATIBILITY_POLICY = "*:missing";

    /** 本仓库重建迁移线的起始版本，与 {@link LegacyMissingMigrationGate#NEW_LINE_FIRST_VERSION} 对齐 */
    static final String NEW_LINE_FIRST_VERSION = "28";

    private static String serverBaseUrl;
    private static String username;
    private static String password;
    private static String unavailableReason;

    /** 本类创建过的库名，仅用于按名删除自己造的东西 */
    private final List<String> ownedDatabases = new ArrayList<>();

    /** 本类创建过的临时脚本目录，仅用于按名删除自己造的东西 */
    private final List<Path> ownedScratchDirectories = new ArrayList<>();

    @BeforeAll
    static void loadIsolationSettings() {
        try {
            readIsolatedSettings();
        } catch (RuntimeException exception) {
            unavailableReason = exception.getMessage();
        }
    }

    @AfterEach
    void dropOwnedResources() {
        for (String database : ownedDatabases) {
            dropOwnedDatabase(database);
        }
        ownedDatabases.clear();
        for (Path directory : ownedScratchDirectories) {
            deleteOwnedScratchDirectory(directory);
        }
        ownedScratchDirectories.clear();
    }

    // ---------- 隔离实例 ----------

    protected void assumeAvailable() {
        Assumptions.assumeTrue(unavailableReason == null, unavailableReason);
    }

    /** 隔离实例是否可用；用于"不依赖数据库也应常绿"的接线用例。 */
    protected static boolean isolationAvailable() {
        return unavailableReason == null;
    }

    private static void readIsolatedSettings() {
        String configPath = System.getProperty("infrahub.it.pg.config");
        if (configPath == null || configPath.isBlank()) {
            configPath = System.getenv("INFRAHUB_IT_PG_CONFIG");
        }
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
                throw new IllegalStateException("隔离库 url 缺少数据库名");
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

    protected String createOwnedDatabase() {
        String name = "infrahub_it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        try (Connection connection = adminConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + name);
        } catch (SQLException exception) {
            throw new IllegalStateException("创建隔离库失败：" + exception.getMessage(), exception);
        }
        ownedDatabases.add(name);
        System.out.println("[it-migration] created own disposable database " + name);
        return name;
    }

    /** 只删本类**自己创建并记录过名字**的库；绝不按前缀枚举批量删除。 */
    private static void dropOwnedDatabase(String name) {
        if (name == null || serverBaseUrl == null) {
            return;
        }
        try (Connection connection = adminConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '"
                    + name + "' AND pid <> pg_backend_pid()");
            statement.execute("DROP DATABASE IF EXISTS " + name);
            System.out.println("[it-migration] dropped own disposable database " + name);
        } catch (SQLException exception) {
            System.out.println("[it-migration] WARN could not drop own disposable database " + name
                    + ": " + exception.getMessage());
        }
    }

    private static Connection adminConnection() throws SQLException {
        return DriverManager.getConnection(serverBaseUrl + "/postgres", username, password);
    }

    protected static Connection connect(String database) throws SQLException {
        return DriverManager.getConnection(serverBaseUrl + "/" + database, username, password);
    }

    /**
     * 供实体契约测试自建 MyBatis-Plus 会话用的数据源。
     *
     * <p>连接信息留在本类，测试类不需要拿到用户名口令。
     */
    protected static javax.sql.DataSource dataSourceFor(String database) {
        return new org.apache.ibatis.datasource.unpooled.UnpooledDataSource(
                "org.postgresql.Driver", serverBaseUrl + "/" + database, username, password);
    }

    protected static void execute(String database, String sql) {
        try (Connection connection = connect(database);
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException exception) {
            throw new IllegalStateException("在隔离库上执行语句失败：" + exception.getMessage(), exception);
        }
    }

    // ---------- 脚本副本 ----------

    /**
     * 把版本受控脚本复制到临时目录，供需要"删掉/改动某个脚本"的用例使用。
     * 只复制 {@code *.sql}，因此不会把 {@code db/flyway/sql} 下的构建残留目录带进来。
     */
    protected Path copyVersionControlledScripts() {
        Path sourceDirectory = VERSION_CONTROLLED_SQL_DIRECTORY.toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isDirectory(sourceDirectory),
                "找不到版本受控脚本目录，跳过：" + sourceDirectory);
        Path scratch = Path.of(System.getProperty("java.io.tmpdir"), "infrahub-it-scripts-"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        try {
            Files.createDirectories(scratch);
            try (Stream<Path> files = Files.list(sourceDirectory)) {
                for (Path script : files.filter(path -> path.getFileName().toString().endsWith(".sql")).toList()) {
                    Files.copy(script, scratch.resolve(script.getFileName().toString()),
                            StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("复制脚本到临时目录失败：" + exception.getMessage(), exception);
        }
        ownedScratchDirectories.add(scratch);
        return scratch;
    }

    /** 只删本类自己创建并记录过的临时目录。 */
    private static void deleteOwnedScratchDirectory(Path directory) {
        if (directory == null || !Files.isDirectory(directory)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
            System.out.println("[it-migration] deleted own scratch directory " + directory);
        } catch (IOException exception) {
            System.out.println("[it-migration] WARN could not delete own scratch directory " + directory
                    + ": " + exception.getMessage());
        }
    }

    // ---------- Flyway ----------

    protected static FluentConfiguration flywayConfiguration(
            String database, Path scriptsDirectory, String... ignorePatterns) {
        FluentConfiguration configuration = Flyway.configure()
                .dataSource(serverBaseUrl + "/" + database, username, password)
                .locations("filesystem:" + scriptsDirectory.toAbsolutePath().normalize()
                        .toString().replace('\\', '/'))
                // 与 application.yml 一致：不自动 baseline、不允许 clean
                .baselineOnMigrate(false)
                .cleanDisabled(true);
        if (ignorePatterns.length > 0) {
            configuration = configuration.ignoreMigrationPatterns(ignorePatterns);
        }
        return configuration;
    }

    /** 把空库做成"既有库"：只写入 baseline=2 锚点，不造旧迁移线的历史行。 */
    protected static void seedBaselineOnly(String database) {
        Flyway.configure()
                .dataSource(serverBaseUrl + "/" + database, username, password)
                .locations("filesystem:" + copyRootForBaselining())
                .baselineVersion("2")
                .cleanDisabled(true)
                .load()
                .baseline();
        System.out.println("[it-migration] seeded baseline anchor version=2 database=" + database);
    }

    /** 把临时库做成"既有库"：写入 baseline=2 锚点，再按清单身份补上旧迁移线的历史行。 */
    protected static void seedLegacyHistory(String database, LegacyMissingMigrationAllowlist allowlist) {
        seedBaselineOnly(database);

        List<LegacyMissingMigrationAllowlist.Identity> legacy = allowlist.entries().stream()
                .filter(identity -> !"BASELINE".equals(identity.type()))
                .filter(identity -> !NEW_LINE_FIRST_VERSION.equals(identity.version()))
                .filter(identity -> Integer.parseInt(identity.version()) < Integer.parseInt(NEW_LINE_FIRST_VERSION))
                .toList();
        try (Connection connection = connect(database);
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO flyway_schema_history(installed_rank, version, description, type, script,"
                             + " checksum, installed_by, execution_time, success) VALUES (?,?,?,?,?,?,?,?,?)")) {
            int rank = 2;
            for (LegacyMissingMigrationAllowlist.Identity identity : legacy) {
                statement.setInt(1, rank++);
                statement.setString(2, identity.version());
                statement.setString(3, identity.script().replace(".sql", "").replace('_', ' '));
                statement.setString(4, identity.type());
                statement.setString(5, identity.script());
                statement.setInt(6, identity.checksum());
                statement.setString(7, "it-synthetic");
                statement.setInt(8, 0);
                statement.setBoolean(9, true);
                statement.executeUpdate();
            }
            System.out.println("[it-migration] seeded legacy history rows=" + legacy.size()
                    + " database=" + database);
        } catch (SQLException exception) {
            throw new IllegalStateException("写入旧迁移线历史失败：" + exception.getMessage(), exception);
        }
    }

    /**
     * 插入一条"库里已应用、但清单里没有"的历史行，用来验证允许集之外的缺失会被拒绝。
     */
    protected static void seedUnexpectedHistory(String database, String version, String script, int checksum) {
        try (Connection connection = connect(database);
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO flyway_schema_history(installed_rank, version, description, type, script,"
                             + " checksum, installed_by, execution_time, success)"
                             + " VALUES ((SELECT COALESCE(MAX(installed_rank), 0) + 1 FROM flyway_schema_history),"
                             + " ?, ?, 'SQL', ?, ?, 'it-synthetic', 0, true)")) {
            statement.setString(1, version);
            statement.setString(2, script.replace(".sql", "").replace('_', ' '));
            statement.setString(3, script);
            statement.setInt(4, checksum);
            statement.executeUpdate();
            System.out.println("[it-migration] seeded unexpected history version=" + version
                    + " script=" + script + " checksum=" + checksum + " database=" + database);
        } catch (SQLException exception) {
            throw new IllegalStateException("写入计划外历史失败：" + exception.getMessage(), exception);
        }
    }

    /** baseline 需要一个 locations 值，但此时脚本目录无关紧要；用一个空的临时目录即可。 */
    private static String copyRootForBaselining() {
        Path empty = Path.of(System.getProperty("java.io.tmpdir"), "infrahub-it-empty-location");
        try {
            Files.createDirectories(empty);
        } catch (IOException exception) {
            throw new IllegalStateException("准备空 locations 目录失败：" + exception.getMessage(), exception);
        }
        return empty.toAbsolutePath().normalize().toString().replace('\\', '/');
    }

    // ---------- 查询 ----------

    /** 历史表里的 (版本:类型)，按安装顺序。 */
    protected static List<String> appliedEntries(String database) {
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

    protected static boolean tableExists(String database, String table) {
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

    /** 列名 -> "data_type|is_nullable|character_maximum_length"，用于断言列定义而不是只看存在性。 */
    protected static Map<String, String> columnsOf(String database, String table) {
        Map<String, String> columns = new LinkedHashMap<>();
        try (Connection connection = connect(database);
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT column_name, data_type, is_nullable, COALESCE(character_maximum_length, -1)"
                             + " FROM information_schema.columns WHERE table_schema = 'public'"
                             + " AND table_name = '" + table + "' ORDER BY ordinal_position")) {
            while (rows.next()) {
                columns.put(rows.getString(1), rows.getString(2) + "|" + rows.getString(3) + "|" + rows.getString(4));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("读取列信息失败：" + exception.getMessage(), exception);
        }
        return columns;
    }

    protected static List<String> indexNames(String database, String table) {
        List<String> indexes = new ArrayList<>();
        try (Connection connection = connect(database);
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT indexname FROM pg_indexes WHERE schemaname = 'public' AND tablename = '"
                             + table + "' ORDER BY indexname")) {
            while (rows.next()) {
                indexes.add(rows.getString(1));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("读取索引失败：" + exception.getMessage(), exception);
        }
        return indexes;
    }

    protected static String singleValue(String database, String sql) {
        try (Connection connection = connect(database);
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            return rows.next() ? rows.getString(1) : null;
        } catch (SQLException exception) {
            throw new IllegalStateException("查询失败：" + exception.getMessage(), exception);
        }
    }

    // ---------- classpath ----------

    protected static byte[] readClasspathBytes(String resource) {
        try (InputStream input = getClassLoaderResource(resource)) {
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new IllegalStateException("读取 classpath 资源失败：" + resource, exception);
        }
    }

    protected static String readClasspathText(String resource) {
        return new String(readClasspathBytes(resource), StandardCharsets.UTF_8);
    }

    protected static InputStream getClassLoaderResource(String resource) {
        InputStream input = MigrationIsolationSupport.class.getClassLoader().getResourceAsStream(resource);
        if (input == null) {
            throw new IllegalStateException("classpath 上找不到 " + resource
                    + "（构建配置是否漏了对应的 <resource> 映射？）");
        }
        return input;
    }

    protected static boolean classpathResourceExists(String resource) {
        return MigrationIsolationSupport.class.getClassLoader().getResource(resource) != null;
    }

    protected static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("缺少 SHA-256 实现", exception);
        }
    }
}
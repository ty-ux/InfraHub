package com.infrahub.web.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 缺失迁移例外闸门的常驻回归。
 *
 * <p><b>为什么不拿既有那五个 Flyway 测试当覆盖</b>：那五个用例走的是"baseline=2 的空库"，
 * 库里根本没有旧迁移线 V3–V27 的历史，因此它们从来不会进入"已应用但解析不到脚本"这条路径
 * ——而这条路径正是 {@code ignore-migration-patterns="*:missing"} 会放过的、也是本闸门要收窄的。
 * 换句话说，那五个用例<b>结构上无法</b>覆盖本类要证明的东西。
 *
 * <p>覆盖的场景（对照任务书三.3）：
 * <ul>
 *   <li>旧历史身份在清单内、但<b>不</b>使用兼容策略 → 被拒绝（清单不能替代策略）；</li>
 *   <li>旧历史身份在清单内 + 使用兼容策略 → 兼容并正常迁移；</li>
 *   <li>正常迁移成功，再迁一次 → 0；</li>
 *   <li>本仓库已应用的新迁移被<b>篡改</b> → 被拒绝；</li>
 *   <li>本仓库已应用的新迁移被<b>删除</b> → 被拒绝；</li>
 *   <li>允许集之外的缺失历史 → 被拒绝；</li>
 *   <li>清单内某条<b>身份不一致</b>（与"自动把库里任意缺失记入允许集"相反的方向）→ 被拒绝；</li>
 *   <li>清单自身被塞进本仓库自有迁移线的版本 → 拒绝，且<b>不依赖数据库可达</b>；</li>
 *   <li>拒绝发生在迁移<b>之前</b>：走生产的 {@link FlywayMigrationStrategy} bean，
 *       抛错后历史表没有任何新增行。</li>
 * </ul>
 *
 * <p>隔离纪律见 {@link MigrationIsolationSupport}：每个用例一个自建一次性库，要删/要改的脚本
 * 一律在临时目录的副本上做，绝不触碰版本受控的仓库文件。
 */
@DisplayName("缺失迁移例外闸门：迁移前拒绝，允许集之外一律不放行")
class LegacyMissingMigrationGateIsolatedTest extends MigrationIsolationSupport {

    private static final String V28_SCRIPT = "V28__create_governance_alert_notification_record.sql";
    private static final String V29_SCRIPT = "V29__create_monitor_collection_state.sql";

    /** 共享真实库里 V29 的已记录校验和；本仓库工作区脚本复算得到同一值 */
    private static final int V29_RECORDED_CHECKSUM = -388601075;

    // ---------- 不依赖数据库：清单与接线 ----------

    @Test
    @DisplayName("版本受控的例外清单只冻结旧迁移线，不含本仓库自有迁移线的版本")
    void allowlistFreezesOnlyTheLegacyLine() throws IOException {
        LegacyMissingMigrationAllowlist allowlist = LegacyMissingMigrationAllowlist.load();

        assertThat(allowlist.entries()).hasSize(26);
        assertThat(allowlist.find("2")).isNotNull();
        assertThat(allowlist.find("2").type()).isEqualTo("BASELINE");
        assertThat(allowlist.find("27")).isNotNull();
        assertThat(allowlist.find("28")).isNull();
        assertThat(allowlist.find("29")).isNull();
        assertThat(allowlist.find("30")).isNull();
        assertThat(allowlist.atOrAbove(LegacyMissingMigrationGate.NEW_LINE_FIRST_VERSION)).isEmpty();

        String versionControlled = Files.readString(VERSION_CONTROLLED_ALLOWLIST, StandardCharsets.UTF_8);
        assertThat(allowlist.entries().size())
                .as("清单条目数应等于文件里去掉注释与空行后的行数")
                .isEqualTo((int) versionControlled.lines()
                        .filter(line -> !line.isBlank() && !line.startsWith("#"))
                        .count());
    }

    @Test
    @DisplayName("例外清单被塞进本仓库自有迁移线的版本：即使数据库不可达也必须拒绝")
    void allowlistCreepIsRejectedWithoutAnyDatabase() throws IOException {
        String crept = Files.readString(VERSION_CONTROLLED_ALLOWLIST, StandardCharsets.UTF_8)
                + "\n28|SQL|" + V28_SCRIPT + "|1624839510\n";
        LegacyMissingMigrationAllowlist creepedAllowlist = LegacyMissingMigrationAllowlist.parse(crept);

        List<String> violations = LegacyMissingMigrationGate.allowlistCreep(creepedAllowlist);
        assertThat(violations).hasSize(1);
        assertThat(violations.get(0)).contains("例外清单声明了本仓库自有迁移线的版本 28");

        // 指向一个不可达的数据源：闸门必须先因清单而拒绝，而不是抛连接错误。
        Path existingLocation = Path.of(System.getProperty("java.io.tmpdir"));
        Flyway unreachable = Flyway.configure()
                .dataSource("jdbc:postgresql://127.0.0.1:1/never_connected", "nobody", "nobody")
                .locations("filesystem:" + existingLocation.toAbsolutePath().normalize()
                        .toString().replace('\\', '/'))
                .cleanDisabled(true)
                .load();

        assertThatThrownBy(() -> LegacyMissingMigrationGate.verify(unreachable, creepedAllowlist))
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("闸门未通过")
                .hasMessageContaining("例外清单声明了本仓库自有迁移线的版本 28");
    }

    @Test
    @DisplayName("打包产物的两处映射都生效：脚本目录与例外清单目录")
    void packagedArtifactsAreByteIdenticalToVersionControlledSources() throws IOException {
        String allowlistResource = "db/migration-policy/legacy-missing-migration-allowlist.txt";
        assertThat(classpathResourceExists(allowlistResource))
                .as("db/flyway → classpath db/migration-policy 的映射必须生效，否则闸门读不到清单")
                .isTrue();
        assertThat(sha256(readClasspathBytes(allowlistResource)))
                .isEqualTo(sha256(Files.readAllBytes(VERSION_CONTROLLED_ALLOWLIST)));

        String v30Resource = "db/migration/V30__add_alert_title_to_governance_alert_notification_record.sql";
        assertThat(classpathResourceExists(v30Resource))
                .as("V30 必须被打包，否则已应用 V28 的库升级不到实体所需列")
                .isTrue();
        assertThat(sha256(readClasspathBytes(v30Resource)))
                .isEqualTo(sha256(Files.readAllBytes(VERSION_CONTROLLED_SQL_DIRECTORY.resolve(
                        "V30__add_alert_title_to_governance_alert_notification_record.sql"))));
    }

    @Test
    @DisplayName("生产接线暴露 FlywayMigrationStrategy bean，而不是默认的直接迁移")
    void productionConfigExposesMigrationStrategyBean() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(com.infrahub.web.config.FlywayMigrationPolicyConfig.class)) {
            FlywayMigrationStrategy strategy = context.getBean(FlywayMigrationStrategy.class);
            assertThat(strategy).as("闸门必须挂在 Spring Boot 启动路径上，而不是靠人记得执行命令").isNotNull();
        }
    }

    // ---------- 依赖隔离实例 ----------

    @Test
    @DisplayName("既有库正常迁移：闸门放行，28/29/30 执行，再迁一次为 0")
    void normalMigrationOnExistingDatabasePassesGate() {
        assumeAvailable();
        LegacyMissingMigrationAllowlist allowlist = LegacyMissingMigrationAllowlist.load();
        String database = createOwnedDatabase();
        Path scripts = copyVersionControlledScripts();

        seedBaselineOnly(database);

        Flyway flyway = flywayConfiguration(database, scripts, COMPATIBILITY_POLICY).load();
        LegacyMissingMigrationGate.verify(flyway, allowlist);

        MigrateResult first = flyway.migrate();
        assertThat(first.migrationsExecuted).isEqualTo(3);
        assertThat(appliedEntries(database))
                .containsExactly("2:BASELINE", "28:SQL", "29:SQL", "30:SQL");

        // 迁移已完成，此时没有任何 pending，闸门必须仍然放行（若把 pending 之外的错误也容忍掉就会掩盖问题）
        Flyway afterMigration = flywayConfiguration(database, scripts, COMPATIBILITY_POLICY).load();
        LegacyMissingMigrationGate.verify(afterMigration, allowlist);
        assertThat(afterMigration.migrate().migrationsExecuted).as("再迁一次必须为 0").isZero();
    }

    @Test
    @DisplayName("允许集内的旧历史 + 兼容策略：通过生产 bean 兼容并迁移")
    void legacyHistoryInsideAllowlistIsCompatible() {
        assumeAvailable();
        LegacyMissingMigrationAllowlist allowlist = LegacyMissingMigrationAllowlist.load();
        String database = createOwnedDatabase();
        Path scripts = copyVersionControlledScripts();
        seedLegacyHistory(database, allowlist);

        Flyway flyway = flywayConfiguration(database, scripts, COMPATIBILITY_POLICY).load();
        // FlywayMigrationStrategy.migrate 在 Spring Boot 3.3.5 上返回 void，所以迁移结果从库状态与
        // 随后的 Flyway 观察，而不是从 bean 的返回值取。
        productionStrategy().migrate(flyway);

        assertThat(appliedEntries(database))
                .startsWith("2:BASELINE")
                .containsSubsequence("26:SQL", "27:SQL", "28:SQL", "29:SQL", "30:SQL");
        assertThat(flywayConfiguration(database, scripts, COMPATIBILITY_POLICY).load().migrate().migrationsExecuted)
                .as("再迁一次必须为 0")
                .isZero();
    }

    @Test
    @DisplayName("旧历史身份在清单内、但不使用兼容策略：仍被拒绝（清单不能替代策略）")
    void legacyHistoryWithoutCompatibilityPolicyIsRejected() {
        assumeAvailable();
        LegacyMissingMigrationAllowlist allowlist = LegacyMissingMigrationAllowlist.load();
        String database = createOwnedDatabase();
        Path scripts = copyVersionControlledScripts();
        seedLegacyHistory(database, allowlist);

        Flyway flyway = flywayConfiguration(database, scripts).load();
        List<String> violations = LegacyMissingMigrationGate.violations(flyway, allowlist);

        long legacyRows = allowlist.entries().stream().filter(identity -> "SQL".equals(identity.type())).count();
        assertThat(violations).hasSize((int) legacyRows);
        assertThat(violations).allSatisfy(violation -> assertThat(violation)
                .contains("迁移校验未通过且不属于兼容例外")
                .contains("APPLIED_VERSIONED_MIGRATION_NOT_RESOLVED"));
        assertThat(violations).as("清单接受了这些旧历史身份，拒绝的原因只能是缺少兼容策略")
                .noneSatisfy(violation -> assertThat(violation).contains("不在例外清单内"));
    }

    @Test
    @DisplayName("拒绝发生在迁移之前：抛错后历史表没有任何新增行")
    void gateRejectsBeforeMigrating() {
        assumeAvailable();
        LegacyMissingMigrationAllowlist allowlist = LegacyMissingMigrationAllowlist.load();
        String database = createOwnedDatabase();
        Path scripts = copyVersionControlledScripts();
        seedLegacyHistory(database, allowlist);
        List<String> before = appliedEntries(database);

        Flyway flyway = flywayConfiguration(database, scripts).load();
        assertThatThrownBy(() -> productionStrategy().migrate(flyway))
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("闸门未通过")
                .hasMessageContaining("数据库未被改动");

        assertThat(appliedEntries(database))
                .as("闸门若在迁移之后才判定，这里就会出现 28/29/30")
                .containsExactlyElementsOf(before);
        assertThat(tableExists(database, "monitor_collection_state"))
                .as("V29 不得执行，说明拒绝确实早于迁移")
                .isFalse();
    }

    @Test
    @DisplayName("本仓库已应用的新迁移被删除：拒绝，且不写入任何历史")
    void deletingThisRepositoryNewMigrationIsRejected() throws IOException {
        assumeAvailable();
        LegacyMissingMigrationAllowlist allowlist = LegacyMissingMigrationAllowlist.load();
        String database = createOwnedDatabase();
        Path scripts = copyVersionControlledScripts();
        migrateFullChain(database, scripts, allowlist);
        List<String> afterFullChain = appliedEntries(database);

        Files.delete(scripts.resolve(V28_SCRIPT));

        Flyway flyway = flywayConfiguration(database, scripts, COMPATIBILITY_POLICY).load();
        List<String> violations = LegacyMissingMigrationGate.violations(flyway, allowlist);

        assertThat(violations).isNotEmpty();
        assertThat(violations).anySatisfy(violation -> assertThat(violation)
                .contains("版本 28")
                .contains("不在例外清单内"));
        assertThat(appliedEntries(database)).containsExactlyElementsOf(afterFullChain);
    }

    @Test
    @DisplayName("本仓库已应用的新迁移被篡改：拒绝，且 info() 不足以发现（必须走 validate）")
    void tamperingWithAppliedNewMigrationIsRejected() throws IOException {
        assumeAvailable();
        LegacyMissingMigrationAllowlist allowlist = LegacyMissingMigrationAllowlist.load();
        String database = createOwnedDatabase();
        Path scripts = copyVersionControlledScripts();
        migrateFullChain(database, scripts, allowlist);

        Files.writeString(scripts.resolve(V29_SCRIPT),
                "\n-- tampered by regression\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);

        Flyway flyway = flywayConfiguration(database, scripts, COMPATIBILITY_POLICY).load();

        // 这一点是"闸门不能只靠 info() 判状态"的实测依据：脚本已被改动，info() 仍报数据库里的旧校验和。
        assertThat(checksumOfVersion(flyway, "29"))
                .as("info() 报的是库里的旧校验和，因此单看状态发现不了篡改")
                .isEqualTo(V29_RECORDED_CHECKSUM);

        List<String> violations = LegacyMissingMigrationGate.violations(flyway, allowlist);
        assertThat(violations).isNotEmpty();
        assertThat(violations).allSatisfy(violation -> assertThat(violation)
                .contains("迁移校验未通过且不属于兼容例外")
                .contains("CHECKSUM_MISMATCH"));
    }

    @Test
    @DisplayName("允许集之外的缺失历史：拒绝")
    void missingHistoryOutsideAllowlistIsRejected() {
        assumeAvailable();
        LegacyMissingMigrationAllowlist allowlist = LegacyMissingMigrationAllowlist.load();
        String database = createOwnedDatabase();
        Path scripts = copyVersionControlledScripts();
        seedLegacyHistory(database, allowlist);
        seedUnexpectedHistory(database, "100", "V100__not_in_allowlist.sql", 845726);

        Flyway flyway = flywayConfiguration(database, scripts, COMPATIBILITY_POLICY).load();
        List<String> violations = LegacyMissingMigrationGate.violations(flyway, allowlist);

        assertThat(violations).anySatisfy(violation -> assertThat(violation)
                .contains("版本 100")
                .contains("不在例外清单内"));
    }

    @Test
    @DisplayName("清单内某条历史身份不一致：拒绝（不允许拿库里现状去放宽清单）")
    void legacyIdentityMismatchIsRejected() {
        assumeAvailable();
        LegacyMissingMigrationAllowlist allowlist = LegacyMissingMigrationAllowlist.load();
        String database = createOwnedDatabase();
        Path scripts = copyVersionControlledScripts();
        seedLegacyHistory(database, allowlist);

        execute(database, "UPDATE flyway_schema_history SET checksum = checksum + 1 WHERE version = '26'");

        Flyway flyway = flywayConfiguration(database, scripts, COMPATIBILITY_POLICY).load();
        List<String> violations = LegacyMissingMigrationGate.violations(flyway, allowlist);

        assertThat(violations).isNotEmpty();
        assertThat(violations).allSatisfy(violation -> assertThat(violation)
                .contains("版本 26 的历史身份与例外清单不一致"));
    }

    // ---------- 辅助 ----------

    private static FlywayMigrationStrategy productionStrategy() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(com.infrahub.web.config.FlywayMigrationPolicyConfig.class)) {
            return context.getBean(FlywayMigrationStrategy.class);
        }
    }

    private static void migrateFullChain(String database, Path scripts, LegacyMissingMigrationAllowlist allowlist) {
        seedBaselineOnly(database);
        Flyway flyway = flywayConfiguration(database, scripts, COMPATIBILITY_POLICY).load();
        LegacyMissingMigrationGate.verify(flyway, allowlist);
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(3);
        assertThat(appliedEntries(database))
                .containsExactly("2:BASELINE", "28:SQL", "29:SQL", "30:SQL");
    }

    private static Integer checksumOfVersion(Flyway flyway, String version) {
        List<MigrationInfo> matched = new ArrayList<>();
        for (MigrationInfo info : flyway.info().all()) {
            if (info.getVersion() != null && version.equals(info.getVersion().getVersion())) {
                matched.add(info);
            }
        }
        assertThat(matched).as("版本 " + version + " 应能在 info() 中定位到").hasSize(1);
        return matched.get(0).getChecksum();
    }
}
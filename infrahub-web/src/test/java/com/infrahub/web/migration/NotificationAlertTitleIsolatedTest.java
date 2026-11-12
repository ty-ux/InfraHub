package com.infrahub.web.migration;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.infrahub.release.entity.GovernanceAlertNotificationRecord;
import com.infrahub.release.enums.GovernanceAlertNotificationChannelType;
import com.infrahub.release.enums.GovernanceAlertNotificationStatus;
import com.infrahub.release.mapper.GovernanceAlertNotificationRecordMapper;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 通知表与实体契约的前向补齐回归（任务书四）。
 *
 * <p><b>缺陷本身</b>：{@code V28__create_governance_alert_notification_record.sql} 建表时没有
 * {@code alert_title}，而实体 {@link GovernanceAlertNotificationRecord#getAlertTitle()} 及其服务、
 * 以及 {@code GovernanceAlertFollowupReviewMapper} 里 {@code n.alert_title AS alertTitle} 的语句都在用它。
 * 在按迁移链建出来的库上，实体查询会直接报 {@code SQLSTATE 42703}。真实库与
 * {@code db/bootstrap/04-01-add-missing-tables.sql} 都已有该列，所以线上没暴露——这是原重建脚本
 * 携带的既有缺陷，<b>不是改编号引入的</b>。
 *
 * <p><b>为什么用新版本而不是改 V28</b>：V28/V29 已在共享真实库应用并记入 {@code flyway_schema_history}。
 * 改写已执行脚本会让校验和与历史不一致，只能靠 repair 掩盖，因此按迁移纪律改由
 * {@code V30__add_alert_title_to_governance_alert_notification_record.sql} 前向补齐。
 *
 * <p><b>本类证明什么（而不是"表存在"）</b>：
 * <ul>
 *   <li>baseline=2 起跑完整当前迁移链后，实体声明的<b>每一个字段</b>都能在表里找到同名列；</li>
 *   <li>缺口是真的：只迁到 V29 时，实体查询确实报 {@code alert_title} 不存在；</li>
 *   <li>已经手工有 {@code alert_title} 的表（真实库就是这种）也能兼容迁移：数据保留、
 *       不出现重复列、不新增约束、不改动其余列定义与索引；</li>
 *   <li>实体的查询与写入契约在真库上跑通（插入、按主键查、按告警记录分页查、取通知编号序列），
 *       而不是只看列在不在；</li>
 *   <li>再迁一次为 0。</li>
 * </ul>
 *
 * <p><b>本轮不改动已应用的 V28/V29，也不把 V30 应用到共享真实库</b>——那由库负责人执行。
 */
@DisplayName("通知表前向补齐：实体所需列、已存在列兼容、查询与写入契约")
class NotificationAlertTitleIsolatedTest extends MigrationIsolationSupport {

    private static final String TABLE = "release_governance_alert_notification_record";
    private static final String ALERT_TITLE_DEFINITION = "character varying|YES|512";
    private static final String ALERT_TITLE_COMMENT = "告警标题";

    @Test
    @DisplayName("完整迁移链后，实体声明的每个字段都有对应列；再迁一次为 0")
    void fullChainProducesEveryColumnTheEntityNeeds() {
        assumeAvailable();
        String database = createOwnedDatabase();
        Path scripts = copyVersionControlledScripts();
        seedBaselineOnly(database);

        Flyway flyway = flywayConfiguration(database, scripts, COMPATIBILITY_POLICY).load();
        // 正常路径必须不被闸门误伤：这一步同时也是"闸门放行正常启动"的证据
        LegacyMissingMigrationGate.verify(flyway, LegacyMissingMigrationAllowlist.load());

        MigrateResult result = flyway.migrate();
        assertThat(result.migrationsExecuted).as("V1/V2 在 baseline 之下，应执行 V28/V29/V30").isEqualTo(3);
        assertThat(appliedEntries(database)).containsExactly("2:BASELINE", "28:SQL", "29:SQL", "30:SQL");

        Map<String, String> columns = columnsOf(database, TABLE);
        assertThat(columns)
                .as("V28 建表 + V30 补列的最终形态应与实体的字段集一致（不是只看 alert_title 在不在）")
                .containsKeys(entityColumns().toArray(new String[0]));
        assertThat(entityColumns()).as("实体字段数用于交叉核对，防止上面的断言被空集合蒙混过关")
                .hasSize(18);
        assertThat(columns).hasSize(18);
        assertThat(columns.get("alert_title"))
                .as("补出来的列必须与真实库定义一致：可空 VARCHAR(512)")
                .isEqualTo(ALERT_TITLE_DEFINITION);
        assertThat(columns.get("notification_no")).as("既有 NOT NULL 约束不得被放宽").isEqualTo("character varying|NO|32");
        assertThat(columns.get("deleted")).isEqualTo("smallint|NO|-1");
        assertThat(columnComment(database, "alert_title")).isEqualTo(ALERT_TITLE_COMMENT);
        assertThat(uniqueConstraintCount(database)).isEqualTo("1");

        assertThat(flywayConfiguration(database, scripts, COMPATIBILITY_POLICY).load().migrate().migrationsExecuted)
                .as("再迁一次必须为 0")
                .isZero();
    }

    @Test
    @DisplayName("缺 alert_title 是真的：只迁到 V29 时实体查询报该列不存在")
    void missingAlertTitleActuallyBreaksTheEntityQuery() {
        assumeAvailable();
        String database = createOwnedDatabase();
        Path scripts = copyVersionControlledScripts();
        seedBaselineOnly(database);

        assertThat(migrateTo(database, scripts, "29").migrationsExecuted).isEqualTo(2);
        assertThat(columnsOf(database, TABLE)).doesNotContainKey("alert_title");

        try (SqlSession session = openSession(database)) {
            GovernanceAlertNotificationRecordMapper mapper =
                    session.getMapper(GovernanceAlertNotificationRecordMapper.class);
            // selectById 由 BaseMapper 生成，列清单来自实体：alert_title 一缺，语句本身就建不起来
            assertThatThrownBy(() -> mapper.selectById(1L))
                    .as("缺口若不存在，这条用例就会失败，说明本轮的补齐是多余的")
                    .hasMessageContaining("alert_title");
        }
    }

    @Test
    @DisplayName("已有 alert_title 的表兼容迁移：数据保留、不重复加列、不动其余结构")
    void tableThatAlreadyHasAlertTitleMigratesCompatibly() {
        assumeAvailable();
        String database = createOwnedDatabase();
        Path scripts = copyVersionControlledScripts();
        seedBaselineOnly(database);
        assertThat(migrateTo(database, scripts, "29").migrationsExecuted).isEqualTo(2);

        // 模拟真实库：该列早已存在（db/bootstrap/04-01-add-missing-tables.sql:316），且带注释
        execute(database, "ALTER TABLE " + TABLE + " ADD COLUMN alert_title VARCHAR(512)");
        execute(database, "COMMENT ON COLUMN " + TABLE + ".alert_title IS '" + ALERT_TITLE_COMMENT + "'");

        String alertTitle = "IT 告警标题 " + UUID.randomUUID();
        Long insertedId;
        try (SqlSession session = openSession(database)) {
            GovernanceAlertNotificationRecordMapper mapper =
                    session.getMapper(GovernanceAlertNotificationRecordMapper.class);
            GovernanceAlertNotificationRecord record = new GovernanceAlertNotificationRecord();
            record.setNotificationNo("IT-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
            record.setAlertRecordId(987654321L);
            record.setAlertTitle(alertTitle);
            record.setChannelType(GovernanceAlertNotificationChannelType.INTERNAL);
            record.setNotificationStatus(GovernanceAlertNotificationStatus.DRAFTED);
            record.setSubject("IT subject");
            assertThat(mapper.insert(record)).isEqualTo(1);
            session.commit();
            insertedId = record.getId();
        }
        assertThat(insertedId).as("自增/雪花主键应回填到实体").isNotNull();

        Map<String, String> columnsBefore = columnsOf(database, TABLE);
        List<String> indexesBefore = indexNames(database, TABLE);

        MigrateResult result = migrateTo(database, scripts, null);
        assertThat(result.migrationsExecuted).as("只应补跑 V30").isEqualTo(1);

        assertThat(columnsOf(database, TABLE))
                .as("V30 不得重塑表：列定义集合必须与迁移前逐项相同")
                .isEqualTo(columnsBefore);
        assertThat(columnsOf(database, TABLE).get("alert_title")).isEqualTo(ALERT_TITLE_DEFINITION);
        assertThat(indexNames(database, TABLE)).as("不得新增或删除索引").isEqualTo(indexesBefore);
        assertThat(uniqueConstraintCount(database)).isEqualTo("1");
        assertThat(singleValue(database, "SELECT alert_title FROM " + TABLE + " WHERE id = " + insertedId))
                .as("既有数据必须原样保留")
                .isEqualTo(alertTitle);
        assertThat(singleValue(database, "SELECT count(*) FROM " + TABLE + " WHERE alert_record_id = 987654321"))
                .isEqualTo("1");
        assertThat(columnComment(database, "alert_title")).isEqualTo(ALERT_TITLE_COMMENT);

        assertThat(migrateTo(database, scripts, null).migrationsExecuted).as("再迁一次必须为 0").isZero();
    }

    @Test
    @DisplayName("实体查询与写入契约在真库上跑通")
    void entityQueryAndWriteContractRoundTrips() {
        assumeAvailable();
        String database = createOwnedDatabase();
        Path scripts = copyVersionControlledScripts();
        seedBaselineOnly(database);
        assertThat(migrateTo(database, scripts, null).migrationsExecuted).isEqualTo(3);

        String alertTitle = "IT 契约标题 " + UUID.randomUUID();
        try (SqlSession session = openSession(database)) {
            GovernanceAlertNotificationRecordMapper mapper =
                    session.getMapper(GovernanceAlertNotificationRecordMapper.class);

            GovernanceAlertNotificationRecord record = new GovernanceAlertNotificationRecord();
            record.setNotificationNo("IT-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
            record.setAlertRecordId(123456789L);
            record.setAlertTitle(alertTitle);
            record.setChannelType(GovernanceAlertNotificationChannelType.EMAIL_DRAFT);
            record.setNotificationStatus(GovernanceAlertNotificationStatus.MANUALLY_CONFIRMED);
            record.setTargetUserId(777L);
            record.setSubject("IT 契约主题");
            assertThat(mapper.insert(record)).isEqualTo(1);
            session.commit();

            GovernanceAlertNotificationRecord reloaded = mapper.selectById(record.getId());
            assertThat(reloaded).isNotNull();
            assertThat(reloaded.getAlertTitle()).as("写入契约的核心：alertTitle 必须真的落到库里").isEqualTo(alertTitle);
            assertThat(reloaded.getAlertRecordId()).isEqualTo(123456789L);
            assertThat(reloaded.getChannelType()).isEqualTo(GovernanceAlertNotificationChannelType.EMAIL_DRAFT);
            assertThat(reloaded.getNotificationStatus())
                    .isEqualTo(GovernanceAlertNotificationStatus.MANUALLY_CONFIRMED);
            assertThat(reloaded.getDeleted()).as("deleted 默认值来自表定义").isZero();

            Page<GovernanceAlertNotificationRecord> byAlertRecord =
                    mapper.queryByAlertRecordId(new Page<>(1, 10), 123456789L);
            assertThat(byAlertRecord.getRecords())
                    .as("Mapper 里 SELECT * 的查询也要能把 alertTitle 映射出来")
                    .anySatisfy(row -> assertThat(row.getAlertTitle()).isEqualTo(alertTitle));
        }

        // 换 session 才观察得到序列前进：MyBatis 一级缓存按语句 id + 参数缓存查询结果，
        // 同一 session 内对同一句 nextval 的第二次调用会直接命中缓存、拿到同一个值。
        Long firstSequence = nextNotificationNo(database);
        Long secondSequence = nextNotificationNo(database);
        assertThat(firstSequence).as("V28 建的 seq_notification_record_no 必须是可用的").isNotNull().isPositive();
        assertThat(secondSequence).isGreaterThan(firstSequence);
    }

    // ---------- 辅助 ----------

    /** 实体声明字段 -> 期望的列名（下划线风格）。用反射而不是手抄，实体一变这里就会提醒。 */
    private static List<String> entityColumns() {
        List<String> columns = new ArrayList<>();
        for (Field field : GovernanceAlertNotificationRecord.class.getDeclaredFields()) {
            if (field.isSynthetic() || Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            columns.add(field.getName().replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT));
        }
        return columns;
    }

    private static MigrateResult migrateTo(String database, Path scripts, String target) {
        org.flywaydb.core.api.configuration.FluentConfiguration configuration =
                flywayConfiguration(database, scripts, COMPATIBILITY_POLICY);
        if (target != null) {
            configuration = configuration.target(target);
        }
        return configuration.load().migrate();
    }

    /** 在新的 session 里取一次通知编号序列，避免落到一级缓存上。 */
    private static Long nextNotificationNo(String database) {
        try (SqlSession session = openSession(database)) {
            return session.getMapper(GovernanceAlertNotificationRecordMapper.class).getNextNotificationNoSequence();
        }
    }

    private static SqlSession openSession(String database) {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(GovernanceAlertNotificationRecordMapper.class);
        // 与生产 infrahub-infra/.../MybatisPlusConfig 一致：分页查询依赖这个拦截器才会填 records
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor());
        configuration.addInterceptor(interceptor);
        configuration.setEnvironment(new Environment("postgres-it", new JdbcTransactionFactory(),
                dataSourceFor(database)));
        SqlSessionFactory factory = new MybatisSqlSessionFactoryBuilder().build(configuration);
        return factory.openSession();
    }

    private static String columnComment(String database, String column) {
        return singleValue(database, "SELECT col_description('" + TABLE + "'::regclass,"
                + " (SELECT ordinal_position FROM information_schema.columns"
                + "  WHERE table_schema = 'public' AND table_name = '" + TABLE + "'"
                + "    AND column_name = '" + column + "'))");
    }

    private static String uniqueConstraintCount(String database) {
        return singleValue(database, "SELECT count(*) FROM information_schema.table_constraints"
                + " WHERE table_schema = 'public' AND table_name = '" + TABLE + "'"
                + " AND constraint_type = 'UNIQUE'");
    }
}
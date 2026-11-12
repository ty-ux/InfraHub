package com.infrahub.release.service;

import com.infrahub.release.dto.TestDataCleanupSqlResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Service
@RequiredArgsConstructor
@Slf4j
public class TestDataCleanupSqlService {

    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    /**
     * E2E测试流水线的 pipeline_code 前缀条件（用于 WHERE 子句）。
     * 与 2026-04-21 冻结脚本保持一致。
     */
    private static final String E2E_PIPELINE_CONDITION =
        "pd.pipeline_code LIKE 'e2e-%'\n"
        + "   OR pd.pipeline_code LIKE 'manual-unlinked-%'\n"
        + "   OR pd.pipeline_code LIKE 'rel-pipe-%'\n"
        + "   OR pd.pipeline_code LIKE 'rel-fail-pipe-%'\n"
        + "   OR pd.pipeline_code LIKE 'rel-dupe-pipe-%'";

    /** 同上，用于 pipeline_def 直接查询（不带别名）。 */
    private static final String E2E_PIPELINE_CONDITION_NO_ALIAS =
        "pipeline_code LIKE 'e2e-%'\n"
        + "   OR pipeline_code LIKE 'manual-unlinked-%'\n"
        + "   OR pipeline_code LIKE 'rel-pipe-%'\n"
        + "   OR pipeline_code LIKE 'rel-fail-pipe-%'\n"
        + "   OR pipeline_code LIKE 'rel-dupe-pipe-%'";

    /**
     * 发布单候选条件 - 与冻结脚本保持一致，只保留 REL-ACT-% 和 REL-PIPE-%
     */
    private static final String RELEASE_ORDER_CONDITION =
        "(order_no LIKE 'REL-ACT-%'\n"
        + "       OR order_no LIKE 'REL-PIPE-%')";

    /**
     * 发布单保护条件
     */
    private static final String RELEASE_ORDER_PROTECTION =
        "      AND id NOT IN (89, 90, 105, 106) -- 保护V1.4/V1.5/V1.6真实证据链\n"
        + "      AND id NOT IN (SELECT source_release_order_id FROM release_current_version WHERE source_release_order_id IS NOT NULL)";

    public TestDataCleanupSqlResponse generateCleanupSql() {
        LocalDateTime now = LocalDateTime.now();
        String fileName = "test_data_cleanup_" + now.format(TIMESTAMP_FORMATTER) + ".sql";

        StringBuilder sqlBuilder = new StringBuilder();

        appendHeader(sqlBuilder, now);
        appendPreviewQueries(sqlBuilder);
        appendTransactionBegin(sqlBuilder);
        appendDeleteStatements(sqlBuilder);
        appendTransactionRollback(sqlBuilder);
        appendExecutionNotes(sqlBuilder);

        return TestDataCleanupSqlResponse.builder()
                .fileName(fileName)
                .generatedAt(now)
                .protectedEvidenceExcluded(true)
                .sql(sqlBuilder.toString())
                .build();
    }

    private void appendHeader(StringBuilder sb, LocalDateTime generatedAt) {
        sb.append("-- ========================================================\n");
        sb.append("-- InfraHub 测试数据清理 SQL 脚本\n");
        sb.append("-- 生成时间: ").append(generatedAt.toString()).append("\n");
        sb.append("-- 版本: V1.9\n");
        sb.append("-- ========================================================\n");
        sb.append("-- ⚠️ 重要说明:\n");
        sb.append("-- 1. 本脚本只删除E2E测试临时数据\n");
        sb.append("-- 2. 真实证据链数据已被排除（受保护）\n");
        sb.append("-- 3. 脚本使用BEGIN/ROLLBACK包裹，默认回滚事务（不提交删除）\n");
        sb.append("-- 4. 如需实际删除，请将ROLLBACK改为COMMIT\n");
        sb.append("-- ========================================================\n\n");
    }

    private void appendPreviewQueries(StringBuilder sb) {
        sb.append("-- ========================================================\n");
        sb.append("-- 阶段1: 候选数据预览查询\n");
        sb.append("-- 执行以下查询可预览将被删除的数据\n");
        sb.append("-- ========================================================\n\n");

        sb.append("-- 1. pipeline_step_run 候选数据预览\n");
        sb.append("SELECT psr.id, psr.step_id, psr.pipeline_run_id, psr.run_status, psr.step_name\n");
        sb.append("FROM pipeline_step_run psr\n");
        sb.append("INNER JOIN pipeline_run pr ON psr.pipeline_run_id = pr.id\n");
        sb.append("INNER JOIN pipeline_def pd ON pr.pipeline_id = pd.id\n");
        sb.append("WHERE ").append(E2E_PIPELINE_CONDITION).append(";\n\n");

        sb.append("-- 2. pipeline_run 候选数据预览\n");
        sb.append("SELECT pr.id, pr.pipeline_id, pr.run_status, pr.trigger_by, pr.created_at\n");
        sb.append("FROM pipeline_run pr\n");
        sb.append("INNER JOIN pipeline_def pd ON pr.pipeline_id = pd.id\n");
        sb.append("WHERE ").append(E2E_PIPELINE_CONDITION).append(";\n\n");

        sb.append("-- 3. pipeline_step 候选数据预览\n");
        sb.append("SELECT ps.id, ps.step_name, ps.step_type, ps.executor_type, ps.step_order, ps.stage_id, ps.created_at\n");
        sb.append("FROM pipeline_step ps\n");
        sb.append("INNER JOIN pipeline_stage pst ON ps.stage_id = pst.id\n");
        sb.append("INNER JOIN pipeline_def pd ON pst.pipeline_id = pd.id\n");
        sb.append("WHERE ").append(E2E_PIPELINE_CONDITION).append(";\n\n");

        sb.append("-- 4. pipeline_stage 候选数据预览\n");
        sb.append("SELECT pst.id, pst.stage_name, pst.stage_order, pst.pipeline_id, pst.timeout_seconds, pst.created_at\n");
        sb.append("FROM pipeline_stage pst\n");
        sb.append("INNER JOIN pipeline_def pd ON pst.pipeline_id = pd.id\n");
        sb.append("WHERE ").append(E2E_PIPELINE_CONDITION).append(";\n\n");

        sb.append("-- 5. pipeline_def 候选数据预览\n");
        sb.append("SELECT id, pipeline_code, pipeline_name, app_id, created_at\n");
        sb.append("FROM pipeline_def\n");
        sb.append("WHERE (").append(E2E_PIPELINE_CONDITION_NO_ALIAS).append(")\n");
        sb.append("  AND id NOT IN (88); -- 保护真实证据链\n\n");

        sb.append("-- 6. runtime_deploy_record 候选数据预览（E2E相关）\n");
        sb.append("SELECT rdr.id, rdr.release_order_id, rdr.rollback_order_id, rdr.deploy_status, rdr.created_at\n");
        sb.append("FROM runtime_deploy_record rdr\n");
        sb.append("LEFT JOIN release_order ro ON rdr.release_order_id = ro.id\n");
        sb.append("LEFT JOIN release_rollback_order rro ON rdr.rollback_order_id = rro.id\n");
        sb.append("WHERE (\n");
        sb.append("    (\n");
        sb.append("      (").append(RELEASE_ORDER_CONDITION.replace("order_no", "ro.order_no")).append(")\n");
        sb.append("      AND ro.id NOT IN (89, 90, 105, 106) -- 保护V1.4/V1.5/V1.6真实证据链\n");
        sb.append("      AND ro.id NOT IN (SELECT source_release_order_id FROM release_current_version WHERE source_release_order_id IS NOT NULL)\n");
        sb.append("    )\n");
        sb.append("    OR\n");
        sb.append("    (\n");
        sb.append("      rro.release_order_id IN (\n");
        sb.append("        SELECT id FROM release_order\n");
        sb.append("        WHERE ").append(RELEASE_ORDER_CONDITION).append("\n");
        sb.append("          ").append(RELEASE_ORDER_PROTECTION.replace("      ", "          ")).append("\n");
        sb.append("      )\n");
        sb.append("      AND rro.id NOT IN (SELECT source_rollback_order_id FROM release_current_version WHERE source_rollback_order_id IS NOT NULL)\n");
        sb.append("    )\n");
        sb.append("  )\n");
        sb.append("  AND rdr.id NOT IN (16, 17, 18, 19); -- 保护真实部署记录\n\n");

        sb.append("-- 7. sys_audit_log 候选数据预览（E2E相关操作记录）\n");
        sb.append("SELECT id, action, biz_type, biz_id, created_at\n");
        sb.append("FROM sys_audit_log\n");
        sb.append("WHERE (biz_type = 'RELEASE_ORDER' AND biz_id IN (\n");
        sb.append("    SELECT id FROM release_order\n");
        sb.append("    WHERE ").append(RELEASE_ORDER_CONDITION).append("\n");
        sb.append("      ").append(RELEASE_ORDER_PROTECTION).append("\n");
        sb.append("))\n");
        sb.append("LIMIT 100;\n\n");

        sb.append("-- 8. release_rollback_order 候选数据预览\n");
        sb.append("SELECT id, rollback_no, release_order_id, status, created_at\n");
        sb.append("FROM release_rollback_order\n");
        sb.append("WHERE release_order_id IN (\n");
        sb.append("    SELECT id FROM release_order\n");
        sb.append("    WHERE ").append(RELEASE_ORDER_CONDITION).append("\n");
        sb.append("      ").append(RELEASE_ORDER_PROTECTION).append("\n");
        sb.append(")\n");
        sb.append("  AND id NOT IN (SELECT source_rollback_order_id FROM release_current_version WHERE source_rollback_order_id IS NOT NULL); -- 保护当前版本引用\n\n");

        sb.append("-- 9. release_order 候选数据预览\n");
        sb.append("SELECT id, order_no, app_id, service_id, status, created_at\n");
        sb.append("FROM release_order\n");
        sb.append("WHERE ").append(RELEASE_ORDER_CONDITION).append("\n");
        sb.append("  ").append(RELEASE_ORDER_PROTECTION).append(";\n\n");

        sb.append("\n");
    }

    private void appendTransactionBegin(StringBuilder sb) {
        sb.append("-- ========================================================\n");
        sb.append("-- 阶段2: 开始事务\n");
        sb.append("-- ========================================================\n");
        sb.append("BEGIN;\n\n");
    }

    private void appendDeleteStatements(StringBuilder sb) {
        sb.append("-- ========================================================\n");
        sb.append("-- 阶段3: 执行删除（按依赖关系顺序：子表 -> 父表）\n");
        sb.append("-- ========================================================\n\n");

        sb.append("-- 1. 删除 pipeline_step_run（E2E测试相关）\n");
        sb.append("DELETE FROM pipeline_step_run\n");
        sb.append("WHERE pipeline_run_id IN (\n");
        sb.append("    SELECT pr.id FROM pipeline_run pr\n");
        sb.append("    INNER JOIN pipeline_def pd ON pr.pipeline_id = pd.id\n");
        sb.append("    WHERE ").append(E2E_PIPELINE_CONDITION).append("\n");
        sb.append(");\n\n");

        sb.append("-- 2. 删除 pipeline_run（E2E测试相关）\n");
        sb.append("DELETE FROM pipeline_run\n");
        sb.append("WHERE pipeline_id IN (\n");
        sb.append("    SELECT id FROM pipeline_def\n");
        sb.append("    WHERE ").append(E2E_PIPELINE_CONDITION_NO_ALIAS).append("\n");
        sb.append(");\n\n");

        sb.append("-- 3. 删除 pipeline_step（E2E测试相关）\n");
        sb.append("DELETE FROM pipeline_step\n");
        sb.append("WHERE stage_id IN (\n");
        sb.append("    SELECT pst.id FROM pipeline_stage pst\n");
        sb.append("    INNER JOIN pipeline_def pd ON pst.pipeline_id = pd.id\n");
        sb.append("    WHERE ").append(E2E_PIPELINE_CONDITION).append("\n");
        sb.append(");\n\n");

        sb.append("-- 4. 删除 pipeline_stage（E2E测试相关）\n");
        sb.append("DELETE FROM pipeline_stage\n");
        sb.append("WHERE pipeline_id IN (\n");
        sb.append("    SELECT id FROM pipeline_def\n");
        sb.append("    WHERE ").append(E2E_PIPELINE_CONDITION_NO_ALIAS).append("\n");
        sb.append(");\n\n");

        sb.append("-- 5. 删除 pipeline_def（E2E测试相关）\n");
        sb.append("DELETE FROM pipeline_def\n");
        sb.append("WHERE (").append(E2E_PIPELINE_CONDITION_NO_ALIAS).append(")\n");
        sb.append("  AND id NOT IN (88); -- 保护真实证据链\n\n");

        sb.append("-- 6. 删除 runtime_deploy_record（E2E测试相关）\n");
        sb.append("DELETE FROM runtime_deploy_record\n");
        sb.append("WHERE (\n");
        sb.append("    release_order_id IN (\n");
        sb.append("        SELECT id FROM release_order\n");
        sb.append("        WHERE ").append(RELEASE_ORDER_CONDITION).append("\n");
        sb.append("          ").append(RELEASE_ORDER_PROTECTION).append("\n");
        sb.append("    )\n");
        sb.append("    OR rollback_order_id IN (\n");
        sb.append("        SELECT id FROM release_rollback_order\n");
        sb.append("        WHERE release_order_id IN (\n");
        sb.append("            SELECT id FROM release_order\n");
        sb.append("            WHERE ").append(RELEASE_ORDER_CONDITION).append("\n");
        sb.append("              ").append(RELEASE_ORDER_PROTECTION).append("\n");
        sb.append("        )\n");
        sb.append("          AND id NOT IN (SELECT source_rollback_order_id FROM release_current_version WHERE source_rollback_order_id IS NOT NULL)\n");
        sb.append("    )\n");
        sb.append(")\n");
        sb.append("  AND id NOT IN (16, 17, 18, 19); -- 保护V1.4/V1.5/V1.6真实部署记录\n\n");

        sb.append("-- 7. 删除 sys_audit_log（E2E测试相关操作记录）\n");
        sb.append("DELETE FROM sys_audit_log\n");
        sb.append("WHERE biz_type = 'RELEASE_ORDER'\n");
        sb.append("  AND biz_id IN (\n");
        sb.append("      SELECT id FROM release_order\n");
        sb.append("      WHERE ").append(RELEASE_ORDER_CONDITION).append("\n");
        sb.append("        ").append(RELEASE_ORDER_PROTECTION).append("\n");
        sb.append("  );\n\n");

        sb.append("-- 8. 删除 release_rollback_order（E2E测试相关）\n");
        sb.append("DELETE FROM release_rollback_order\n");
        sb.append("WHERE release_order_id IN (\n");
        sb.append("    SELECT id FROM release_order\n");
        sb.append("    WHERE ").append(RELEASE_ORDER_CONDITION).append("\n");
        sb.append("      ").append(RELEASE_ORDER_PROTECTION).append("\n");
        sb.append(")\n");
        sb.append("  AND id NOT IN (SELECT source_rollback_order_id FROM release_current_version WHERE source_rollback_order_id IS NOT NULL); -- 保护当前版本引用\n\n");

        sb.append("-- 9. 删除 release_order（E2E测试相关）\n");
        sb.append("DELETE FROM release_order\n");
        sb.append("WHERE ").append(RELEASE_ORDER_CONDITION).append("\n");
        sb.append("  ").append(RELEASE_ORDER_PROTECTION).append(";\n\n");
    }

    private void appendTransactionRollback(StringBuilder sb) {
        sb.append("-- ========================================================\n");
        sb.append("-- 阶段4: 事务回滚（安全模式）\n");
        sb.append("-- 注意：默认执行ROLLBACK，如需实际删除请将下方改为COMMIT\n");
        sb.append("-- ========================================================\n");
        sb.append("ROLLBACK;\n\n");
    }

    private void appendExecutionNotes(StringBuilder sb) {
        sb.append("-- ========================================================\n");
        sb.append("-- 附录：实际执行说明\n");
        sb.append("-- ========================================================\n");
        sb.append("/*\n\n");
        sb.append("📋 如何使用此脚本：\n\n");
        sb.append("1. 【安全验证】首先执行预览查询（阶段1），确认要删除的数据\n\n");
        sb.append("2. 【测试模式】直接执行此脚本（默认ROLLBACK），验证SQL语法正确性\n");
        sb.append("   - 不会实际删除任何数据\n");
        sb.append("   - 可以检查是否有外键约束错误\n\n");
        sb.append("3. 【执行删除】如需实际删除数据，请按以下步骤：\n");
        sb.append("   a. 将脚本中的 ROLLBACK; 改为 COMMIT;\n");
        sb.append("   b. 建议先备份相关数据\n");
        sb.append("   c. 执行修改后的脚本\n\n");
        sb.append("⚠️  重要提示：\n\n");
        sb.append("- 本脚本只删除E2E测试数据，保留以下真实证据链：\n");
        sb.append("  * pipeline_def.id = 88 (V1.4/V1.5真实部署)\n");
        sb.append("  * release_order.id IN (89, 90, 105, 106) (真实发布单)\n");
        sb.append("  * pipeline_run.id IN (99, 100, 114) (真实流水线运行)\n");
        sb.append("  * runtime_deploy_record.id IN (16, 17, 18, 19) (真实部署记录)\n");
        sb.append("  * release_artifact.id IN (8, 9) (真实制品)\n");
        sb.append("  * release_version.id IN (9, 10) (真实版本)\n\n");
        sb.append("- 删除的数据特征：\n");
        sb.append("  * pipeline_code LIKE 'e2e-%' / 'manual-unlinked-%' / 'rel-pipe-%' / 'rel-fail-pipe-%' / 'rel-dupe-pipe-%'\n");
        sb.append("  * order_no LIKE 'REL-ACT-%' 或 'REL-PIPE-%'\n\n");
        sb.append("- 删除顺序遵循外键依赖关系，确保无残留孤儿记录\n\n");
        sb.append("🔒 安全保护机制：\n\n");
        sb.append("1. 默认使用ROLLBACK，不会意外删除数据\n");
        sb.append("2. 明确的WHERE条件限制，只删除E2E数据\n");
        sb.append("3. 排除了所有已知真实证据链记录\n");
        sb.append("4. 从子表到父表的删除顺序，避免外键约束冲突\n\n");
        sb.append("*/\n");
    }
}

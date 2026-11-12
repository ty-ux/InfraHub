package com.infrahub.web.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.ErrorCode;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.output.ValidateOutput;
import org.flywaydb.core.api.output.ValidateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 迁移历史一致性闸门：在 {@code flyway.migrate()} <b>之前</b>判定，不通过就拒绝启动。
 *
 * <p>它补的是 {@code ignore-migration-patterns="*:missing"} 留下的洞。那个配置是类型/状态通配，
 * 没有版本区间，因此"允许缺失"的范围是"任何本地解析不到的已应用迁移"。本闸门把它收窄成
 * {@link LegacyMissingMigrationAllowlist} 里冻结的、逐条核实过的历史身份。
 *
 * <p><b>三件互不等价的事，这里只做其中两件</b>：
 * <ol>
 *   <li>已应用脚本的校验和一致性 —— 靠 {@code validateWithResult()} 的
 *       {@code CHECKSUM_MISMATCH}，本闸门会放行 pending、拒绝它；</li>
 *   <li>缺失迁移例外 —— 靠清单白名单逐字段比对版本/类型/脚本/校验和；</li>
 *   <li>表结构是否满足实体、打包脚本是否与源文件逐字节一致 —— <b>不在这里</b>，
 *       分别由 {@code NotificationAlertTitleIsolatedTest} 与
 *       {@code FlywayIsolatedPostgresTest#packagedScriptsAreByteIdenticalToVersionControlledSources} 覆盖。</li>
 * </ol>
 *
 * <p><b>不能只靠 {@code info()} 状态判断篡改</b>：本地脚本被改动后，{@code info()} 仍然报数据库里的旧
 * 校验和（实测 V29 被篡改后仍报 -388601075），所以第 1 件必须走 validate。
 *
 * <p><b>不能裸用 {@code validate()}</b>：在库里还有待执行迁移时，validate 会把 pending 报成错误
 * （实测 config 为 {@code *:missing} 时，待执行的 28/29 各报一条 {@code RESOLVED_VERSIONED_MIGRATION_NOT_APPLIED}）。
 * 因此只容忍该错误码，其余一律拒绝。
 */
public final class LegacyMissingMigrationGate {

    private static final Logger LOGGER = LoggerFactory.getLogger(LegacyMissingMigrationGate.class);

    /** 本仓库重建迁移线的起始版本。该版本的迁移必须始终能在本地解析，绝不允许出现在例外清单里。 */
    public static final int NEW_LINE_FIRST_VERSION = 28;

    /**
     * "已在库中应用、但本地找不到脚本"的迁移状态集合。
     *
     * <p>用显式枚举而不是 {@code !isResolved() && isApplied()}：{@code MigrationState} 没有
     * {@code isMissing()}，而 BASELINE 行在那种谓词下的归属是含糊的，显式集合才能保证
     * baseline 锚点不被卷进来。
     */
    private static final Set<MigrationState> APPLIED_WITHOUT_LOCAL_SCRIPT =
            EnumSet.of(MigrationState.MISSING_SUCCESS, MigrationState.MISSING_FAILED,
                    MigrationState.FUTURE_SUCCESS, MigrationState.FUTURE_FAILED);

    /** 待执行迁移在 validate 中报出的错误码；这是正常启动路径的一部分，必须放行。 */
    public static final ErrorCode PENDING_NOT_APPLIED = ErrorCode.RESOLVED_VERSIONED_MIGRATION_NOT_APPLIED;

    private static final Set<ErrorCode> TOLERATED_VALIDATION_ERRORS = Set.of(PENDING_NOT_APPLIED);

    private LegacyMissingMigrationGate() {
    }

    /** 判定并在不通过时抛错。抛错发生在迁移之前，库不会被改动。 */
    public static void verify(Flyway flyway) {
        verify(flyway, LegacyMissingMigrationAllowlist.load());
    }

    /** 判定并在不通过时抛错；清单由调用方提供，供测试注入受损清单。 */
    public static void verify(Flyway flyway, LegacyMissingMigrationAllowlist allowlist) {
        List<String> violations = new ArrayList<>(allowlistCreep(allowlist));
        // 清单完整性是最便宜且确定性的判定，先做完再碰数据库：清单被塞进本仓库自有迁移线的版本时，
        // 即使数据库不可达也必须拒绝，而不是先抛连接错误。
        if (violations.isEmpty()) {
            violations.addAll(unresolvableAppliedHistory(flyway, allowlist));
            violations.addAll(unexplainedValidationFailures(flyway));
        }
        if (!violations.isEmpty()) {
            throw new FlywayException(failureMessage(violations));
        }
        LOGGER.info("[flyway-policy] gate passed: allowlistEntries={} newLineFirstVersion={}",
                allowlist.entries().size(), NEW_LINE_FIRST_VERSION);
    }

    /**
     * 收集全部违规项，空列表表示通过。测试用它断言具体原因，无需依赖异常文本。
     *
     * <p>只读：{@code info()} 与 {@code validateWithResult()} 都不改动数据库。
     */
    public static List<String> violations(Flyway flyway, LegacyMissingMigrationAllowlist allowlist) {
        List<String> violations = new ArrayList<>();
        violations.addAll(allowlistCreep(allowlist));
        violations.addAll(unresolvableAppliedHistory(flyway, allowlist));
        violations.addAll(unexplainedValidationFailures(flyway));
        return List.copyOf(violations);
    }

    /**
     * 例外清单自身不得包含本仓库自有迁移线的版本，否则"删掉 V28+ 再改清单"就能绕过闸门。
     *
     * <p>包内可见是为了让回归能在<b>没有数据库</b>的情况下直接断言这一项，不必为了测一个
     * 纯清单判定而去连库。
     */
    static List<String> allowlistCreep(LegacyMissingMigrationAllowlist allowlist) {
        List<String> violations = new ArrayList<>();
        for (LegacyMissingMigrationAllowlist.Identity offender : allowlist.atOrAbove(NEW_LINE_FIRST_VERSION)) {
            violations.add("例外清单声明了本仓库自有迁移线的版本 " + offender.version()
                    + "（>= " + NEW_LINE_FIRST_VERSION + "）：" + offender.describe()
                    + "。该版本必须保留可解析的本地脚本，不允许作为兼容例外。");
        }
        return violations;
    }

    /** 已在库中应用却解析不到本地脚本的历史，必须逐字段等于例外清单里的某一条。 */
    private static List<String> unresolvableAppliedHistory(Flyway flyway, LegacyMissingMigrationAllowlist allowlist) {
        List<String> violations = new ArrayList<>();
        for (MigrationInfo info : flyway.info().all()) {
            MigrationState state = info.getState();
            if (!APPLIED_WITHOUT_LOCAL_SCRIPT.contains(state)) {
                continue;
            }
            String version = info.getVersion() == null ? null : info.getVersion().getVersion();
            if (version == null) {
                violations.add("库中存在已应用但无本地脚本的迁移，且没有版本号可供比对：" + state
                        + " " + LegacyMissingMigrationAllowlist.Identity.describe(info));
                continue;
            }
            LegacyMissingMigrationAllowlist.Identity allowed = allowlist.find(version);
            if (allowed == null) {
                violations.add("版本 " + version + " 已在库中应用（状态 " + state + "）但本地没有脚本，且不在例外清单内：实际 "
                        + LegacyMissingMigrationAllowlist.Identity.describe(info)
                        + "。本仓库自有迁移线的脚本必须存在，旧迁移线缺失必须逐条登记。");
            } else if (!allowed.matches(info)) {
                violations.add("版本 " + version + " 的历史身份与例外清单不一致：期望 " + allowed.describe()
                        + "，实际 " + LegacyMissingMigrationAllowlist.Identity.describe(info));
            }
        }
        return violations;
    }

    /** 校验失败项里，除"待执行"以外的一切都必须拒绝——包括校验和不一致与未知错误码。 */
    private static List<String> unexplainedValidationFailures(Flyway flyway) {
        ValidateResult result = flyway.validateWithResult();
        List<String> violations = new ArrayList<>();
        for (ValidateOutput output : result.invalidMigrations) {
            ErrorCode code = output.errorDetails == null ? null : output.errorDetails.errorCode;
            String message = output.errorDetails == null ? null : output.errorDetails.errorMessage;
            if (code != null && TOLERATED_VALIDATION_ERRORS.contains(code)) {
                continue;
            }
            violations.add("迁移校验未通过且不属于兼容例外：version=" + output.version
                    + " errorCode=" + (code == null ? "<none>" : code)
                    + " filepath=" + output.filepath
                    + " message=" + (message == null ? "<none>" : message));
        }
        if (!result.validationSuccessful && result.invalidMigrations.isEmpty()) {
            violations.add("迁移校验整体失败但没有逐条明细（可能连脚本都无法解析）："
                    + result.getAllErrorMessages().replace('\n', '|'));
        }
        return violations;
    }

    private static String failureMessage(List<String> violations) {
        StringBuilder message = new StringBuilder();
        message.append("Flyway 迁移历史一致性闸门未通过，已拒绝在迁移前继续（数据库未被改动）。共 ")
                .append(violations.size()).append(" 项：");
        for (int index = 0; index < violations.size(); index++) {
            message.append("\n  ").append(index + 1).append(") ").append(violations.get(index));
        }
        message.append("\n处理方式：恢复本仓库自有迁移线的脚本；旧迁移线缺失只能逐条登记到 ")
                .append(LegacyMissingMigrationAllowlist.REPOSITORY_LOCATION)
                .append(" 并人工复核。不要用 repair 或改写 flyway_schema_history 掩盖。");
        return message.toString();
    }
}
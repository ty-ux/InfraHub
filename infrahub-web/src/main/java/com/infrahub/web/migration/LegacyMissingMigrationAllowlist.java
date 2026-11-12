package com.infrahub.web.migration;

import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationInfo;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 冻结的"已应用但本地没有脚本"历史清单。
 *
 * <p><b>它解决什么问题</b>：共享真实库由另一条迁移线建成，占用了 v2(baseline)..v27，脚本不在本仓库、
 * 也无法从仓库历史恢复。本仓库因此从 V28 起编号，并用
 * {@code spring.flyway.ignore-migration-patterns="*:missing"} 让这段旧历史通过校验。
 * 但那个配置是"类型/状态通配"，<b>没有版本区间</b>——它同样会放过将来被误删的 V28+ 脚本。
 * 本清单把"允许缺失"的集合从"任何匹配的缺失"收窄为"下面这些已核实过的身份"。
 *
 * <p><b>不得自动生成</b>：条目是人工从 {@code flyway_schema_history} 的只读查询里逐字抄下来的
 * （版本、类型、脚本名、校验和），并逐条复核。任何"拿当前库的历史自动回填清单"的做法都会让闸门
 * 退化成它本该防住的东西。
 *
 * <p>清单文件在仓库里是 {@value #REPOSITORY_LOCATION}，由 {@code infrahub-web/pom.xml}
 * 构建期映射到 classpath 的 {@value #CLASSPATH_LOCATION}。文件内容保持纯 ASCII，
 * 避免任何字符集解释差异影响判定。
 */
public final class LegacyMissingMigrationAllowlist {

    /** 闸门运行时读取的位置（由 pom 的 &lt;resources&gt; 映射而来）。 */
    public static final String CLASSPATH_LOCATION = "db/migration-policy/legacy-missing-migration-allowlist.txt";

    /** 清单在仓库里的单一来源，供文档与测试引用。 */
    public static final String REPOSITORY_LOCATION = "db/flyway/legacy-missing-migration-allowlist.txt";

    private final Map<String, Identity> identitiesByVersion;

    private LegacyMissingMigrationAllowlist(Map<String, Identity> identitiesByVersion) {
        // 必须保留文件顺序：{@link #entries()} 的契约是"按文件出现顺序"，而 Map.copyOf 的迭代顺序未定义
        // （实测会输出 23,22,21,… 的降序），审查方就没法拿 entries() 与清单文件逐行对照。
        this.identitiesByVersion = Collections.unmodifiableMap(new LinkedHashMap<>(identitiesByVersion));
    }

    /** 从 classpath 读取清单；读不到就抛错——闸门缺少依据时必须拒绝启动，而不是当作"没有例外"。 */
    public static LegacyMissingMigrationAllowlist load() {
        try (InputStream input = LegacyMissingMigrationAllowlist.class.getClassLoader()
                .getResourceAsStream(CLASSPATH_LOCATION)) {
            if (input == null) {
                throw new FlywayException("classpath 上找不到缺失迁移例外清单 " + CLASSPATH_LOCATION
                        + "（仓库单一来源：" + REPOSITORY_LOCATION + "）");
            }
            return parse(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new UncheckedIOException("读取缺失迁移例外清单失败", exception);
        }
    }

    /**
     * 解析清单文本。格式：{@code #} 开头是注释，空行忽略，其余每行 {@code version|type|script|checksum}。
     * 解析失败即抛错，不做任何"尽力而为"的容错：清单损坏必须表现为启动被拒绝。
     */
    public static LegacyMissingMigrationAllowlist parse(String content) {
        Map<String, Identity> identities = new LinkedHashMap<>();
        int lineNumber = 0;
        for (String rawLine : content.split("\n", -1)) {
            lineNumber++;
            String line = rawLine.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] parts = line.split("\\|", -1);
            if (parts.length != 4) {
                throw new FlywayException("缺失迁移例外清单第 " + lineNumber + " 行不是 version|type|script|checksum 四段："
                        + line);
            }
            Identity identity = new Identity(parts[0].strip(), parts[1].strip(), parts[2].strip(),
                    "null".equals(parts[3].strip()) ? null : parseChecksum(parts[3].strip(), lineNumber));
            if (identities.put(identity.version(), identity) != null) {
                throw new FlywayException("缺失迁移例外清单第 " + lineNumber + " 行重复声明版本 " + identity.version());
            }
        }
        if (identities.isEmpty()) {
            throw new FlywayException("缺失迁移例外清单为空：空清单无法表达任何例外，应直接去掉兼容策略");
        }
        return new LegacyMissingMigrationAllowlist(identities);
    }

    private static Integer parseChecksum(String raw, int lineNumber) {
        try {
            return Integer.valueOf(raw);
        } catch (NumberFormatException exception) {
            throw new FlywayException("缺失迁移例外清单第 " + lineNumber + " 行的 checksum 不是 32 位整数：" + raw);
        }
    }

    /** 清单里的全部条目，按文件出现顺序。 */
    public List<Identity> entries() {
        return List.copyOf(identitiesByVersion.values());
    }

    /** 按版本查条目；版本不在清单内返回 {@code null}。 */
    public Identity find(String version) {
        return identitiesByVersion.get(version);
    }

    /** 版本号大于等于给定下限的条目——用于拦截"把本仓库自己的迁移写进例外清单"。 */
    public List<Identity> atOrAbove(int versionFloor) {
        List<Identity> offenders = new ArrayList<>();
        for (Identity identity : identitiesByVersion.values()) {
            try {
                if (Integer.parseInt(identity.version()) >= versionFloor) {
                    offenders.add(identity);
                }
            } catch (NumberFormatException exception) {
                // 版本不是整数：无法证明它低于下限，按越界处理（fail-closed）。
                offenders.add(identity);
            }
        }
        return List.copyOf(offenders);
    }

    /** 一条历史迁移的身份：版本 + 类型 + 脚本标识 + 历史校验和。 */
    public record Identity(String version, String type, String script, Integer checksum) {

        /** 与 Flyway 报出的某条迁移记录逐字段比对，用于识破"同名不同内容"的历史被篡改。 */
        public boolean matches(MigrationInfo info) {
            return version.equals(versionOf(info))
                    && type.equals(String.valueOf(info.getType()))
                    && script.equals(String.valueOf(info.getScript()))
                    && Objects.equals(checksum, info.getChecksum());
        }

        /** 人类可读的比对依据，用于失败信息。 */
        public String describe() {
            return version + "|" + type + "|" + script + "|" + (checksum == null ? "null" : checksum);
        }

        /** 把一条数据库迁移记录描述成同样的格式，便于把"期望"和"实际"并排打印。 */
        public static String describe(MigrationInfo info) {
            return versionOf(info) + "|" + info.getType() + "|" + info.getScript() + "|"
                    + (info.getChecksum() == null ? "null" : info.getChecksum());
        }

        private static String versionOf(MigrationInfo info) {
            return info.getVersion() == null ? "null" : info.getVersion().getVersion();
        }
    }
}
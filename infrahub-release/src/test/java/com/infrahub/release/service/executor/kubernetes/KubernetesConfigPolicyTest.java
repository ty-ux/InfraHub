package com.infrahub.release.service.executor.kubernetes;

import com.infrahub.release.dto.KubernetesExecutionConfig;
import com.infrahub.release.service.executor.kubernetes.KubernetesConfigPolicy.Field;
import com.infrahub.release.service.executor.kubernetes.KubernetesConfigPolicy.Issue;
import com.infrahub.release.service.executor.kubernetes.KubernetesConfigPolicy.Reason;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pure configuration policy.
 *
 * <p>Every case here passes or fails on the payload text alone: no file is created, none is read, and
 * no host is contacted. A payload naming a path that does not exist is accepted - the executor reads
 * the file per release - while a payload naming a path that is not absolute is refused.</p>
 */
class KubernetesConfigPolicyTest {

    /** An absolute path as this host recognizes one, without requiring it to exist. */
    private static String hostAbsolute(String relative) {
        return Path.of(System.getProperty("user.dir")).resolve(relative).toString();
    }

    private static String validPayload() {
        return """
                {
                  "kubeconfigPath": "%s",
                  "kubeContext": "bound-context",
                  "namespace": "prod",
                  "manifestPath": "%s"
                }
                """.formatted(
                hostAbsolute("configs/target.kubeconfig").replace("\\", "\\\\"),
                hostAbsolute("manifests/app.yaml").replace("\\", "\\\\"));
    }

    private static boolean hasIssue(List<Issue> issues, Field field, Reason reason) {
        return issues.contains(new Issue(field, reason));
    }

    @Test
    @DisplayName("完整配置（manifestPath）→ 无不通过项")
    void completePayloadWithManifestPathIsAccepted() {
        assertTrue(KubernetesConfigPolicy.payloadIssues(validPayload()).isEmpty());
    }

    @Test
    @DisplayName("完整配置（manifestContent）→ 无不通过项")
    void completePayloadWithManifestContentIsAccepted() {
        String payload = """
                {"kubeconfigPath": "%s", "kubeContext": "c", "namespace": "ns",
                 "manifestContent": "apiVersion: v1\\nkind: ConfigMap"}
                """.formatted(hostAbsolute("cfg/kubeconfig").replace("\\", "\\\\"));

        assertTrue(KubernetesConfigPolicy.payloadIssues(payload).isEmpty());
    }

    @Test
    @DisplayName("路径不存在的配置 → 仍通过：仅校验路径形式，不检查文件是否存在")
    void nonexistentAbsolutePathsAreAccepted() {
        String payload = """
                {"kubeconfigPath": "%s", "kubeContext": "c", "namespace": "ns", "manifestPath": "%s"}
                """.formatted(
                hostAbsolute("no/such/dir/absent.kubeconfig").replace("\\", "\\\\"),
                hostAbsolute("no/such/dir/absent.yaml").replace("\\", "\\\\"));

        assertTrue(KubernetesConfigPolicy.payloadIssues(payload).isEmpty());
    }

    @Test
    @DisplayName("空内容 / null → EMPTY，且不触碰任何文件")
    void emptyPayloadIsRejected() {
        assertEquals(List.of(new Issue(Field.PAYLOAD, Reason.EMPTY)),
                KubernetesConfigPolicy.payloadIssues(null));
        assertEquals(List.of(new Issue(Field.PAYLOAD, Reason.EMPTY)),
                KubernetesConfigPolicy.payloadIssues("   "));
    }

    @Test
    @DisplayName("非 JSON 文本 / 尾随内容 → NOT_JSON")
    void malformedJsonIsRejected() {
        assertEquals(List.of(new Issue(Field.PAYLOAD, Reason.NOT_JSON)),
                KubernetesConfigPolicy.payloadIssues("{\"namespace\": "));
        assertEquals(List.of(new Issue(Field.PAYLOAD, Reason.NOT_JSON)),
                KubernetesConfigPolicy.payloadIssues("{\"namespace\":\"ns\"} trailing"));
        assertEquals(List.of(new Issue(Field.PAYLOAD, Reason.NOT_JSON)),
                KubernetesConfigPolicy.payloadIssues("[{\"namespace\":\"ns\""));
    }

    @Test
    @DisplayName("数组 / 数字 / 字符串 / 布尔 → NOT_JSON_OBJECT")
    void nonObjectJsonIsRejected() {
        for (String payload : List.of("[]", "123", "\"text\"", "true")) {
            assertEquals(List.of(new Issue(Field.PAYLOAD, Reason.NOT_JSON_OBJECT)),
                    KubernetesConfigPolicy.payloadIssues(payload), () -> "payload=" + payload);
        }
    }

    @Test
    @DisplayName("缺项 → MISSING，且一次列出所有缺项")
    void missingFieldsAreListedTogether() {
        List<Issue> issues = KubernetesConfigPolicy.payloadIssues("{\"namespace\": \"prod\"}");

        assertTrue(hasIssue(issues, Field.KUBECONFIG_PATH, Reason.MISSING), () -> "issues=" + issues);
        assertTrue(hasIssue(issues, Field.KUBE_CONTEXT, Reason.MISSING), () -> "issues=" + issues);
        assertTrue(hasIssue(issues, Field.MANIFEST, Reason.MISSING), () -> "issues=" + issues);
        assertFalse(hasIssue(issues, Field.NAMESPACE, Reason.MISSING), () -> "issues=" + issues);
    }

    @Test
    @DisplayName("空白必填项 → 视为缺项")
    void blankRequiredFieldsCountAsMissing() {
        String payload = """
                {"kubeconfigPath": "  ", "kubeContext": "c", "namespace": "ns",
                 "manifestContent": "apiVersion: v1"}
                """;

        List<Issue> issues = KubernetesConfigPolicy.payloadIssues(payload);
        assertTrue(hasIssue(issues, Field.KUBECONFIG_PATH, Reason.MISSING), () -> "issues=" + issues);
    }

    @Test
    @DisplayName("数字/布尔/数组不得被读成字符串字段")
    void nonTextValuesAreNotCoerced() {
        assertTrue(hasIssue(KubernetesConfigPolicy.payloadIssues("""
                        {"kubeconfigPath": "%s", "kubeContext": "c", "namespace": 123,
                         "manifestContent": "apiVersion: v1"}
                        """.formatted(hostAbsolute("cfg/kubeconfig").replace("\\", "\\\\"))),
                Field.NAMESPACE, Reason.NOT_NON_BLANK_TEXT));

        assertTrue(hasIssue(KubernetesConfigPolicy.payloadIssues("""
                        {"kubeconfigPath": true, "kubeContext": "c", "namespace": "ns",
                         "manifestContent": "apiVersion: v1"}
                        """),
                Field.KUBECONFIG_PATH, Reason.NOT_NON_BLANK_TEXT));

        assertTrue(hasIssue(KubernetesConfigPolicy.payloadIssues("""
                        {"kubeconfigPath": "%s", "kubeContext": ["c"], "namespace": "ns",
                         "manifestContent": "apiVersion: v1"}
                        """.formatted(hostAbsolute("cfg/kubeconfig").replace("\\", "\\\\"))),
                Field.KUBE_CONTEXT, Reason.NOT_NON_BLANK_TEXT));
    }

    @Test
    @DisplayName("相对路径 → NOT_ABSOLUTE_PATH（kubeconfigPath 与 manifestPath）")
    void relativePathsAreRejected() {
        String payload = """
                {"kubeconfigPath": "configs/kubeconfig.yaml", "kubeContext": "c", "namespace": "ns",
                 "manifestPath": "./manifests/app.yaml"}
                """;

        List<Issue> issues = KubernetesConfigPolicy.payloadIssues(payload);
        assertTrue(hasIssue(issues, Field.KUBECONFIG_PATH, Reason.NOT_ABSOLUTE_PATH), () -> "issues=" + issues);
        assertTrue(hasIssue(issues, Field.MANIFEST_PATH, Reason.NOT_ABSOLUTE_PATH), () -> "issues=" + issues);
    }

    @Test
    @DisplayName("manifestPath 与 manifestContent 并存 → 通过（优先级由执行期决定）")
    void bothManifestSourcesAreAccepted() {
        String payload = """
                {"kubeconfigPath": "%s", "kubeContext": "c", "namespace": "ns",
                 "manifestPath": "%s", "manifestContent": "apiVersion: v1"}
                """.formatted(
                hostAbsolute("cfg/kubeconfig").replace("\\", "\\\\"),
                hostAbsolute("manifests/app.yaml").replace("\\", "\\\\"));

        assertTrue(KubernetesConfigPolicy.payloadIssues(payload).isEmpty());
    }

    @Test
    @DisplayName("workloadName / timeout / 日志行数：合法值通过，非法值定位到字段")
    void optionalFieldsAreCheckedWhenPresent() {
        String valid = """
                {"kubeconfigPath": "%s", "kubeContext": "c", "namespace": "ns",
                 "manifestContent": "apiVersion: v1", "workloadName": "web",
                 "rolloutTimeoutSeconds": 300, "logTailLines": 100}
                """.formatted(hostAbsolute("cfg/kubeconfig").replace("\\", "\\\\"));
        assertTrue(KubernetesConfigPolicy.payloadIssues(valid).isEmpty());

        assertTrue(hasIssue(KubernetesConfigPolicy.payloadIssues("""
                        {"kubeconfigPath": "%s", "kubeContext": "c", "namespace": "ns",
                         "manifestContent": "apiVersion: v1", "workloadName": 5}
                        """.formatted(hostAbsolute("cfg/kubeconfig").replace("\\", "\\\\"))),
                Field.WORKLOAD_NAME, Reason.NOT_NON_BLANK_TEXT));

        assertTrue(hasIssue(KubernetesConfigPolicy.payloadIssues("""
                        {"kubeconfigPath": "%s", "kubeContext": "c", "namespace": "ns",
                         "manifestContent": "apiVersion: v1", "rolloutTimeoutSeconds": 0}
                        """.formatted(hostAbsolute("cfg/kubeconfig").replace("\\", "\\\\"))),
                Field.ROLLOUT_TIMEOUT_SECONDS, Reason.NOT_POSITIVE_INTEGER));

        assertTrue(hasIssue(KubernetesConfigPolicy.payloadIssues("""
                        {"kubeconfigPath": "%s", "kubeContext": "c", "namespace": "ns",
                         "manifestContent": "apiVersion: v1", "rolloutTimeoutSeconds": "300"}
                        """.formatted(hostAbsolute("cfg/kubeconfig").replace("\\", "\\\\"))),
                Field.ROLLOUT_TIMEOUT_SECONDS, Reason.NOT_POSITIVE_INTEGER));

        assertTrue(hasIssue(KubernetesConfigPolicy.payloadIssues("""
                        {"kubeconfigPath": "%s", "kubeContext": "c", "namespace": "ns",
                         "manifestContent": "apiVersion: v1", "logTailLines": -1}
                        """.formatted(hostAbsolute("cfg/kubeconfig").replace("\\", "\\\\"))),
                Field.LOG_TAIL_LINES, Reason.NOT_POSITIVE_INTEGER));
    }

    @Test
    @DisplayName("空白的可选字段 → 视为未配置（与执行期读取一致）")
    void blankOptionalFieldsCountAsAbsent() {
        String payload = """
                {"kubeconfigPath": "%s", "kubeContext": "c", "namespace": "ns",
                 "manifestPath": "", "manifestContent": "apiVersion: v1", "workloadName": "  "}
                """.formatted(hostAbsolute("cfg/kubeconfig").replace("\\", "\\\\"));

        assertTrue(KubernetesConfigPolicy.payloadIssues(payload).isEmpty());
    }

    @Test
    @DisplayName("未知字段原样保留，不参与判定")
    void unknownFieldsAreIgnored() {
        String payload = """
                {"kubeconfigPath": "%s", "kubeContext": "c", "namespace": "ns",
                 "manifestContent": "apiVersion: v1", "futureField": {"a": [1, 2]}, "another": null}
                """.formatted(hostAbsolute("cfg/kubeconfig").replace("\\", "\\\\"));

        assertTrue(KubernetesConfigPolicy.payloadIssues(payload).isEmpty());
    }

    @Test
    @DisplayName("Issue 只含字段与原因，不含 payload 中的任何值")
    void issuesCarryNoPayloadValues() {
        String payload = """
                {"kubeconfigPath": "CANARY-KUBECONFIG-VALUE/kubeconfig", "kubeContext": "CANARY-CONTEXT",
                 "manifestContent": "CANARY-MANIFEST-CONTENT", "rolloutTimeoutSeconds": -1}
                """;

        List<Issue> issues = KubernetesConfigPolicy.payloadIssues(payload);
        assertFalse(issues.isEmpty());
        String rendered = issues.toString();
        assertFalse(rendered.contains("CANARY"), () -> "issues leaked a payload value: " + rendered);
    }

    @Test
    @DisplayName("与执行器门禁同源：缺项规则由 configIssues 提供")
    void presenceRulesAreTheExecutorGate() {
        KubernetesExecutionConfig config = KubernetesExecutionConfig.builder()
                .namespace("prod")
                .build();

        List<Issue> issues = KubernetesConfigPolicy.configIssues(config);
        assertEquals(3, issues.size(), () -> "issues=" + issues);
        assertTrue(hasIssue(issues, Field.KUBECONFIG_PATH, Reason.MISSING));
        assertTrue(hasIssue(issues, Field.KUBE_CONTEXT, Reason.MISSING));
        assertTrue(hasIssue(issues, Field.MANIFEST, Reason.MISSING));
    }
}
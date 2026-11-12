package com.infrahub.release.service.executor.kubernetes;

import com.infrahub.release.dto.KubernetesExecutionConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The command surface of the executor: every command names its target explicitly, so no command can
 * fall back to the default kubeconfig, the current context or an inherited {@code KUBECONFIG}.
 */
class KubectlCommandBuilderTest {

    private static final String KUBECONFIG = "C:/bound/target.kubeconfig";
    private static final String CONTEXT = "bound-context";
    private static final String NAMESPACE = "bound-ns";

    private static KubernetesExecutionConfig config() {
        return KubernetesExecutionConfig.builder()
                .kubeconfigPath(KUBECONFIG)
                .kubeContext(CONTEXT)
                .namespace(NAMESPACE)
                .manifestContent("apiVersion: v1\nkind: ConfigMap")
                .workloadName("web")
                .build();
    }

    @Test
    @DisplayName("apply：目标三元组 + 固定从 stdin 读取，不重新读取文件")
    void applyCommandCarriesTargetAndReadsStdin() {
        assertEquals(List.of("kubectl", "--kubeconfig", KUBECONFIG, "--context", CONTEXT, "-n", NAMESPACE,
                        "apply", "-f", "-"),
                new KubectlCommandBuilder(config()).buildApplyCommand());
    }

    @Test
    @DisplayName("rollout status：目标三元组 + 明确的 deployment 与超时")
    void rolloutCommandCarriesTarget() {
        KubernetesExecutionConfig config = config();
        config.setRolloutTimeoutSeconds(42);

        assertEquals(List.of("kubectl", "--kubeconfig", KUBECONFIG, "--context", CONTEXT, "-n", NAMESPACE,
                        "rollout", "status", "deployment/web", "--timeout=42s"),
                new KubectlCommandBuilder(config).buildRolloutStatusCommand());
    }

    @Test
    @DisplayName("logs：目标三元组 + pod 名与 tail")
    void logsCommandCarriesTarget() {
        KubernetesExecutionConfig config = config();
        config.setLogTailLines(7);

        assertEquals(List.of("kubectl", "--kubeconfig", KUBECONFIG, "--context", CONTEXT, "-n", NAMESPACE,
                        "logs", "web-abc", "--tail=7"),
                new KubectlCommandBuilder(config).buildLogsCommand("web-abc"));
    }

    @Test
    @DisplayName("get pods：目标三元组 + 明确的 selector")
    void getPodsCommandCarriesTarget() {
        assertEquals(List.of("kubectl", "--kubeconfig", KUBECONFIG, "--context", CONTEXT, "-n", NAMESPACE,
                        "get", "pods", "-l", "app=web", "-o", "jsonpath={.items[*].metadata.name}"),
                new KubectlCommandBuilder(config()).buildGetPodsCommand());
    }

    @Test
    @DisplayName("delete：目标三元组 + 固定从 stdin 读取")
    void deleteCommandCarriesTarget() {
        assertEquals(List.of("kubectl", "--kubeconfig", KUBECONFIG, "--context", CONTEXT, "-n", NAMESPACE,
                        "delete", "-f", "-"),
                new KubectlCommandBuilder(config()).buildDeleteCommand());
    }

    @Test
    @DisplayName("未配置的字段一律拒绝构造，不静默回退到默认 kubeconfig 或 current-context")
    void missingTargetFieldIsRejected() {
        assertRejected(configWithKubeconfig(null), "kubeconfigPath");
        assertRejected(configWithKubeconfig("   "), "kubeconfigPath");
        assertRejected(configWithContext(null), "kubeContext");
        assertRejected(configWithContext(" "), "kubeContext");
        assertRejected(configWithNamespace(null), "namespace");
        assertRejected(configWithNamespace(""), "namespace");
    }

    @Test
    @DisplayName("拒绝信息说明不会使用默认 kubeconfig、current-context 或 KUBECONFIG")
    void rejectionExplainsTheForbiddenFallbacks() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new KubectlCommandBuilder(configWithKubeconfig(null)));

        assertTrue(error.getMessage().contains("default kubeconfig"), () -> "message=" + error.getMessage());
        assertTrue(error.getMessage().contains("KUBECONFIG"), () -> "message=" + error.getMessage());
    }

    @Test
    @DisplayName("workloadName 缺失时拒绝 rollout 与 get pods，不构造指向未知对象的命令")
    void workloadDependentCommandsRequireWorkloadName() {
        KubernetesExecutionConfig config = config();
        config.setWorkloadName(null);
        KubectlCommandBuilder builder = new KubectlCommandBuilder(config);

        assertThrows(IllegalStateException.class, builder::buildRolloutStatusCommand);
        assertThrows(IllegalStateException.class, builder::buildGetPodsCommand);
    }

    @Test
    @DisplayName("null 配置被拒绝")
    void nullConfigIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new KubectlCommandBuilder(null));
    }

    private static void assertRejected(KubernetesExecutionConfig config, String field) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new KubectlCommandBuilder(config));
        assertTrue(error.getMessage().contains(field), () -> "message=" + error.getMessage());
    }

    private static KubernetesExecutionConfig configWithKubeconfig(String value) {
        KubernetesExecutionConfig config = config();
        config.setKubeconfigPath(value);
        return config;
    }

    private static KubernetesExecutionConfig configWithContext(String value) {
        KubernetesExecutionConfig config = config();
        config.setKubeContext(value);
        return config;
    }

    private static KubernetesExecutionConfig configWithNamespace(String value) {
        KubernetesExecutionConfig config = config();
        config.setNamespace(value);
        return config;
    }
}
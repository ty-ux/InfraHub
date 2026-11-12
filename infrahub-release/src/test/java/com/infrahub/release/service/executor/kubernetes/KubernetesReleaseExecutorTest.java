package com.infrahub.release.service.executor.kubernetes;

import com.infrahub.release.dto.KubernetesExecutionConfig;
import com.infrahub.release.entity.ReleaseExecutorConfig;
import com.infrahub.release.service.executor.ReleaseExecutionContext;
import com.infrahub.release.service.executor.ReleaseExecutor.ValidationResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The configuration gate of the executor.
 *
 * <p>{@code validate} decides whether a configuration is complete enough to be attempted: it reads no
 * file and starts no process, so it stays a pure gate. The kubeconfig file and the manifest are
 * inspected per release, before the first command, by {@link KubernetesReleaseExecutor#execute}.</p>
 */
class KubernetesReleaseExecutorTest {

    private final KubernetesReleaseExecutor executor = new KubernetesReleaseExecutor();

    private ReleaseExecutionContext contextWith(Object executorConfig) {
        return ReleaseExecutionContext.builder()
                .order(null)
                .service(null)
                .env(null)
                .version(null)
                .artifact(null)
                .deployConfig(null)
                .executorConfig(executorConfig)
                .hostId(1L)
                .instanceId(2L)
                .build();
    }

    private static KubernetesExecutionConfig completeConfig() {
        return KubernetesExecutionConfig.builder()
                .kubeconfigPath("C:/bound/target.kubeconfig")
                .kubeContext("bound-context")
                .namespace("prod")
                .manifestPath("C:/manifests/app.yaml")
                .build();
    }

    @Test
    @DisplayName("null 上下文 → invalid")
    void validateNullContextIsInvalid() {
        ValidationResult result = executor.validate(null);
        assertFalse(result.isValid());
    }

    @Test
    @DisplayName("类型化配置（manifestPath + 显式目标）→ valid")
    void validateTypedConfigWithManifestPathIsValid() {
        ValidationResult result = executor.validate(contextWith(completeConfig()));
        assertTrue(result.isValid(), () -> "expected valid but was: " + result.errorMessage());
    }

    @Test
    @DisplayName("类型化配置（manifestContent + 显式目标）→ valid")
    void validateTypedConfigWithManifestContentIsValid() {
        KubernetesExecutionConfig config = completeConfig();
        config.setManifestPath(null);
        config.setManifestContent("apiVersion: v1\nkind: ConfigMap\nmetadata:\n  name: cm\n  namespace: staging");

        ValidationResult result = executor.validate(contextWith(config));
        assertTrue(result.isValid(), () -> "expected valid but was: " + result.errorMessage());
    }

    @Test
    @DisplayName("执行器配置实体（非 DTO）→ invalid")
    void validateEntityConfigIsInvalid() {
        ReleaseExecutorConfig entity = new ReleaseExecutorConfig();
        entity.setExecutorType("KUBERNETES");
        entity.setConfigPayload("{\"namespace\":\"prod\"}");
        ValidationResult result = executor.validate(contextWith(entity));
        assertFalse(result.isValid());
        assertTrue(result.errorMessage().contains("KubernetesExecutionConfig"));
    }

    @Test
    @DisplayName("kubeconfigPath 缺失或空白 → invalid，且不静默回退默认 kubeconfig")
    void validateMissingKubeconfigPathIsInvalid() {
        KubernetesExecutionConfig missing = completeConfig();
        missing.setKubeconfigPath(null);
        ValidationResult result = executor.validate(contextWith(missing));
        assertFalse(result.isValid());
        assertTrue(result.errorMessage().contains("kubeconfigPath"), () -> "message=" + result.errorMessage());
        assertTrue(result.errorMessage().contains("default kubeconfig"), () -> "message=" + result.errorMessage());

        KubernetesExecutionConfig blank = completeConfig();
        blank.setKubeconfigPath("   ");
        assertFalse(executor.validate(contextWith(blank)).isValid());
    }

    @Test
    @DisplayName("kubeContext 缺失或空白 → invalid，且不静默回退 current-context")
    void validateMissingKubeContextIsInvalid() {
        KubernetesExecutionConfig missing = completeConfig();
        missing.setKubeContext(null);
        ValidationResult result = executor.validate(contextWith(missing));
        assertFalse(result.isValid());
        assertTrue(result.errorMessage().contains("kubeContext"), () -> "message=" + result.errorMessage());
        assertTrue(result.errorMessage().contains("current context"), () -> "message=" + result.errorMessage());

        KubernetesExecutionConfig blank = completeConfig();
        blank.setKubeContext(" ");
        assertFalse(executor.validate(contextWith(blank)).isValid());
    }

    @Test
    @DisplayName("manifestPath 与 manifestContent 均缺失 → invalid")
    void validateMissingManifestIsInvalid() {
        KubernetesExecutionConfig config = completeConfig();
        config.setManifestPath(null);
        ValidationResult result = executor.validate(contextWith(config));
        assertFalse(result.isValid());
        assertTrue(result.errorMessage().contains("manifest"));
    }

    @Test
    @DisplayName("namespace 缺失或空白 → invalid")
    void validateMissingNamespaceIsInvalid() {
        KubernetesExecutionConfig missing = completeConfig();
        missing.setNamespace(null);
        ValidationResult result = executor.validate(contextWith(missing));
        assertFalse(result.isValid());
        assertTrue(result.errorMessage().contains("namespace"), () -> "message=" + result.errorMessage());

        KubernetesExecutionConfig blank = completeConfig();
        blank.setNamespace("");
        assertFalse(executor.validate(contextWith(blank)).isValid());
    }

    @Test
    @DisplayName("validate 不读取文件：路径不存在时仍只是配置门禁，由 execute 前置检查拒绝")
    void validateDoesNotTouchTheFilesystem() {
        KubernetesExecutionConfig config = completeConfig();
        config.setKubeconfigPath("C:/no/such/dir/absent.kubeconfig");
        config.setManifestPath("C:/no/such/dir/absent.yaml");

        ValidationResult result = executor.validate(contextWith(config));
        assertTrue(result.isValid(), () -> "validate must stay a pure gate but was: " + result.errorMessage());
    }
}
package com.infrahub.release.service.executor.kubernetes;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit behaviour of the pre-flight input policy. No child process, no kubectl and no cluster is
 * involved: every case here is decided from local files and in-memory YAML.
 */
class ManifestValidatorTest {

    private static final String NAMESPACE = "prod";

    private static final String CONFIG_MAP = """
            apiVersion: v1
            kind: ConfigMap
            metadata:
              name: app-config
              namespace: prod
            """;

    private static final String DEPLOYMENT = """
            apiVersion: apps/v1
            kind: Deployment
            metadata:
              name: web
              namespace: prod
            """;

    private static final String SERVICE = """
            apiVersion: v1
            kind: Service
            metadata:
              name: web-svc
              namespace: prod
            """;

    /** Synthetic markers only; no real Secret is read anywhere in this class. */
    private static final String KEY_CANARY = "CANARY-DUPLICATE-KEY-3141";
    private static final String VALUE_CANARY = "CANARY-VALUE-2718";
    private static final String TAG_CANARY = "CANARY-TAG-5319";
    /** Values no standard scalar tag can convert: an integer, a float and a base64 blob. */
    private static final String FLOAT_CANARY = "CANARY-FLOAT-8842";
    private static final String BLOB_CANARY = "CANARY-BINARY-55213";

    @TempDir
    Path tempDir;

    // ---------------------------------------------------------------- kubeconfig binding

    @Test
    @DisplayName("kubeconfig：绝对且可读的普通文件被接受")
    void absoluteReadableKubeconfigIsAccepted() throws IOException {
        Path kubeconfig = write("kubeconfig.yaml", "apiVersion: v1\nclusters: []\n");
        assertNull(ManifestValidator.validateKubeconfigPath(kubeconfig.toString()));
    }

    @Test
    @DisplayName("kubeconfig：缺失、空白与相对路径被拒绝")
    void missingOrRelativeKubeconfigIsRejected() {
        assertTrue(ManifestValidator.validateKubeconfigPath(null).contains("must be configured"));
        assertTrue(ManifestValidator.validateKubeconfigPath("  ").contains("must be configured"));
        assertTrue(ManifestValidator.validateKubeconfigPath("relative/kubeconfig").contains("absolute"));
    }

    @Test
    @DisplayName("kubeconfig：不存在的绝对路径被拒绝")
    void nonexistentKubeconfigIsRejected() {
        String reason = ManifestValidator.validateKubeconfigPath(tempDir.resolve("absent.yaml").toString());
        assertNotNull(reason);
        assertTrue(reason.contains("does not exist"), () -> "reason=" + reason);
    }

    @Test
    @DisplayName("kubeconfig：目录不是普通文件，被拒绝")
    void directoryKubeconfigIsRejected() {
        String reason = ManifestValidator.validateKubeconfigPath(tempDir.toString());
        assertNotNull(reason);
        assertTrue(reason.contains("not a regular file"), () -> "reason=" + reason);
    }

    // ---------------------------------------------------------------- path source

    @Test
    @DisplayName("manifestPath：绝对可读文件的快照原样返回")
    void manifestPathSnapshotIsReturnedVerbatim() throws IOException {
        Path manifest = write("app.yaml", CONFIG_MAP);
        ManifestValidator.Result result = ManifestValidator.validatePath(manifest.toString(), NAMESPACE);

        assertTrue(result.isValid(), () -> "rejection=" + result.rejection());
        assertEquals(CONFIG_MAP, result.content());
    }

    @Test
    @DisplayName("manifestPath：不存在、相对路径与目录被拒绝")
    void manifestPathRejectsMissingRelativeAndDirectory() {
        assertTrue(ManifestValidator.validatePath(tempDir.resolve("absent.yaml").toString(), NAMESPACE)
                .rejection().contains("does not exist"));
        assertTrue(ManifestValidator.validatePath("relative/app.yaml", NAMESPACE)
                .rejection().contains("absolute"));
        assertTrue(ManifestValidator.validatePath(tempDir.toString(), NAMESPACE)
                .rejection().contains("not a regular file"));
    }

    @Test
    @DisplayName("manifestPath：非 UTF-8 字节被拒绝，不静默替换字符")
    void manifestPathRejectsNonUtf8Bytes() throws IOException {
        Path manifest = tempDir.resolve("latin1.yaml");
        Files.write(manifest, ("kind: ConfigMap\nmetadata:\n  name: café\n  namespace: prod\n")
                .getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));

        ManifestValidator.Result result = ManifestValidator.validatePath(manifest.toString(), NAMESPACE);
        assertFalse(result.isValid());
        assertTrue(result.rejection().contains("not valid UTF-8"), () -> "rejection=" + result.rejection());
    }

    @Test
    @DisplayName("manifestPath：文件内容仍受 kind/namespace 校验约束")
    void manifestPathContentIsStillScoped() throws IOException {
        Path manifest = write("other-ns.yaml", CONFIG_MAP.replace("namespace: prod", "namespace: staging"));
        ManifestValidator.Result result = ManifestValidator.validatePath(manifest.toString(), NAMESPACE);
        assertFalse(result.isValid());
        assertTrue(result.rejection().contains("staging"), () -> "rejection=" + result.rejection());
    }

    // ---------------------------------------------------------------- accepted content

    @Test
    @DisplayName("内容：允许的 Deployment/Service/ConfigMap 多文档被接受")
    void multiDocumentAllowedKindsAreAccepted() {
        ManifestValidator.Result result = ManifestValidator
                .validateContent(DEPLOYMENT + "---\n" + SERVICE + "---\n" + CONFIG_MAP, NAMESPACE);
        assertTrue(result.isValid(), () -> "rejection=" + result.rejection());
    }

    @Test
    @DisplayName("内容：文档分隔符与注释不改变文档集合")
    void separatorsAndCommentsDoNotChangeDocumentSet() {
        ManifestValidator.Result result = ManifestValidator.validateContent(
                "# leading comment\n---\n" + CONFIG_MAP + "\n---\n# only a comment\n", NAMESPACE);
        assertTrue(result.isValid(), () -> "rejection=" + result.rejection());
    }

    @Test
    @DisplayName("内容：锚点与合并键被解析为最终对象，仍通过校验")
    void anchorsAndMergeKeysAreResolved() {
        String merged = """
                apiVersion: v1
                kind: ConfigMap
                origin: &meta
                  name: app-config
                  namespace: prod
                metadata:
                  <<: *meta
                """;
        ManifestValidator.Result result = ManifestValidator.validateContent(merged, NAMESPACE);
        assertTrue(result.isValid(), () -> "rejection=" + result.rejection());
    }

    // ---------------------------------------------------------------- complete resource types

    @Test
    @DisplayName("类型：三种完整类型（apps/v1 Deployment、v1 Service、v1 ConfigMap）分别被接受")
    void allowedCompleteTypesAreAccepted() {
        assertTrue(ManifestValidator.validateContent(DEPLOYMENT, NAMESPACE).isValid(),
                () -> "apps/v1 Deployment was refused: "
                        + ManifestValidator.validateContent(DEPLOYMENT, NAMESPACE).rejection());
        assertTrue(ManifestValidator.validateContent(SERVICE, NAMESPACE).isValid(),
                () -> "v1 Service was refused: "
                        + ManifestValidator.validateContent(SERVICE, NAMESPACE).rejection());
        assertTrue(ManifestValidator.validateContent(CONFIG_MAP, NAMESPACE).isValid(),
                () -> "v1 ConfigMap was refused: "
                        + ManifestValidator.validateContent(CONFIG_MAP, NAMESPACE).rejection());
    }

    @Test
    @DisplayName("类型：缺少 apiVersion 被拒绝，并指明该 kind 要求的版本")
    void missingApiVersionIsRejected() {
        ManifestValidator.Result deployment = ManifestValidator.validateContent(
                "kind: Deployment\nmetadata: {name: web, namespace: prod}\n", NAMESPACE);
        assertFalse(deployment.isValid());
        assertTrue(deployment.rejection().contains("no non-empty 'apiVersion'"),
                () -> "rejection=" + deployment.rejection());
        assertTrue(deployment.rejection().contains("only as 'apps/v1'"),
                () -> "rejection=" + deployment.rejection());

        ManifestValidator.Result service = ManifestValidator.validateContent(
                "kind: Service\nmetadata: {name: web-svc, namespace: prod}\n", NAMESPACE);
        assertFalse(service.isValid());
        assertTrue(service.rejection().contains("only as 'v1'"), () -> "rejection=" + service.rejection());
    }

    @Test
    @DisplayName("类型：空白、非字符串的 apiVersion 与缺失同等对待")
    void blankOrNonStringApiVersionIsRejected() {
        for (String apiVersion : List.of("\"  \"", "\"\"", "123", "[v1]", "{group: apps}", "~")) {
            ManifestValidator.Result result = ManifestValidator.validateContent(
                    "apiVersion: " + apiVersion + "\nkind: ConfigMap\nmetadata: {name: cm, namespace: prod}\n",
                    NAMESPACE);
            assertFalse(result.isValid(), () -> "apiVersion " + apiVersion + " was accepted");
            assertTrue(result.rejection().contains("no non-empty 'apiVersion'"),
                    () -> "apiVersion " + apiVersion + " -> " + result.rejection());
        }
    }

    @Test
    @DisplayName("类型：版本错误、API 组错误与 kind/版本不匹配都被拒绝")
    void wrongVersionOrGroupOrMismatchedKindIsRejected() {
        // an allowed kind with an older version of its own group
        assertVersionRejected("apiVersion: apps/v1beta1\nkind: Deployment\n", "must declare apiVersion 'apps/v1'");
        // an allowed kind with a foreign API group
        assertVersionRejected("apiVersion: extensions/v1beta1\nkind: Deployment\n", "must declare apiVersion 'apps/v1'");
        // an allowed kind with a version of a different group
        assertVersionRejected("apiVersion: v1\nkind: Deployment\n", "must declare apiVersion 'apps/v1'");
        assertVersionRejected("apiVersion: apps/v1\nkind: ConfigMap\n", "must declare apiVersion 'v1'");
        assertVersionRejected("apiVersion: apps/v1\nkind: Service\n", "must declare apiVersion 'v1'");
        // a core-group kind with a version that does not exist
        assertVersionRejected("apiVersion: v2\nkind: ConfigMap\n", "must declare apiVersion 'v1'");
        assertVersionRejected("apiVersion: example.io/v1\nkind: ConfigMap\n", "must declare apiVersion 'v1'");
    }

    @Test
    @DisplayName("类型：策略逐文档生效，第二个文档的类型同样被拒绝")
    void everyDocumentMustCarryACompleteType() {
        String mixed = DEPLOYMENT + "---\n"
                + "apiVersion: v1\nkind: Deployment\nmetadata: {name: api, namespace: prod}\n";
        ManifestValidator.Result result = ManifestValidator.validateContent(mixed, NAMESPACE);
        assertFalse(result.isValid());
        assertTrue(result.rejection().contains("must declare apiVersion 'apps/v1'"),
                () -> "rejection=" + result.rejection());
    }

    @Test
    @DisplayName("类型：拒绝信息列出被允许的完整类型")
    void rejectionNamesTheAllowedCompleteTypes() {
        ManifestValidator.Result result = ManifestValidator.validateContent(
                "apiVersion: v1\nkind: Secret\nmetadata: {name: s, namespace: prod}\n", NAMESPACE);
        assertFalse(result.isValid());
        assertTrue(result.rejection().contains("ConfigMap (v1)"), () -> "rejection=" + result.rejection());
        assertTrue(result.rejection().contains("Deployment (apps/v1)"), () -> "rejection=" + result.rejection());
        assertTrue(result.rejection().contains("Service (v1)"), () -> "rejection=" + result.rejection());
    }

    @Test
    @DisplayName("类型：rollout 验收目标只认 apps/v1 Deployment")
    void deploymentNamesOnlyCountsAppsV1Deployments() {
        // the rollout target is read from the same type policy the apply was accepted under
        assertTrue(ManifestValidator.deploymentNames("apiVersion: v1\nkind: Deployment\nmetadata:\n"
                + "  name: web\n  namespace: prod\n").isEmpty());
        assertTrue(ManifestValidator.deploymentNames("apiVersion: apps/v1beta1\nkind: Deployment\nmetadata:\n"
                + "  name: web\n  namespace: prod\n").isEmpty());
        assertEquals(List.of("web"), ManifestValidator.deploymentNames(DEPLOYMENT));
    }

    // ---------------------------------------------------------------- rejected content

    @Test
    @DisplayName("内容：空内容与空文档集合被拒绝")
    void emptyContentAndEmptyDocumentSetAreRejected() {
        assertTrue(ManifestValidator.validateContent(null, NAMESPACE).rejection().contains("must not be blank"));
        assertTrue(ManifestValidator.validateContent("   ", NAMESPACE).rejection().contains("must not be blank"));
        assertTrue(ManifestValidator.validateContent("", NAMESPACE).rejection().contains("must not be blank"));
        assertTrue(ManifestValidator.validateContent("---\n", NAMESPACE).rejection().contains("no YAML documents"));
        assertTrue(ManifestValidator.validateContent("# comment only\n", NAMESPACE).rejection()
                .contains("no YAML documents"));
    }

    @Test
    @DisplayName("内容：语法错误的 YAML 被拒绝且附带解析原因")
    void invalidYamlIsRejected() {
        ManifestValidator.Result result = ManifestValidator.validateContent("kind: ConfigMap\n  metadata:\n x: 1\n", NAMESPACE);
        assertFalse(result.isValid());
        assertTrue(result.rejection().startsWith("manifest is not valid YAML: "), () -> "rejection=" + result.rejection());
    }

    @Test
    @DisplayName("内容：重复键被拒绝，不做最后一个键获胜的解析")
    void duplicateKeysAreRejected() {
        ManifestValidator.Result result = ManifestValidator.validateContent(
                "apiVersion: v1\nkind: ConfigMap\nmetadata:\n  name: a\n  name: b\n  namespace: prod\n", NAMESPACE);
        assertFalse(result.isValid());
        assertTrue(result.rejection().contains("duplicate key"), () -> "rejection=" + result.rejection());
    }

    @Test
    @DisplayName("内容：非映射文档（标量或序列）被拒绝")
    void nonMappingDocumentsAreRejected() {
        assertTrue(ManifestValidator.validateContent("just-a-string", NAMESPACE).rejection()
                .contains("is not a Kubernetes object"));
        assertTrue(ManifestValidator.validateContent("- a\n- b\n", NAMESPACE).rejection()
                .contains("is not a Kubernetes object"));
    }

    @Test
    @DisplayName("内容：未允许的 kind 与 List 被拒绝")
    void unallowedKindsAndListAreRejected() {
        assertTrue(ManifestValidator.validateContent(
                        "apiVersion: v1\nkind: Secret\nmetadata: {name: s, namespace: prod}\n", NAMESPACE)
                .rejection().contains("does not allow"));
        assertTrue(ManifestValidator.validateContent(
                        "apiVersion: apps/v1\nkind: DaemonSet\nmetadata: {name: d, namespace: prod}\n", NAMESPACE)
                .rejection().contains("does not allow"));
        assertTrue(ManifestValidator.validateContent("apiVersion: v1\nkind: List\nitems: []\n", NAMESPACE)
                .rejection().contains("does not allow"));
    }

    @Test
    @DisplayName("内容：cluster-scoped 资源（无显式 namespace）被拒绝")
    void clusterScopedResourcesAreRejected() {
        // an allowed complete type without a namespace is what a scope-less object looks like here
        assertTrue(ManifestValidator.validateContent(
                        "apiVersion: v1\nkind: ConfigMap\nmetadata: {name: cm}\n", NAMESPACE)
                .rejection().contains("no explicit 'metadata.namespace'"));
        assertTrue(ManifestValidator.validateContent(
                        "apiVersion: apps/v1\nkind: Deployment\nmetadata: {name: web}\n", NAMESPACE)
                .rejection().contains("no explicit 'metadata.namespace'"));
        assertTrue(ManifestValidator.validateContent(
                        "apiVersion: v1\nkind: ConfigMap\nmetadata: {name: cm, namespace: \"  \"}\n", NAMESPACE)
                .rejection().contains("no explicit 'metadata.namespace'"));
        // a genuinely cluster-scoped kind never reaches that check: its type is not accepted at all
        assertTrue(ManifestValidator.validateContent("apiVersion: v1\nkind: Namespace\nmetadata: {name: team-a}\n", NAMESPACE)
                .rejection().contains("does not allow"));
        assertTrue(ManifestValidator.validateContent(
                        "apiVersion: rbac.authorization.k8s.io/v1\nkind: ClusterRole\nmetadata: {name: r}\n", NAMESPACE)
                .rejection().contains("does not allow"));
    }

    @Test
    @DisplayName("内容：与执行器 namespace 不一致的对象被拒绝，并指明两个 namespace")
    void namespaceMismatchIsRejected() {
        ManifestValidator.Result result = ManifestValidator.validateContent(
                CONFIG_MAP.replace("namespace: prod", "namespace: staging"), NAMESPACE);
        assertFalse(result.isValid());
        assertTrue(result.rejection().contains("staging") && result.rejection().contains("prod"),
                () -> "rejection=" + result.rejection());
    }

    @Test
    @DisplayName("内容：重复对象身份被拒绝")
    void duplicateObjectIdentityIsRejected() {
        ManifestValidator.Result result = ManifestValidator.validateContent(CONFIG_MAP + "---\n" + CONFIG_MAP, NAMESPACE);
        assertFalse(result.isValid());
        assertTrue(result.rejection().contains("more than once"), () -> "rejection=" + result.rejection());
    }

    @Test
    @DisplayName("内容：同名不同类型不算重复身份")
    void sameNameDifferentKindIsNotADuplicate() {
        String deployment = CONFIG_MAP
                .replace("apiVersion: v1", "apiVersion: apps/v1")
                .replace("kind: ConfigMap", "kind: Deployment");
        ManifestValidator.Result result = ManifestValidator.validateContent(CONFIG_MAP + "---\n" + deployment, NAMESPACE);
        assertTrue(result.isValid(), () -> "rejection=" + result.rejection());
    }

    @Test
    @DisplayName("内容：缺少 metadata 或 metadata.name 的对象被拒绝")
    void missingMetadataOrNameIsRejected() {
        assertTrue(ManifestValidator.validateContent("apiVersion: v1\nkind: ConfigMap\n", NAMESPACE)
                .rejection().contains("no 'metadata' mapping"));
        assertTrue(ManifestValidator.validateContent(
                        "apiVersion: v1\nkind: ConfigMap\nmetadata: {namespace: prod}\n", NAMESPACE)
                .rejection().contains("no non-empty 'metadata.name'"));
        assertTrue(ManifestValidator.validateContent("metadata: {name: cm, namespace: prod}\n", NAMESPACE)
                .rejection().contains("no non-empty 'kind'"));
    }

    @Test
    @DisplayName("内容：非字符串的 name/kind 被拒绝，而不是按 YAML 类型推断")
    void nonStringNameIsRejected() {
        assertTrue(ManifestValidator.validateContent(
                        "apiVersion: v1\nkind: ConfigMap\nmetadata: {name: 123, namespace: prod}\n", NAMESPACE)
                .rejection().contains("no non-empty 'metadata.name'"));
        assertTrue(ManifestValidator.validateContent("kind: 123\nmetadata: {name: a, namespace: prod}\n", NAMESPACE)
                .rejection().contains("no non-empty 'kind'"));
        // an unquoted date stays text and is therefore an unknown kind, never a resolved object
        assertTrue(ManifestValidator.validateContent("kind: 2024-01-02\nmetadata: {name: a, namespace: prod}\n", NAMESPACE)
                .rejection().contains("does not allow"));
    }

    @Test
    @DisplayName("内容：显式的非本地标签被拒绝")
    void explicitCustomTagIsRejected() {
        ManifestValidator.Result result = ManifestValidator.validateContent(
                "apiVersion: v1\nkind: ConfigMap\nmetadata: {name: a, namespace: prod}\n"
                        + "value: !!java.util.Date 2020-01-01\n", NAMESPACE);
        assertFalse(result.isValid());
        assertTrue(result.rejection().contains("forbidden tag"), () -> "rejection=" + result.rejection());
        assertTrue(result.rejection().contains("valid YAML"), () -> "rejection=" + result.rejection());
    }

    @Test
    @DisplayName("内容：namespace 未配置时先拒绝，不按空字符串比较")
    void unconfiguredNamespaceIsRejectedBeforeComparison() {
        assertTrue(ManifestValidator.validateContent(CONFIG_MAP, null).rejection().contains("namespace must be configured"));
        assertTrue(ManifestValidator.validateContent(CONFIG_MAP, " ").rejection().contains("namespace must be configured"));
    }

    @Test
    @DisplayName("拒绝信息不回显 manifest 正文")
    void rejectionDoesNotEchoManifestContent() {
        String secretLooking = "apiVersion: v1\nkind: Secret\nmetadata: {name: s, namespace: prod}\n"
                + "data:\n  password: SENSITIVE-9911\n";
        ManifestValidator.Result result = ManifestValidator.validateContent(secretLooking, NAMESPACE);
        assertFalse(result.isValid());
        assertFalse(result.rejection().contains("SENSITIVE-9911"), () -> "rejection=" + result.rejection());
    }

    // ---------------------------------------------------------------- failure desensitization

    @Test
    @DisplayName("解析错误路径不回显正文：只报告诊断与位置")
    void parseErrorDoesNotEchoManifestContent() {
        // the parser's own message quotes the offending line, which must never reach the log
        ManifestValidator.Result result = ManifestValidator.validateContent(
                "kind: ConfigMap\nmetadata:\n  name: cm\n  namespace: prod\ndata:\n  token: \"SENSITIVE-9911\n", NAMESPACE);

        assertFalse(result.isValid());
        assertFalse(result.rejection().contains("SENSITIVE-9911"), () -> "rejection=" + result.rejection());
        assertTrue(result.rejection().startsWith("manifest is not valid YAML: "),
                () -> "rejection=" + result.rejection());
        assertTrue(result.rejection().contains("parse failure"), () -> "rejection=" + result.rejection());
        assertTrue(result.rejection().contains("line "), () -> "rejection=" + result.rejection());
    }

    @Test
    @DisplayName("脱敏：重复的普通键名含合成标记时，拒绝信息不回显该键")
    void duplicateKeyRejectionDoesNotEchoTheKey() {
        ManifestValidator.Result result = ManifestValidator.validateContent(
                CONFIG_MAP + KEY_CANARY + ": a\n" + KEY_CANARY + ": b\n", NAMESPACE);

        assertFalse(result.isValid());
        assertTrue(result.rejection().contains("duplicate key"), () -> "rejection=" + result.rejection());
        assertFalse(result.rejection().contains(KEY_CANARY), () -> "rejection=" + result.rejection());
        assertTrue(result.rejection().contains("line "), () -> "rejection=" + result.rejection());
    }

    @Test
    @DisplayName("脱敏：重复的复合键内容含合成标记时，拒绝信息同样不回显")
    void duplicateComplexKeyRejectionDoesNotEchoTheKey() {
        // a complex key is rendered by toString() when the duplicate is reported, so it must not be reported
        ManifestValidator.Result result = ManifestValidator.validateContent(
                CONFIG_MAP + "? [\"" + VALUE_CANARY + "\", b]\n: 1\n? [\"" + VALUE_CANARY + "\", b]\n: 2\n", NAMESPACE);

        assertFalse(result.isValid());
        assertTrue(result.rejection().contains("duplicate key"), () -> "rejection=" + result.rejection());
        assertFalse(result.rejection().contains(VALUE_CANARY), () -> "rejection=" + result.rejection());
    }

    @Test
    @DisplayName("脱敏：本地 tag 名含合成标记时，拒绝信息不回显该 tag")
    void forbiddenLocalTagRejectionDoesNotEchoTheTag() {
        ManifestValidator.Result result = ManifestValidator.validateContent(
                CONFIG_MAP + "value: !" + TAG_CANARY + " payload\n", NAMESPACE);

        assertFalse(result.isValid());
        assertTrue(result.rejection().contains("forbidden tag"), () -> "rejection=" + result.rejection());
        assertFalse(result.rejection().contains(TAG_CANARY), () -> "rejection=" + result.rejection());
        assertTrue(result.rejection().contains("line "), () -> "rejection=" + result.rejection());
    }

    @Test
    @DisplayName("脱敏：全局 tag 名含合成标记时，拒绝信息不回显该 tag")
    void forbiddenGlobalTagRejectionDoesNotEchoTheTag() {
        ManifestValidator.Result result = ManifestValidator.validateContent(
                CONFIG_MAP + "value: !<tag:example.com,2020:" + TAG_CANARY + "> "
                        + VALUE_CANARY + "\n", NAMESPACE);

        assertFalse(result.isValid());
        assertTrue(result.rejection().contains("forbidden tag"), () -> "rejection=" + result.rejection());
        assertFalse(result.rejection().contains(TAG_CANARY), () -> "rejection=" + result.rejection());
        assertFalse(result.rejection().contains(VALUE_CANARY), () -> "rejection=" + result.rejection());
    }

    @Test
    @DisplayName("脱敏：结构/规模上限失败只报固定类别、异常类型与位置")
    void structureLimitFailureReportsAFixedCategory() {
        // far beyond MAX_ALIASES: the loader's own bound, not a scanner or parser failure
        String aliases = CONFIG_MAP + "shared: &shared\n  a: " + VALUE_CANARY + "\nlist:\n"
                + "  - *shared\n".repeat(60);
        ManifestValidator.Result result = ManifestValidator.validateContent(aliases, NAMESPACE);

        assertFalse(result.isValid());
        assertTrue(result.rejection().contains("structure or size limit"), () -> "rejection=" + result.rejection());
        assertFalse(result.rejection().contains(VALUE_CANARY), () -> "rejection=" + result.rejection());
        assertFalse(result.rejection().contains("CANARY"), () -> "rejection=" + result.rejection());
    }

    @Test
    @DisplayName("脱敏：!!int 无法转换的标量含合成标记时，返回受控拒绝而不是向外抛解析异常")
    void scalarConstructionFailureIsRejectedInsteadOfThrown() {
        // SnakeYAML builds !!int with Integer.valueOf and raises NumberFormatException, whose own
        // message quotes the scalar; the validator must turn that into a rejection, not let it out
        ManifestValidator.Result result = ManifestValidator.validateContent(
                CONFIG_MAP + "data:\n  token: !!int " + VALUE_CANARY + "\n", NAMESPACE);

        assertFalse(result.isValid());
        assertTrue(result.rejection().contains("scalar construction failure"), () -> "rejection=" + result.rejection());
        assertTrue(result.rejection().contains("NumberFormatException"), () -> "rejection=" + result.rejection());
        assertFalse(result.rejection().contains(VALUE_CANARY), () -> "rejection=" + result.rejection());
        assertFalse(result.rejection().contains("CANARY"), () -> "rejection=" + result.rejection());
    }

    @Test
    @DisplayName("脱敏：!!float 无法转换的标量含合成标记时，同样返回受控拒绝")
    void floatConstructionFailureIsRejectedInsteadOfThrown() {
        ManifestValidator.Result result = ManifestValidator.validateContent(
                CONFIG_MAP + "data:\n  token: !!float " + FLOAT_CANARY + "\n", NAMESPACE);

        assertFalse(result.isValid());
        assertTrue(result.rejection().contains("scalar construction failure"), () -> "rejection=" + result.rejection());
        assertTrue(result.rejection().contains("NumberFormatException"), () -> "rejection=" + result.rejection());
        assertFalse(result.rejection().contains(FLOAT_CANARY), () -> "rejection=" + result.rejection());
    }

    @Test
    @DisplayName("受控拒绝不是一刀切：可转换的标准标量仍然照常通过")
    void constructibleStandardScalarsAreStillAccepted() {
        ManifestValidator.Result result = ManifestValidator.validateContent(
                CONFIG_MAP + "data:\n  count: !!int 7\n  ratio: !!float 1.5\n  flag: !!bool true\n"
                        + "  label: !!str " + VALUE_CANARY + "\n  empty: !!null ~\n", NAMESPACE);

        assertTrue(result.isValid(), () -> "rejection=" + result.rejection());
    }

    @Test
    @DisplayName("脱敏：其他标准 tag 的构造失败同样受控（!!binary / !!seq），不止 !!int")
    void otherStandardTagConstructionFailuresAreAlsoControlled() {
        ManifestValidator.Result blob = ManifestValidator.validateContent(
                CONFIG_MAP + "data:\n  blob: !!binary " + BLOB_CANARY + "\n", NAMESPACE);
        ManifestValidator.Result sequence = ManifestValidator.validateContent(
                CONFIG_MAP + "data:\n  items: !!seq " + VALUE_CANARY + "\n", NAMESPACE);

        assertFalse(blob.isValid());
        assertFalse(sequence.isValid());
        assertTrue(blob.rejection().contains("parse failure"), () -> "rejection=" + blob.rejection());
        assertTrue(sequence.rejection().contains("parse failure"), () -> "rejection=" + sequence.rejection());
        assertFalse(blob.rejection().contains(BLOB_CANARY), () -> "rejection=" + blob.rejection());
        assertFalse(sequence.rejection().contains(VALUE_CANARY), () -> "rejection=" + sequence.rejection());
    }

    @Test
    @DisplayName("脱敏：!!timestamp 无法转换的标量同样只报固定类别，不回显该标量")
    void timestampConstructionFailureIsRejectedWithoutEchoingTheScalar() {
        // the loader wraps this one in a plain YAMLException, so only the fixed category is reported
        ManifestValidator.Result result = ManifestValidator.validateContent(
                CONFIG_MAP + "data:\n  when: !!timestamp " + VALUE_CANARY + "\n", NAMESPACE);

        assertFalse(result.isValid());
        assertTrue(result.rejection().startsWith("manifest is not valid YAML: "),
                () -> "rejection=" + result.rejection());
        assertFalse(result.rejection().contains(VALUE_CANARY), () -> "rejection=" + result.rejection());
    }

    // ---------------------------------------------------------------- deployment scope read

    @Test
    @DisplayName("deploymentNames：按文档顺序列出全部 Deployment，忽略其他 kind")
    void deploymentNamesListsDeploymentsInOrder() {
        List<String> names = ManifestValidator.deploymentNames(DEPLOYMENT + "---\n" + CONFIG_MAP + "---\n"
                + DEPLOYMENT.replace("name: web", "name: api"));

        assertEquals(List.of("web", "api"), names);
    }

    @Test
    @DisplayName("deploymentNames：没有 Deployment 时返回空列表")
    void deploymentNamesIsEmptyWithoutDeployments() {
        assertTrue(ManifestValidator.deploymentNames(CONFIG_MAP).isEmpty());
    }

    private void assertVersionRejected(String documentPrefix, String expectedReason) {
        ManifestValidator.Result result = ManifestValidator.validateContent(
                documentPrefix + "metadata: {name: a, namespace: prod}\n", NAMESPACE);
        assertFalse(result.isValid(), () -> "document was accepted: " + documentPrefix);
        assertTrue(result.rejection().contains(expectedReason),
                () -> documentPrefix + " -> " + result.rejection());
    }

    private Path write(String name, String content) throws IOException {
        Path path = tempDir.resolve(name);
        Files.writeString(path, content);
        return path;
    }
}
package com.infrahub.release.service.executor.kubernetes;

import com.infrahub.release.dto.KubernetesExecutionConfig;
import com.infrahub.release.service.executor.ReleaseExecutionContext;
import com.infrahub.release.service.executor.ReleaseExecutionResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Executor-level behaviour against synthetic child processes; kubectl is never executed.
 *
 * <p>Every released command is checked against the target binding the configuration declares before
 * it is replaced by a synthetic process, so a command that reached the process boundary without its
 * explicit {@code --kubeconfig}/{@code --context}/{@code -n} fails the test.</p>
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class KubernetesReleaseExecutorProcessTest {

    private static final KubernetesReleaseExecutor.DiagnosticBudget FAST_DIAGNOSTIC_BUDGET =
            new KubernetesReleaseExecutor.DiagnosticBudget(Duration.ofSeconds(1), Duration.ofSeconds(2));

    /** Reclamation is allowed on top of the work itself; see {@link KubectlProcessRunner#CLEANUP_GRACE}. */
    private static final long CLEANUP_GRACE_MILLIS = KubectlProcessRunner.CLEANUP_GRACE.toMillis();

    /** Scheduling slack only; must stay small compared to the bounds it qualifies. */
    private static final long SCHEDULING_TOLERANCE_MILLIS = 1500L;

    private static final String NAMESPACE = "prod";
    private static final String KUBE_CONTEXT = "bound-context";

    /** Credential material that must never be read, logged or returned by the executor. */
    private static final String KUBECONFIG_CANARY = "CANARY-KUBECONFIG-TOKEN-7788";

    private static final String KUBECONFIG_BODY = """
            apiVersion: v1
            kind: Config
            users:
            - name: bound
              user:
                token: CANARY-KUBECONFIG-TOKEN-7788
            """;

    /** One Deployment to watch plus one ConfigMap, both explicitly in the configured namespace. */
    private static final String MANIFEST = """
            apiVersion: apps/v1
            kind: Deployment
            metadata:
              name: web
              namespace: prod
            ---
            apiVersion: v1
            kind: ConfigMap
            metadata:
              name: web-config
              namespace: prod
            """;

    /** The three complete resource types this executor accepts, with one Deployment to watch. */
    private static final String ALL_TYPES_MANIFEST = """
            apiVersion: apps/v1
            kind: Deployment
            metadata:
              name: web
              namespace: prod
            ---
            apiVersion: v1
            kind: Service
            metadata:
              name: web-svc
              namespace: prod
            ---
            apiVersion: v1
            kind: ConfigMap
            metadata:
              name: web-config
              namespace: prod
            """;

    /** Synthetic markers only; no real Secret, cluster or kubeconfig value is used for these. */
    private static final String KEY_CANARY = "CANARY-DUPLICATE-KEY-3141";
    private static final String TAG_CANARY = "CANARY-TAG-5319";
    private static final String VALUE_CANARY = "CANARY-VALUE-2718";
    /** A value no numeric standard tag can convert, used for the scalar-construction counter-examples. */
    private static final String FLOAT_CANARY = "CANARY-FLOAT-8842";

    @TempDir
    Path tempDir;

    private final ScriptedExecutor executor = new ScriptedExecutor();

    /** Work time plus one bounded reclamation window per command that actually ran. */
    private static long boundedMillis(long workMillis, int commands) {
        return workMillis + commands * CLEANUP_GRACE_MILLIS + SCHEDULING_TOLERANCE_MILLIS;
    }

    private ReleaseExecutionContext context(KubernetesExecutionConfig config) {
        return ReleaseExecutionContext.builder().executorConfig(config).build();
    }

    // ---------------------------------------------------------------- preserved behaviour

    @Test
    @DisplayName("成功路径（无 workloadName）不声称 rollout 已验证")
    void successfulApplyWithoutWorkloadClaimsNoReadiness() {
        executor.applyMode = "ok";

        ReleaseExecutionResult result = executor.execute(context(manifestPathConfig()));

        assertTrue(result.isSuccess(), () -> "message=" + result.getMessage());
        assertEquals("Kubernetes apply succeeded; workload readiness was not verified (workloadName is not configured)",
                result.getMessage());
        assertTrue(result.getStdout().contains("APPLY:"), () -> "stdout=" + result.getStdout());
        assertNotNull(result.getStartedAt());
        assertNotNull(result.getEndedAt());
        assertEquals(List.of("apply"), executor.issuedStages());
        assertEquals(1, executor.builtCommands().size());
        assertEquals(List.of("kubectl", "--kubeconfig", kubeconfigPath(), "--context", KUBE_CONTEXT, "-n", NAMESPACE,
                "apply", "-f", "-"), executor.builtCommands().get(0));
    }

    @Test
    @DisplayName("成功路径（apply + rollout）依次执行两条命令，并声称 rollout 已验证")
    void successfulApplyAndRolloutRunBothCommands() {
        executor.applyMode = "ok";
        executor.rolloutMode = "ok";

        ReleaseExecutionResult result = executor.execute(context(workloadConfig()));

        assertTrue(result.isSuccess(), () -> "message=" + result.getMessage());
        assertEquals("Kubernetes apply and rollout verification succeeded", result.getMessage());
        assertTrue(result.getStdout().contains("APPLY:"));
        assertTrue(result.getStdout().contains("ROLLOUT:"));
        assertEquals(List.of("apply", "rollout"), executor.issuedStages());
        assertEquals(List.of("kubectl", "--kubeconfig", kubeconfigPath(), "--context", KUBE_CONTEXT, "-n", NAMESPACE,
                "rollout", "status", "deployment/web", "--timeout=300s"), executor.builtCommands().get(1));
    }

    @Test
    @DisplayName("完整类型快照（apps/v1 Deployment + v1 Service + v1 ConfigMap）端到端通过，并核对 stdin 字节数")
    void completeTypesSnapshotRunsEndToEnd() {
        executor.applyMode = "stdin-eof";
        executor.rolloutMode = "ok";
        KubernetesExecutionConfig config = workloadConfig();
        config.setManifestPath(writeManifestFile("all-types.yaml", ALL_TYPES_MANIFEST));

        ReleaseExecutionResult result = executor.execute(context(config));

        assertTrue(result.isSuccess(), () -> "message=" + result.getMessage());
        assertEquals("Kubernetes apply and rollout verification succeeded", result.getMessage());
        assertTrue(result.getStdout().contains("STDIN_BYTES=" + ALL_TYPES_MANIFEST.getBytes(StandardCharsets.UTF_8).length),
                () -> "stdin did not carry the whole snapshot: " + result.getStdout());
        assertEquals(List.of("apply", "rollout"), executor.issuedStages());
        assertEquals(List.of("kubectl", "--kubeconfig", kubeconfigPath(), "--context", KUBE_CONTEXT, "-n", NAMESPACE,
                "rollout", "status", "deployment/web", "--timeout=300s"), executor.builtCommands().get(1));
    }

    @Test
    @DisplayName("manifestPath 优先：manifestContent 即便无效也不会被读取")
    void manifestPathTakesPrecedenceOverManifestContent() {
        executor.applyMode = "echo-stdin";
        KubernetesExecutionConfig config = manifestPathConfig();
        // rejected on its own content rules - it can only pass if the path was used and this was never read
        config.setManifestContent("not-a-kubernetes-object");

        ReleaseExecutionResult result = executor.execute(context(config));

        assertTrue(result.isSuccess(), () -> "message=" + result.getMessage());
        assertTrue(result.getStdout().contains("STDIN:" + MANIFEST), () -> "stdout=" + result.getStdout());
        assertFalse(result.getStdout().contains("not-a-kubernetes-object"),
                () -> "manifestContent reached stdin: " + result.getStdout());
    }

    @Test
    @DisplayName("校验与 stdin 收到的是同一份内容：按字节数核对快照")
    void stdinReceivesExactlyTheValidatedSnapshot() {
        executor.applyMode = "stdin-eof";
        KubernetesExecutionConfig config = manifestPathConfig();

        ReleaseExecutionResult result = executor.execute(context(config));

        int expectedBytes = MANIFEST.getBytes(StandardCharsets.UTF_8).length;
        assertTrue(result.isSuccess(), () -> "message=" + result.getMessage());
        assertTrue(result.getStdout().contains("STDIN_BYTES=" + expectedBytes),
                () -> "stdin did not carry the whole snapshot (" + expectedBytes + " bytes): " + result.getStdout());
    }

    @Test
    @DisplayName("仅配置 manifestContent 时仍写入 stdin")
    void manifestContentIsWrittenToStdinWhenNoPath() {
        executor.applyMode = "stdin-eof";
        KubernetesExecutionConfig config = KubernetesExecutionConfig.builder()
                .kubeconfigPath(kubeconfigPath())
                .kubeContext(KUBE_CONTEXT)
                .namespace(NAMESPACE)
                .manifestContent(MANIFEST)
                .build();

        ReleaseExecutionResult result = executor.execute(context(config));

        assertTrue(result.isSuccess(), () -> "message=" + result.getMessage());
        assertTrue(result.getStdout().contains("STDIN_BYTES=" + MANIFEST.getBytes(StandardCharsets.UTF_8).length),
                () -> "stdout=" + result.getStdout());
    }

    @Test
    @DisplayName("kubeconfig 内容从不被读取或输出")
    void kubeconfigContentIsNeverReadOrReported() {
        executor.applyMode = "fail:4:APPLY-ERR";
        KubernetesExecutionConfig config = manifestPathConfig();
        Path kubeconfig = Path.of(config.getKubeconfigPath());
        assertEquals(KUBECONFIG_BODY, readFile(kubeconfig));

        ReleaseExecutionResult result = executor.execute(context(config));

        assertFalse(result.isSuccess());
        assertFalse(result.getMessage().contains(KUBECONFIG_CANARY), () -> "message=" + result.getMessage());
        assertFalse(result.getStdout().contains(KUBECONFIG_CANARY), () -> "stdout=" + result.getStdout());
        assertFalse(result.getStderr().contains(KUBECONFIG_CANARY), () -> "stderr=" + result.getStderr());
    }

    @Test
    @DisplayName("apply 非零退出：保留 stdout/stderr、exit code，且不继续后续命令")
    void applyFailurePreservesStreamsAndExitCode() {
        executor.applyMode = "fail:3:APPLY-ERR";

        ReleaseExecutionResult result = executor.execute(context(workloadConfig()));

        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("kubectl apply"), () -> "message=" + result.getMessage());
        assertTrue(result.getMessage().contains("exit code 3"), () -> "message=" + result.getMessage());
        assertTrue(result.getMessage().contains("APPLY-ERR"), () -> "message=" + result.getMessage());
        assertTrue(result.getStdout().contains("OUT-OK"), () -> "stdout=" + result.getStdout());
        assertTrue(result.getStderr().contains("APPLY-ERR"), () -> "stderr=" + result.getStderr());
        assertEquals(List.of("apply"), executor.issuedStages());
    }

    @Test
    @DisplayName("apply 超时：有界失败且区分单命令超时与整个发布耗时")
    void applyTimeoutIsReportedAsPerCommandTimeout() {
        executor.applyMode = "hang";
        KubernetesExecutionConfig config = manifestPathConfig();
        config.setRolloutTimeoutSeconds(1);

        long start = System.nanoTime();
        ReleaseExecutionResult result = executor.execute(context(config));
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("kubectl apply timed out after 1s"),
                () -> "message=" + result.getMessage());
        assertTrue(result.getMessage().contains("per-command timeout"),
                () -> "message=" + result.getMessage());
        assertTrue(result.getMessage().contains("not a total release deadline"),
                () -> "message=" + result.getMessage());
        assertTrue(elapsedMillis < boundedMillis(1_000, 1),
                () -> "timeout was not bounded, elapsed=" + elapsedMillis + "ms");
        assertEquals(List.of("apply"), executor.issuedStages());
    }

    @Test
    @DisplayName("rollout 失败且日志采集失败：留下诊断并保留原始发布失败")
    void rolloutFailureRecordsLogCollectionFailureAndKeepsOriginalFailure() {
        executor.applyMode = "ok";
        executor.rolloutMode = "fail:1:ROLLOUT-ERR";
        executor.podsMode = "fail:1:PODS-ERR";

        ReleaseExecutionResult result = executor.execute(context(workloadConfig()));

        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("kubectl rollout status"),
                () -> "message=" + result.getMessage());
        assertTrue(result.getMessage().contains("ROLLOUT-ERR"),
                () -> "original rollout failure was replaced: " + result.getMessage());

        String stdout = result.getStdout();
        String stderr = result.getStderr();
        assertTrue(stderr.contains("ROLLOUT ERR"), () -> "stderr=" + stderr);
        assertTrue(stdout.contains("GET PODS (exit code 1)"), () -> "stdout=" + stdout);
        assertTrue(stderr.contains("PODS-ERR"), () -> "stderr=" + stderr);
        assertTrue(stderr.contains("GET PODS FAILED"), () -> "stderr=" + stderr);
        assertEquals(List.of("apply", "rollout", "get-pods"), executor.issuedStages());
    }

    @Test
    @DisplayName("rollout 失败：逐 Pod 日志成功/失败均被记录")
    void rolloutFailureRecordsPerPodLogOutcome() {
        executor.applyMode = "ok";
        executor.rolloutMode = "fail:1:ROLLOUT-ERR";
        executor.podsMode = "print:pod-a pod-b";
        executor.logsMode = podName -> "pod-a".equals(podName) ? "fail:1:LOGS-ERR" : "print:LOGS-OK-" + podName;

        ReleaseExecutionResult result = executor.execute(context(workloadConfig()));

        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("ROLLOUT-ERR"), () -> "message=" + result.getMessage());

        String stdout = result.getStdout();
        String stderr = result.getStderr();
        assertTrue(stdout.contains("LOGS for pod-a (exit code 1)"), () -> "stdout=" + stdout);
        assertTrue(stdout.contains("LOGS for pod-b (exit code 0)"), () -> "stdout=" + stdout);
        assertTrue(stdout.contains("LOGS-OK-pod-b"), () -> "stdout=" + stdout);
        assertTrue(stderr.contains("kubectl logs pod-a"), () -> "stderr=" + stderr);
        assertTrue(stderr.contains("LOGS-ERR"), () -> "stderr=" + stderr);
        assertEquals(List.of("apply", "rollout", "get-pods", "logs:pod-a", "logs:pod-b"), executor.issuedStages());
    }

    @Test
    @DisplayName("日志采集单条命令超时有界，且不改变原始发布失败")
    void podLogCollectionCommandTimeoutIsBounded() {
        executor.applyMode = "ok";
        executor.rolloutMode = "fail:1:ROLLOUT-ERR";
        executor.podsMode = "hang";
        executor.budget = FAST_DIAGNOSTIC_BUDGET;

        long start = System.nanoTime();
        ReleaseExecutionResult result = executor.execute(context(workloadConfig()));
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("ROLLOUT-ERR"), () -> "message=" + result.getMessage());
        assertTrue(result.getStderr().contains("GET PODS FAILED"), () -> "stderr=" + result.getStderr());
        assertTrue(result.getStderr().contains("timed out after 1s"), () -> "stderr=" + result.getStderr());
        assertTrue(elapsedMillis < boundedMillis(2_000, 3),
                () -> "diagnostics were not bounded, elapsed=" + elapsedMillis + "ms");
        assertEquals(List.of("apply", "rollout", "get-pods"), executor.issuedStages());
    }

    @Test
    @DisplayName("总预算耗尽后不再启动新诊断命令，耗时符合预算加清理宽限")
    void podLogCollectionStopsWhenTotalBudgetIsExhausted() {
        executor.applyMode = "ok";
        executor.rolloutMode = "fail:1:ROLLOUT-ERR";
        executor.podsMode = "print:pod-a pod-b pod-c";
        executor.logsMode = podName -> "hang";
        executor.budget = new KubernetesReleaseExecutor.DiagnosticBudget(Duration.ofSeconds(30), Duration.ofMillis(2_000));

        long start = System.nanoTime();
        ReleaseExecutionResult result = executor.execute(context(workloadConfig()));
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("ROLLOUT-ERR"), () -> "message=" + result.getMessage());
        // the first log command consumed the whole 2s budget, so the remaining pods must be skipped
        assertEquals(List.of("apply", "rollout", "get-pods", "logs:pod-a"), executor.issuedStages());
        assertTrue(elapsedMillis < boundedMillis(2_600, 4),
                () -> "diagnostics exceeded the budget plus cleanup grace, elapsed=" + elapsedMillis + "ms");
    }

    @Test
    @DisplayName("诊断剩余不足一秒时按毫秒执行，不被向上取整到整秒")
    void diagnosticSubSecondRemainderIsNotRoundedUp() {
        executor.applyMode = "ok";
        executor.rolloutMode = "fail:1:ROLLOUT-ERR";
        executor.podsMode = "print:pod-a pod-b";
        executor.logsMode = podName -> "pod-a".equals(podName) ? "print-later:4700:LOGS-A" : "hang";
        executor.budget = new KubernetesReleaseExecutor.DiagnosticBudget(Duration.ofSeconds(30), Duration.ofMillis(6_000));

        long start = System.nanoTime();
        ReleaseExecutionResult result = executor.execute(context(workloadConfig()));
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        String stderr = result.getStderr();
        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("ROLLOUT-ERR"), () -> "message=" + result.getMessage());
        assertEquals(List.of("apply", "rollout", "get-pods", "logs:pod-a", "logs:pod-b"), executor.issuedStages());

        String podBLine = stderr.lines()
                .filter(line -> line.contains("kubectl logs pod-b"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no diagnostic for pod-b in: " + stderr));
        assertTrue(podBLine.contains("timed out after"), () -> "pod-b line=" + podBLine);
        assertTrue(Pattern.compile("timed out after \\d+ms").matcher(podBLine).find(),
                () -> "sub-second remainder was not reported in milliseconds: " + podBLine);
        assertFalse(Pattern.compile("timed out after \\d+s").matcher(podBLine).find(),
                () -> "sub-second remainder was rounded up to a whole second: " + podBLine);
        assertTrue(elapsedMillis < boundedMillis(6_600, 5),
                () -> "diagnostics exceeded the budget plus cleanup grace, elapsed=" + elapsedMillis + "ms");
    }

    @Test
    @DisplayName("子进程已退出但输出未结束：诊断命令按同一 deadline 失败并有界回收")
    void diagnosticIsBoundedWhenChildExitsButItsOutputNeverEnds() {
        Path pidFile = tempDir.resolve("holder-pid.txt");
        executor.applyMode = "ok";
        executor.rolloutMode = "fail:1:ROLLOUT-ERR";
        executor.podsMode = "spawn-holder:" + pidFile;
        executor.budget = new KubernetesReleaseExecutor.DiagnosticBudget(Duration.ofSeconds(2), Duration.ofSeconds(5));

        try {
            long start = System.nanoTime();
            ReleaseExecutionResult result = executor.execute(context(workloadConfig()));
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            assertFalse(result.isSuccess());
            assertTrue(result.getMessage().contains("ROLLOUT-ERR"), () -> "message=" + result.getMessage());
            assertTrue(result.getStdout().contains("SPAWNED"), () -> "stdout=" + result.getStdout());
            assertTrue(result.getStderr().contains("GET PODS FAILED"),
                    () -> "a command whose output never ended was not reported as failed: " + result.getStderr());
            assertTrue(result.getStderr().contains("timed out after"),
                    () -> "stderr=" + result.getStderr());
            assertEquals(List.of("apply", "rollout", "get-pods"), executor.issuedStages());
            assertTrue(elapsedMillis < boundedMillis(2_600, 3),
                    () -> "diagnostics were not bounded, elapsed=" + elapsedMillis + "ms");
        } finally {
            SyntheticProcessFixture.releaseHolder(pidFile);
        }
    }

    @Test
    @DisplayName("中断：停止后续命令、保留中断标志并回收子进程")
    void interruptionStopsSubsequentCommandsAndReclaimsProcess() throws Exception {
        Path pidFile = tempDir.resolve("pid.txt");
        executor.applyMode = "hang-pid:" + pidFile;
        executor.rolloutMode = "ok";

        KubernetesExecutionConfig config = workloadConfig();
        config.setRolloutTimeoutSeconds(120);

        AtomicReference<ReleaseExecutionResult> outcome = new AtomicReference<>();
        AtomicBoolean interruptFlagRestored = new AtomicBoolean();
        Thread thread = new Thread(() -> {
            outcome.set(executor.execute(context(config)));
            interruptFlagRestored.set(Thread.currentThread().isInterrupted());
        }, "executor-interrupt-test");
        thread.setDaemon(true);
        thread.start();

        long pid = SyntheticProcessFixture.awaitPidFile(pidFile, 30_000);
        Thread.sleep(300L);
        thread.interrupt();
        thread.join(15_000);

        try {
            assertFalse(thread.isAlive(), "executor did not return after interruption");
            ReleaseExecutionResult result = outcome.get();
            assertNotNull(result);
            assertFalse(result.isSuccess());
            assertTrue(result.getMessage().contains("interrupted"), () -> "message=" + result.getMessage());
            assertEquals(List.of("apply"), executor.issuedStages());
            assertTrue(interruptFlagRestored.get(), "executor must restore the interrupt flag");
            SyntheticProcessFixture.awaitProcessGone(pid, 10_000);
        } finally {
            // never leak the child when an assertion above fails
            SyntheticProcessFixture.releaseHolder(pidFile);
        }
    }

    // ---------------------------------------------------------------- rejections before any process

    @Test
    @DisplayName("缺 kubeconfigPath：零子进程")
    void missingKubeconfigPathStartsNoProcess() {
        KubernetesExecutionConfig config = manifestPathConfig();
        config.setKubeconfigPath(null);

        ReleaseExecutionResult result = executor.execute(context(config));

        assertRejectedByConfiguration(result, "kubeconfigPath");
    }

    @Test
    @DisplayName("缺 kubeContext：零子进程")
    void missingKubeContextStartsNoProcess() {
        KubernetesExecutionConfig config = manifestPathConfig();
        config.setKubeContext(null);

        ReleaseExecutionResult result = executor.execute(context(config));

        assertRejectedByConfiguration(result, "kubeContext");
    }

    @Test
    @DisplayName("缺 namespace：零子进程")
    void missingNamespaceStartsNoProcess() {
        KubernetesExecutionConfig config = manifestPathConfig();
        config.setNamespace(null);

        ReleaseExecutionResult result = executor.execute(context(config));

        assertRejectedByConfiguration(result, "namespace");
    }

    @Test
    @DisplayName("kubeconfig 文件不存在：零子进程")
    void nonexistentKubeconfigFileStartsNoProcess() {
        KubernetesExecutionConfig config = manifestPathConfig();
        config.setKubeconfigPath(tempDir.resolve("absent.kubeconfig").toString());

        ReleaseExecutionResult result = executor.execute(context(config));

        assertRejectedBeforeExecution(result, "kubeconfigPath does not exist");
    }

    @Test
    @DisplayName("kubeconfig 路径为相对路径：零子进程")
    void relativeKubeconfigPathStartsNoProcess() {
        KubernetesExecutionConfig config = manifestPathConfig();
        config.setKubeconfigPath("relative/target.kubeconfig");

        ReleaseExecutionResult result = executor.execute(context(config));

        assertRejectedBeforeExecution(result, "kubeconfigPath must be an absolute path");
    }

    @Test
    @DisplayName("manifest 文件不存在：零子进程")
    void missingManifestFileStartsNoProcess() {
        KubernetesExecutionConfig config = manifestPathConfig();
        config.setManifestPath(tempDir.resolve("absent.yaml").toString());

        ReleaseExecutionResult result = executor.execute(context(config));

        assertRejectedBeforeExecution(result, "manifestPath does not exist");
    }

    @Test
    @DisplayName("manifest 路径为相对路径：零子进程")
    void relativeManifestPathStartsNoProcess() {
        KubernetesExecutionConfig config = manifestPathConfig();
        config.setManifestPath("relative/app.yaml");

        ReleaseExecutionResult result = executor.execute(context(config));

        assertRejectedBeforeExecution(result, "manifestPath must be an absolute path");
    }

    @Test
    @DisplayName("无效 YAML：零子进程，且没有任何子进程留下痕迹")
    void invalidYamlStartsNoProcess() {
        KubernetesExecutionConfig config = manifestPathConfig();
        config.setManifestPath(writeManifestFile("broken.yaml", "kind: ConfigMap\n  metadata:\n x: 1\n"));

        ReleaseExecutionResult result = executor.execute(context(config));

        assertRejectedBeforeExecution(result, "manifest is not valid YAML");
    }

    @Test
    @DisplayName("被拒绝的发布不留下任何子进程：以子进程自报 pid 为直接证据")
    void rejectedReleaseLeavesNoChildProcessBehind() throws Exception {
        Path pidFile = tempDir.resolve("never-started.pid");
        // if even one child had started, this mode would report its pid before anything else
        executor.applyMode = "hang-pid:" + pidFile;
        executor.rolloutMode = "hang-pid:" + pidFile;
        KubernetesExecutionConfig config = workloadConfig();
        config.setNamespace("other-ns");

        try {
            ReleaseExecutionResult result = executor.execute(context(config));

            assertRejectedBeforeExecution(result, "is scoped to namespace");
            assertFalse(awaitPidFileAppears(pidFile, 1_500), "a child process was started for a rejected release");
        } finally {
            SyntheticProcessFixture.releaseHolder(pidFile);
        }
    }

    @Test
    @DisplayName("跨 namespace 对象：零子进程")
    void crossNamespaceManifestStartsNoProcess() {
        KubernetesExecutionConfig config = manifestPathConfig();
        config.setManifestPath(writeManifestFile("other-ns.yaml", MANIFEST.replace("namespace: prod", "namespace: staging")));

        ReleaseExecutionResult result = executor.execute(context(config));

        assertRejectedBeforeExecution(result, "is scoped to namespace 'staging'");
    }

    @Test
    @DisplayName("cluster-scoped 对象（无显式 namespace）：零子进程")
    void clusterScopedResourceStartsNoProcess() {
        KubernetesExecutionConfig config = manifestPathConfig();
        config.setManifestPath(writeManifestFile("cluster.yaml",
                "apiVersion: v1\nkind: ConfigMap\nmetadata:\n  name: web-config\n"));

        ReleaseExecutionResult result = executor.execute(context(config));

        assertRejectedBeforeExecution(result, "has no explicit 'metadata.namespace'");
    }

    @Test
    @DisplayName("未允许的 kind（Secret）：零子进程且不回显其内容")
    void disallowedKindStartsNoProcess() {
        KubernetesExecutionConfig config = manifestPathConfig();
        config.setManifestPath(writeManifestFile("secret.yaml", """
                apiVersion: v1
                kind: Secret
                metadata:
                  name: web-secret
                  namespace: prod
                data:
                  password: CANARY-SECRET-VALUE-4411
                """));

        ReleaseExecutionResult result = executor.execute(context(config));

        assertRejectedBeforeExecution(result, "which this executor does not allow");
        assertFalse(result.getStdout().contains("CANARY-SECRET-VALUE-4411"), () -> "stdout=" + result.getStdout());
        assertFalse(result.getStderr().contains("CANARY-SECRET-VALUE-4411"), () -> "stderr=" + result.getStderr());
    }

    @Test
    @DisplayName("List 对象：零子进程")
    void listKindStartsNoProcess() {
        KubernetesExecutionConfig config = manifestPathConfig();
        config.setManifestPath(writeManifestFile("list.yaml",
                "apiVersion: v1\nkind: List\nmetadata:\n  name: batch\n  namespace: prod\nitems: []\n"));

        ReleaseExecutionResult result = executor.execute(context(config));

        assertRejectedBeforeExecution(result, "does not allow");
    }

    @Test
    @DisplayName("重复对象身份：零子进程")
    void duplicateObjectStartsNoProcess() {
        KubernetesExecutionConfig config = manifestPathConfig();
        config.setManifestPath(writeManifestFile("duplicate.yaml", MANIFEST + "---\n" + MANIFEST));

        ReleaseExecutionResult result = executor.execute(context(config));

        assertRejectedBeforeExecution(result, "more than once");
    }

    @Test
    @DisplayName("workloadName 与 manifest 中的 Deployment 不一致：零子进程")
    void workloadNameMismatchStartsNoProcess() {
        KubernetesExecutionConfig config = workloadConfig();
        config.setWorkloadName("api");

        ReleaseExecutionResult result = executor.execute(context(config));

        assertRejectedBeforeExecution(result, "is not the Deployment declared by the manifest");
    }

    @Test
    @DisplayName("manifest 无 Deployment 却配置了 workloadName：零子进程")
    void workloadNameWithoutDeploymentStartsNoProcess() {
        KubernetesExecutionConfig config = workloadConfig();
        config.setManifestPath(writeManifestFile("configmap-only.yaml", """
                apiVersion: v1
                kind: ConfigMap
                metadata:
                  name: web-config
                  namespace: prod
                """));

        ReleaseExecutionResult result = executor.execute(context(config));

        assertRejectedBeforeExecution(result, "declares no Deployment");
    }

    @Test
    @DisplayName("manifest 含多个 Deployment，单个 workloadName 无法表达验收目标：零子进程")
    void multipleDeploymentsAreRejectedAsAnUnrepresentableScope() {
        KubernetesExecutionConfig config = workloadConfig();
        config.setManifestPath(writeManifestFile("two-deployments.yaml", MANIFEST + "---\n" + """
                apiVersion: apps/v1
                kind: Deployment
                metadata:
                  name: api
                  namespace: prod
                """));

        ReleaseExecutionResult result = executor.execute(context(config));

        assertRejectedBeforeExecution(result, "cannot express the acceptance target");
    }

    // ---------------------------------------------------------------- complete-type policy

    @Test
    @DisplayName("缺少 apiVersion：零子进程，且拒绝发生在构造任何命令之前")
    void missingApiVersionStartsNoProcess() {
        KubernetesExecutionConfig config = workloadConfig();
        config.setManifestPath(writeManifestFile("no-version.yaml", """
                kind: Deployment
                metadata:
                  name: web
                  namespace: prod
                """));

        ReleaseExecutionResult result = executor.execute(context(config));

        assertRejectedBeforeExecution(result, "no non-empty 'apiVersion'");
    }

    @Test
    @DisplayName("版本错误（v1 Deployment）：零子进程，且拒绝发生在构造任何命令之前")
    void wrongVersionStartsNoProcess() {
        KubernetesExecutionConfig config = workloadConfig();
        config.setManifestPath(writeManifestFile("wrong-version.yaml", """
                apiVersion: v1
                kind: Deployment
                metadata:
                  name: web
                  namespace: prod
                """));

        ReleaseExecutionResult result = executor.execute(context(config));

        assertRejectedBeforeExecution(result, "must declare apiVersion 'apps/v1'");
    }

    @Test
    @DisplayName("自定义 API 组：零子进程，且拒绝发生在构造任何命令之前")
    void customApiGroupStartsNoProcess() {
        KubernetesExecutionConfig config = manifestPathConfig();
        config.setManifestPath(writeManifestFile("custom-group.yaml", """
                apiVersion: example.io/v1
                kind: ConfigMap
                metadata:
                  name: web-config
                  namespace: prod
                """));

        ReleaseExecutionResult result = executor.execute(context(config));

        assertRejectedBeforeExecution(result, "must declare apiVersion 'v1'");
    }

    @Test
    @DisplayName("kind 与 apiVersion 不匹配：零子进程，且拒绝发生在构造任何命令之前")
    void mismatchedKindAndVersionStartsNoProcess() {
        KubernetesExecutionConfig config = manifestPathConfig();
        config.setManifestPath(writeManifestFile("mismatched.yaml", """
                apiVersion: apps/v1
                kind: ConfigMap
                metadata:
                  name: web-config
                  namespace: prod
                """));

        ReleaseExecutionResult result = executor.execute(context(config));

        assertRejectedBeforeExecution(result, "must declare apiVersion 'v1'");
    }

    @Test
    @DisplayName("类型被拒绝的发布不留下任何子进程：以子进程自报 pid 为直接证据")
    void typeRejectionLeavesNoChildProcessBehind() throws Exception {
        Path pidFile = tempDir.resolve("never-started-type.pid");
        // if even one child had started, this mode would report its pid before anything else
        executor.applyMode = "hang-pid:" + pidFile;
        executor.rolloutMode = "hang-pid:" + pidFile;
        KubernetesExecutionConfig config = workloadConfig();
        config.setManifestPath(writeManifestFile("wrong-version-pid.yaml", """
                apiVersion: v1
                kind: Deployment
                metadata:
                  name: web
                  namespace: prod
                """));

        try {
            ReleaseExecutionResult result = executor.execute(context(config));

            assertRejectedBeforeExecution(result, "must declare apiVersion 'apps/v1'");
            assertFalse(awaitPidFileAppears(pidFile, 1_500), "a child process was started for a rejected release");
        } finally {
            SyntheticProcessFixture.releaseHolder(pidFile);
        }
    }

    // ---------------------------------------------------------------- rejection desensitization

    @Test
    @DisplayName("重复键名含合成标记：零子进程，message/stdout/stderr 均不出现该标记")
    void duplicateKeyRejectionNeverEchoesTheManifestKey() {
        KubernetesExecutionConfig config = manifestPathConfig();
        config.setManifestPath(writeManifestFile("duplicate-key.yaml",
                ALL_TYPES_MANIFEST + KEY_CANARY + ": a\n" + KEY_CANARY + ": b\n"));

        ReleaseExecutionResult result = executor.execute(context(config));

        assertRejectedBeforeExecution(result, "duplicate key");
        assertCanaryNeverEscapes(result, KEY_CANARY);
    }

    @Test
    @DisplayName("tag 名含合成标记：零子进程，诊断中不出现该标记或它挂的值")
    void forbiddenTagRejectionNeverEchoesTheTagOrItsValue() {
        KubernetesExecutionConfig config = manifestPathConfig();
        config.setManifestPath(writeManifestFile("tagged.yaml",
                ALL_TYPES_MANIFEST + "value: !" + TAG_CANARY + " " + VALUE_CANARY + "\n"));

        ReleaseExecutionResult result = executor.execute(context(config));

        assertRejectedBeforeExecution(result, "forbidden tag");
        assertCanaryNeverEscapes(result, TAG_CANARY);
        assertCanaryNeverEscapes(result, VALUE_CANARY);
    }

    @Test
    @DisplayName("语法错误含合成标记：零子进程，且只报告类别与位置")
    void syntaxErrorRejectionNeverEchoesTheManifestValue() {
        KubernetesExecutionConfig config = manifestPathConfig();
        config.setManifestPath(writeManifestFile("syntax-error.yaml",
                ALL_TYPES_MANIFEST + "token: \"" + VALUE_CANARY + "\n"));

        ReleaseExecutionResult result = executor.execute(context(config));

        assertRejectedBeforeExecution(result, "parse failure");
        assertCanaryNeverEscapes(result, VALUE_CANARY);
    }

    @Test
    @DisplayName("标量转换失败（!!int 含合成标记）：零命令零子进程，三个输出字段均不出现该标记")
    void scalarConstructionFailureNeverEchoesTheScalar() {
        KubernetesExecutionConfig config = manifestPathConfig();
        config.setManifestPath(writeManifestFile("bad-int.yaml",
                ALL_TYPES_MANIFEST + "data:\n  token: !!int " + VALUE_CANARY + "\n"));

        ReleaseExecutionResult result = executor.execute(context(config));

        assertRejectedBeforeExecution(result, "scalar construction failure");
        assertCanaryNeverEscapes(result, VALUE_CANARY);
    }

    @Test
    @DisplayName("标量转换失败（!!float 含合成标记）：拒绝先于命令构造，且不留下任何子进程")
    void floatConstructionFailureLeavesNoChildProcessBehind() throws Exception {
        Path pidFile = tempDir.resolve("never-started-scalar.pid");
        // if even one child had started, this mode would report its pid before anything else
        executor.applyMode = "hang-pid:" + pidFile;
        executor.rolloutMode = "hang-pid:" + pidFile;
        KubernetesExecutionConfig config = manifestPathConfig();
        config.setManifestPath(writeManifestFile("bad-float.yaml",
                ALL_TYPES_MANIFEST + "data:\n  token: !!float " + FLOAT_CANARY + "\n"));

        try {
            ReleaseExecutionResult result = executor.execute(context(config));

            assertRejectedBeforeExecution(result, "scalar construction failure");
            assertCanaryNeverEscapes(result, FLOAT_CANARY);
            assertFalse(awaitPidFileAppears(pidFile, 1_500), "a child process was started for a rejected release");
        } finally {
            SyntheticProcessFixture.releaseHolder(pidFile);
        }
    }

    // ---------------------------------------------------------------- helpers

    /**
     * A rejection must be locatable in the message, must name no command and must not claim success.
     */
    private void assertNoCommandWasBuilt(ReleaseExecutionResult result, String expectedReason) {
        assertFalse(result.isSuccess(), () -> "release was not rejected: " + result.getMessage());
        assertTrue(result.getMessage().contains(expectedReason),
                () -> "expected '" + expectedReason + "' in message but was: " + result.getMessage());
        assertNotNull(result.getStartedAt());
        assertNotNull(result.getEndedAt());
        assertEquals(List.of(), executor.issuedStages(), "a command was built for a rejected release");
    }

    /**
     * Rejection by the configuration gate: no log stream exists yet, because nothing was attempted.
     */
    private void assertRejectedByConfiguration(ReleaseExecutionResult result, String expectedReason) {
        assertNoCommandWasBuilt(result, expectedReason);
    }

    /**
     * Rejection by the pre-flight checks, which also leaves a locatable line in the release log.
     */
    private void assertRejectedBeforeExecution(ReleaseExecutionResult result, String expectedReason) {
        assertNoCommandWasBuilt(result, expectedReason);
        assertTrue(result.getStderr().contains("REJECTED BEFORE EXECUTION"),
                () -> "no rejection diagnostic in stderr: " + result.getStderr());
    }

    /**
     * A synthetic marker inside a manifest must never reach a release result: not through the
     * message, and not through either stream of the release log.
     */
    private void assertCanaryNeverEscapes(ReleaseExecutionResult result, String canary) {
        assertFalse(result.getMessage().contains(canary), () -> "message=" + result.getMessage());
        assertFalse(result.getStdout().contains(canary), () -> "stdout=" + result.getStdout());
        assertFalse(result.getStderr().contains(canary), () -> "stderr=" + result.getStderr());
    }

    private KubernetesExecutionConfig manifestPathConfig() {
        return KubernetesExecutionConfig.builder()
                .kubeconfigPath(kubeconfigPath())
                .kubeContext(KUBE_CONTEXT)
                .namespace(NAMESPACE)
                .manifestPath(writeManifestFile("app.yaml", MANIFEST))
                .build();
    }

    private KubernetesExecutionConfig workloadConfig() {
        KubernetesExecutionConfig config = manifestPathConfig();
        config.setWorkloadName("web");
        return config;
    }

    private String kubeconfigPath() {
        return writeFile("target.kubeconfig", KUBECONFIG_BODY).toString();
    }

    private String writeManifestFile(String name, String content) {
        return writeFile(name, content).toString();
    }

    private Path writeFile(String name, String content) {
        Path path = tempDir.resolve(name);
        try {
            Files.writeString(path, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return path;
    }

    private static String readFile(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean awaitPidFileAppears(Path pidFile, long windowMillis) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(windowMillis);
        while (System.nanoTime() < deadline) {
            if (Files.exists(pidFile)) {
                return true;
            }
            Thread.sleep(50L);
        }
        return Files.exists(pidFile);
    }

    /**
     * Replaces kubectl with synthetic child processes while keeping the real pre-flight checks, the
     * real command construction and the real stdin delivery.
     */
    private static final class ScriptedExecutor extends KubernetesReleaseExecutor {

        private final List<String> issued = Collections.synchronizedList(new ArrayList<>());
        private final List<List<String>> builtCommands = Collections.synchronizedList(new ArrayList<>());
        private volatile String applyMode = "ok";
        private volatile String rolloutMode = "ok";
        private volatile String podsMode = "ok";
        private volatile Function<String, String> logsMode = podName -> "ok";
        private volatile DiagnosticBudget budget;

        @Override
        KubectlCommandBuilder createCommandBuilder(KubernetesExecutionConfig config) {
            List<String> target = List.of("kubectl", "--kubeconfig", config.getKubeconfigPath(),
                    "--context", config.getKubeContext(), "-n", config.getNamespace());
            return new KubectlCommandBuilder(config) {
                @Override
                public List<String> buildApplyCommand() {
                    return scripted(target, "apply", applyMode, super.buildApplyCommand());
                }

                @Override
                public List<String> buildRolloutStatusCommand() {
                    return scripted(target, "rollout", rolloutMode, super.buildRolloutStatusCommand());
                }

                @Override
                public List<String> buildGetPodsCommand() {
                    return scripted(target, "get-pods", podsMode, super.buildGetPodsCommand());
                }

                @Override
                public List<String> buildLogsCommand(String podName) {
                    return scripted(target, "logs:" + podName, logsMode.apply(podName),
                            super.buildLogsCommand(podName));
                }
            };
        }

        @Override
        DiagnosticBudget podLogDiagnosticBudget() {
            DiagnosticBudget override = budget;
            return override != null ? override : super.podLogDiagnosticBudget();
        }

        List<String> issuedStages() {
            return List.copyOf(issued);
        }

        /** The real commands the executor built, before they were replaced by synthetic processes. */
        List<List<String>> builtCommands() {
            return List.copyOf(builtCommands);
        }

        /**
         * Records the real command, confirms it is bound to the configured target, and then hands the
         * process boundary a synthetic child instead of kubectl.
         */
        private List<String> scripted(List<String> target, String stage, String mode, List<String> realCommand) {
            if (realCommand.size() < target.size() || !realCommand.subList(0, target.size()).equals(target)) {
                throw new AssertionError("command for " + stage + " is not bound to the configured target: "
                        + realCommand);
            }
            issued.add(stage);
            builtCommands.add(List.copyOf(realCommand));
            return SyntheticProcessFixture.command(mode);
        }
    }
}
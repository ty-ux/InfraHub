package com.infrahub.release.service.executor.kubernetes;

import com.infrahub.release.dto.KubernetesExecutionConfig;
import com.infrahub.release.service.executor.ReleaseExecutionResult;
import com.infrahub.release.service.executor.ReleaseExecutionContext;
import com.infrahub.release.service.executor.ReleaseExecutor;
import com.infrahub.release.support.DeployExecutionOutcome;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Kubernetes release executor using kubectl.
 * Implements the ReleaseExecutor SPI for K8s deployments.
 */
@Component
public class KubernetesReleaseExecutor implements ReleaseExecutor {

    public static final String NAME = "KUBERNETES";

    /** Per-command timeout applied to each diagnostic command issued while collecting pod logs. */
    private static final Duration POD_LOG_COMMAND_TIMEOUT = Duration.ofSeconds(30);

    /**
     * Wall-clock budget for the best-effort pod log diagnostics. It bounds when diagnostic commands
     * are still allowed to start and caps every command deadline at the budget left; reclaiming the
     * last command can add up to {@link KubectlProcessRunner#CLEANUP_GRACE} on top of it.
     */
    private static final Duration POD_LOG_TOTAL_BUDGET = Duration.ofSeconds(60);

    /** Keeps a per-command timeout from being read as the deadline of the whole release. */
    private static final String PER_COMMAND_TIMEOUT_NOTE = "per-command timeout, not a total release deadline";

    /**
     * Reported when no rollout verification was requested. It states what was and was not
     * established, instead of borrowing the wording of a verified deployment.
     */
    private static final String APPLY_ONLY_MESSAGE =
            "Kubernetes apply succeeded; workload readiness was not verified (workloadName is not configured)";

    /** Reported only after the rollout of the configured workload returned success. */
    private static final String VERIFIED_MESSAGE = "Kubernetes apply and rollout verification succeeded";

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public ValidationResult validate(ReleaseExecutionContext context) {
        if (context == null) {
            return ValidationResult.invalid("execution context must not be null");
        }

        Object executorConfig = context.getExecutorConfig();
        if (!(executorConfig instanceof KubernetesExecutionConfig config)) {
            return ValidationResult.invalid("executorConfig must be KubernetesExecutionConfig");
        }

        // The fields a configuration must carry are decided by the shared policy, so a configuration
        // that was accepted when it was written is one this gate accepts before a release.
        for (KubernetesConfigPolicy.Issue issue : KubernetesConfigPolicy.configIssues(config)) {
            return ValidationResult.invalid(gateMessage(issue.field()));
        }

        return ValidationResult.valid();
    }

    private String gateMessage(KubernetesConfigPolicy.Field field) {
        return switch (field) {
            case KUBECONFIG_PATH -> "kubeconfigPath must be configured; the default kubeconfig is never used";
            case KUBE_CONTEXT -> "kubeContext must be configured; the current context is never used";
            case NAMESPACE -> "namespace must be configured";
            case MANIFEST -> "Either manifestPath or manifestContent must be configured";
            default -> "invalid Kubernetes executor configuration: " + field.jsonKey();
        };
    }

    @Override
    public ReleaseExecutionResult execute(ReleaseExecutionContext context) {
        ValidationResult validation = validate(context);
        if (!validation.isValid()) {
            // 配置/清单/工作负载校验失败：尚未创建任何命令，确定没有开始执行
            return ReleaseExecutionResult.notStarted(validation.errorMessage());
        }

        OffsetDateTime startedAt = OffsetDateTime.now();
        KubernetesExecutionConfig config = (KubernetesExecutionConfig) context.getExecutorConfig();

        StringBuilder stdoutLog = new StringBuilder();
        StringBuilder stderrLog = new StringBuilder();

        try {
            PlanOutcome outcome = buildPlan(config, stdoutLog, stderrLog);
            if (outcome.rejection() != null) {
                // 计划在建出任何命令之前被拒绝：没有任何外部动作开始
                return ReleaseExecutionResult.builder()
                        .success(false)
                        .message(outcome.rejection())
                        .stdout(stdoutLog.toString())
                        .stderr(stderrLog.toString())
                        .outcome(DeployExecutionOutcome.NOT_STARTED)
                        .startedAt(startedAt)
                        .endedAt(OffsetDateTime.now())
                        .build();
            }
            Plan plan = outcome.plan();

            ExecutionResult applyResult = executeCommand(plan.commandBuilder().buildApplyCommand(),
                    plan.snapshot(), Duration.ofSeconds(config.getEffectiveTimeout()));
            stdoutLog.append("APPLY:\n").append(applyResult.stdout()).append("\n");
            stderrLog.append("APPLY ERR:\n").append(applyResult.stderr()).append("\n");

            if (!applyResult.success()) {
                // kubectl apply 以非零退出时无法证明“没有创建/更新任何对象”：kubectl 可能已经
                // 提交了部分对象后失败。只有拿到完整成功才算生效，失败一律按 UNKNOWN 处理。
                return buildFailureResult(describeFailure("kubectl apply", applyResult), applyResult, startedAt,
                        DeployExecutionOutcome.UNKNOWN);
            }
            appendCleanupNotes(stderrLog, applyResult);

            if (plan.workloadName() != null) {
                if (Thread.currentThread().isInterrupted()) {
                    stderrLog.append("EXECUTION INTERRUPTED\n");
                    return buildUnknownResult("execution interrupted", startedAt, stdoutLog, stderrLog);
                }

                ExecutionResult rolloutResult = executeCommand(plan.commandBuilder().buildRolloutStatusCommand(), null,
                        Duration.ofSeconds(config.getEffectiveTimeout()));
                stdoutLog.append("ROLLOUT:\n").append(rolloutResult.stdout()).append("\n");
                stderrLog.append("ROLLOUT ERR:\n").append(rolloutResult.stderr()).append("\n");
                appendCleanupNotes(stderrLog, rolloutResult);

                if (!rolloutResult.success()) {
                    String failureMessage = describeFailure("kubectl rollout status", rolloutResult);
                    collectPodLogDiagnostics(plan.commandBuilder(), stdoutLog, stderrLog);
                    // apply 已成功，远端对象已经变更；rollout 未完成意味着“部分生效、未收敛”，
                    // 不是“完全没生效”。只有超时/进程未退出才升级为 UNKNOWN。
                    DeployExecutionOutcome rolloutOutcome = isUncertain(rolloutResult)
                            ? DeployExecutionOutcome.UNKNOWN
                            : DeployExecutionOutcome.PARTIAL;
                    return buildFailureResult(failureMessage, startedAt, stdoutLog, stderrLog, rolloutOutcome);
                }
            }

            return ReleaseExecutionResult.builder()
                    .success(true)
                    .message(plan.workloadName() == null ? APPLY_ONLY_MESSAGE : VERIFIED_MESSAGE)
                    .stdout(stdoutLog.toString())
                    .stderr(stderrLog.toString())
                    .outcome(DeployExecutionOutcome.SUCCEEDED)
                    .startedAt(startedAt)
                    .endedAt(OffsetDateTime.now())
                    .build();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return buildUnknownResult("execution interrupted", startedAt, stdoutLog, stderrLog);
        } catch (IOException e) {
            return buildUnknownResult("execution failed before a result was observed: " + e.getMessage(),
                    startedAt, stdoutLog, stderrLog);
        } catch (Exception e) {
            return buildUnknownResult("unexpected error: " + e.getMessage(), startedAt, stdoutLog, stderrLog);
        }
    }

    /**
     * Resolves every input the release acts on - the kubeconfig binding, the manifest snapshot and
     * the workload that will be watched - before a command exists. A configuration error, an
     * unreadable file, an invalid manifest, a resource outside the configured namespace, a workload
     * the snapshot does not contain, or a snapshot whose scope one {@code workloadName} cannot express
     * all end the release here, with no child process started.
     *
     * @return the plan to execute, or the reason the release was rejected before anything ran
     */
    private PlanOutcome buildPlan(KubernetesExecutionConfig config,
                                  StringBuilder stdoutLog, StringBuilder stderrLog) {
        String kubeconfigError = ManifestValidator.validateKubeconfigPath(config.getKubeconfigPath());
        if (kubeconfigError != null) {
            return rejectPlan("kubeconfig", kubeconfigError, stderrLog);
        }

        // manifestPath takes precedence: with a path configured, manifestContent is never read
        ManifestValidator.Result snapshot = resolveSnapshot(config);
        if (!snapshot.isValid()) {
            return rejectPlan("manifest", snapshot.rejection(), stderrLog);
        }

        String workloadName = config.hasWorkloadName() ? config.getWorkloadName() : null;
        if (workloadName != null) {
            List<String> deployments = ManifestValidator.deploymentNames(snapshot.content());
            if (!deployments.contains(workloadName)) {
                String reason = deployments.isEmpty()
                        ? "workloadName '" + workloadName + "' is configured but the manifest declares no Deployment,"
                        + " so no rollout status could describe this apply"
                        : "workloadName '" + workloadName + "' is not the Deployment declared by the manifest"
                        + " (found " + String.join(", ", deployments) + ")";
                return rejectPlan("workload", reason, stderrLog);
            }
            if (deployments.size() > 1) {
                return rejectPlan("workload", "manifest declares " + deployments.size() + " Deployments ("
                        + String.join(", ", deployments) + ") but a single workloadName ('" + workloadName
                        + "') cannot express the acceptance target: the apply that was verified would cover one"
                        + " Deployment while the others in the same manifest stay unverified; this executor"
                        + " verifies exactly one workload and does not accept a manifest whose scope it cannot express",
                        stderrLog);
            }
        }

        KubectlCommandBuilder commandBuilder;
        try {
            commandBuilder = createCommandBuilder(config);
        } catch (RuntimeException e) {
            return rejectPlan("kubectl target", e.getMessage(), stderrLog);
        }

        return PlanOutcome.accepted(new Plan(commandBuilder, snapshot.content(), workloadName));
    }

    private ManifestValidator.Result resolveSnapshot(KubernetesExecutionConfig config) {
        if (config.hasManifestPath()) {
            return ManifestValidator.validatePath(config.getManifestPath(), config.getNamespace());
        }
        if (config.hasManifestContent()) {
            return ManifestValidator.validateContent(config.getManifestContent(), config.getNamespace());
        }
        return ManifestValidator.Result.rejected("Either manifestPath or manifestContent must be configured");
    }

    private PlanOutcome rejectPlan(String stage, String reason, StringBuilder stderrLog) {
        stderrLog.append("REJECTED BEFORE EXECUTION (").append(stage).append("): ").append(reason).append("\n");
        return PlanOutcome.rejected(reason);
    }

    /** Seam for tests; production always drives kubectl. */
    KubectlCommandBuilder createCommandBuilder(KubernetesExecutionConfig config) {
        return new KubectlCommandBuilder(config);
    }

    /**
     * The inputs one release acts on: the bound command builder, the validated manifest snapshot that
     * is sent to stdin, and the workload to verify - null when no rollout verification was requested.
     */
    private record Plan(KubectlCommandBuilder commandBuilder, String snapshot, String workloadName) {
    }

    /** Either the plan to execute or the reason this release ends before any command is started. */
    private record PlanOutcome(Plan plan, String rejection) {

        static PlanOutcome accepted(Plan plan) {
            return new PlanOutcome(plan, null);
        }

        static PlanOutcome rejected(String rejection) {
            return new PlanOutcome(null, rejection);
        }
    }

    /** Seam for tests; bounds the best-effort pod log diagnostics. */
    DiagnosticBudget podLogDiagnosticBudget() {
        return new DiagnosticBudget(POD_LOG_COMMAND_TIMEOUT, POD_LOG_TOTAL_BUDGET);
    }

    record DiagnosticBudget(Duration perCommand, Duration total) {
    }

    private ExecutionResult executeCommand(List<String> command, String stdinContent, Duration timeout)
            throws IOException, InterruptedException {

        KubectlProcessRunner.CommandResult result = KubectlProcessRunner.run(command, stdinContent, timeout);
        return new ExecutionResult(result.success(), result.processExited(), result.timedOut(), result.exitCode(),
                timeout, result.stdout(), result.stderr(), result.failureReason(), result.notes());
    }

    private String describeFailure(String commandLabel, ExecutionResult result) {
        if (result.timedOut()) {
            String message = commandLabel + " timed out after " + formatDuration(result.timeout())
                    + " (" + PER_COMMAND_TIMEOUT_NOTE + ")";
            return result.failureReason() == null ? message : message + "; " + result.failureReason();
        }

        StringBuilder message = new StringBuilder(commandLabel).append(" failed (")
                .append(result.processExited() ? "exit code " + result.exitCode() : "process did not exit")
                .append(")");
        List<String> details = new ArrayList<>();
        if (result.failureReason() != null) {
            details.add(result.failureReason());
        }
        if (!result.stderr().isBlank()) {
            details.add(result.stderr().strip());
        }
        if (!details.isEmpty()) {
            message.append(": ").append(String.join("; ", details));
        }
        return message.toString();
    }

    private String formatDuration(Duration duration) {
        if (duration.toMillis() % 1000 == 0 && duration.toSeconds() >= 1) {
            return duration.toSeconds() + "s";
        }
        return duration.toMillis() + "ms";
    }

    /**
     * Best-effort pod log diagnostics. Never changes the original failure and never runs when the
     * thread has been interrupted; command deadlines are charged against {@link #podLogDiagnosticBudget()}
     * and reclamation of the last command adds at most {@link KubectlProcessRunner#CLEANUP_GRACE}.
     */
    private void collectPodLogDiagnostics(KubectlCommandBuilder cmdBuilder,
                                          StringBuilder stdoutLog, StringBuilder stderrLog) {
        DiagnosticBudget budget = podLogDiagnosticBudget();
        long deadlineNanos = System.nanoTime() + Math.max(budget.total().toNanos(), 0L);
        try {
            Duration podsTimeout = diagnosticTimeout(deadlineNanos, budget);
            if (podsTimeout == null) {
                stderrLog.append("GET PODS SKIPPED: pod log collection budget exhausted\n");
                return;
            }

            ExecutionResult podsResult = executeCommand(cmdBuilder.buildGetPodsCommand(), null, podsTimeout);
            stdoutLog.append("GET PODS (exit code ").append(podsResult.exitCode()).append("):\n")
                    .append(podsResult.stdout()).append("\n");
            stderrLog.append("GET PODS ERR:\n").append(podsResult.stderr()).append("\n");
            appendCleanupNotes(stderrLog, podsResult);

            if (!podsResult.success()) {
                stderrLog.append("GET PODS FAILED: ").append(describeFailure("kubectl get pods", podsResult))
                        .append("\n");
                return;
            }

            String podList = podsResult.stdout().trim();
            if (!StringUtils.hasText(podList)) {
                stderrLog.append("GET PODS EMPTY: no pods matched the workload selector\n");
                return;
            }

            for (String podName : podList.split("\\s+")) {
                if (Thread.currentThread().isInterrupted()) {
                    stderrLog.append("POD LOGS ABORTED: thread interrupted\n");
                    return;
                }
                Duration logsTimeout = diagnosticTimeout(deadlineNanos, budget);
                if (logsTimeout == null) {
                    stderrLog.append("POD LOGS TRUNCATED: pod log collection budget of ")
                            .append(budget.total().toMillis()).append("ms exhausted\n");
                    return;
                }

                ExecutionResult logsResult = executeCommand(cmdBuilder.buildLogsCommand(podName), null, logsTimeout);
                stdoutLog.append("LOGS for ").append(podName).append(" (exit code ")
                        .append(logsResult.exitCode()).append("):\n").append(logsResult.stdout()).append("\n");
                appendCleanupNotes(stderrLog, logsResult);
                if (!logsResult.success()) {
                    stderrLog.append("LOGS for ").append(podName).append(" FAILED: ")
                            .append(describeFailure("kubectl logs " + podName, logsResult)).append("\n");
                } else if (!logsResult.stderr().isBlank()) {
                    stderrLog.append("LOGS ERR for ").append(podName).append(":\n")
                            .append(logsResult.stderr()).append("\n");
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            stderrLog.append("POD LOG COLLECTION INTERRUPTED\n");
        } catch (Exception e) {
            stderrLog.append("POD LOG COLLECTION ERROR: ").append(e.getClass().getSimpleName())
                    .append(": ").append(e.getMessage()).append("\n");
        }
    }

    /**
     * Timeout for the next diagnostic command: never more than the per-command cap, never more than
     * the budget left, and null - never rounded up to a whole second - once the budget is spent.
     */
    private Duration diagnosticTimeout(long deadlineNanos, DiagnosticBudget budget) {
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0) {
            return null;
        }
        Duration remaining = Duration.ofNanos(remainingNanos);
        return remaining.compareTo(budget.perCommand()) <= 0 ? remaining : budget.perCommand();
    }

    private void appendCleanupNotes(StringBuilder stderrLog, ExecutionResult result) {
        for (String note : result.notes()) {
            stderrLog.append("CLEANUP: ").append(note).append("\n");
        }
    }

    /**
     * 一条命令的失败是否属于“连本地进程都没有正常结束”：超时（进程被本地杀掉）或子进程没有退出。
     * 拿到非零退出码说明命令本身结束了，但**是否已产生远端副作用**仍要按命令语义判断
     * （apply 一律按 UNKNOWN，rollout 在 apply 之后失败按 PARTIAL）。
     */
    private static boolean isUncertain(ExecutionResult result) {
        return result.timedOut() || !result.processExited();
    }

    private ReleaseExecutionResult buildFailureResult(String message, ExecutionResult result, OffsetDateTime startedAt,
                                                       DeployExecutionOutcome outcome) {
        StringBuilder stderr = new StringBuilder(result.stderr());
        for (String note : result.notes()) {
            stderr.append("CLEANUP: ").append(note).append("\n");
        }
        return ReleaseExecutionResult.builder()
                .success(false)
                .message(message)
                .stdout(result.stdout())
                .stderr(stderr.toString())
                .outcome(outcome)
                .startedAt(startedAt)
                .endedAt(OffsetDateTime.now())
                .build();
    }

    private ReleaseExecutionResult buildFailureResult(String message, OffsetDateTime startedAt,
                                                       StringBuilder stdoutLog, StringBuilder stderrLog,
                                                       DeployExecutionOutcome outcome) {
        return ReleaseExecutionResult.builder()
                .success(false)
                .message(message)
                .stdout(stdoutLog.toString())
                .stderr(stderrLog.toString())
                .outcome(outcome)
                .startedAt(startedAt)
                .endedAt(OffsetDateTime.now())
                .build();
    }

    /** 执行已经开始，但连失败/成功都无法证明（中断、IO 异常、未预期异常）。 */
    private ReleaseExecutionResult buildUnknownResult(String message, OffsetDateTime startedAt,
                                                       StringBuilder stdoutLog, StringBuilder stderrLog) {
        return ReleaseExecutionResult.builder()
                .success(false)
                .message(message)
                .stdout(stdoutLog.toString())
                .stderr(stderrLog.toString())
                .outcome(DeployExecutionOutcome.UNKNOWN)
                .startedAt(startedAt)
                .endedAt(OffsetDateTime.now())
                .build();
    }

    private record ExecutionResult(boolean success, boolean processExited, boolean timedOut, int exitCode,
                                   Duration timeout, String stdout, String stderr, String failureReason,
                                   List<String> notes) {
    }
}
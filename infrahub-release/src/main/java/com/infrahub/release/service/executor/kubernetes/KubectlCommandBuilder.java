package com.infrahub.release.service.executor.kubernetes;

import com.infrahub.release.dto.KubernetesExecutionConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds kubectl commands for Kubernetes deployments.
 * All commands are constructed as lists for safe execution without shell injection.
 *
 * <p>Every command names its target explicitly - {@code --kubeconfig}, {@code --context} and
 * {@code -n} - so no command can fall back to the default kubeconfig, the current context or an
 * inherited {@code KUBECONFIG}. A config missing any of the three is rejected here, before a command
 * exists to run; nothing is defaulted silently.</p>
 */
public class KubectlCommandBuilder {

    private final KubernetesExecutionConfig config;

    public KubectlCommandBuilder(KubernetesExecutionConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        requireTargetField("kubeconfigPath", config.getKubeconfigPath());
        requireTargetField("kubeContext", config.getKubeContext());
        requireTargetField("namespace", config.getNamespace());
        this.config = config;
    }

    /**
     * Builds the base kubectl command with the explicit kubeconfig, context and namespace binding.
     */
    private List<String> buildBaseCommand() {
        List<String> cmd = new ArrayList<>();
        cmd.add("kubectl");
        cmd.add("--kubeconfig");
        cmd.add(config.getKubeconfigPath());
        cmd.add("--context");
        cmd.add(config.getKubeContext());
        cmd.add("-n");
        cmd.add(config.getNamespace());
        return cmd;
    }

    /**
     * Builds kubectl apply command for the validated manifest snapshot.
     * The manifest always arrives on stdin: the snapshot that was validated is the snapshot applied,
     * and no subsequent read of a file that may have changed can be interleaved.
     *
     * @return command parts ready for ProcessBuilder
     */
    public List<String> buildApplyCommand() {
        List<String> cmd = buildBaseCommand();
        cmd.add("apply");
        cmd.add("-f");
        cmd.add("-");
        return cmd;
    }

    /**
     * Builds kubectl rollout status command for deployment monitoring.
     *
     * @return command parts ready for ProcessBuilder
     */
    public List<String> buildRolloutStatusCommand() {
        if (!config.hasWorkloadName()) {
            throw new IllegalStateException("workloadName must be set for rollout status");
        }

        List<String> cmd = buildBaseCommand();
        cmd.add("rollout");
        cmd.add("status");
        cmd.add("deployment/" + config.getWorkloadName());
        cmd.add("--timeout=" + config.getEffectiveTimeout() + "s");

        return cmd;
    }

    /**
     * Builds kubectl logs command for fetching pod logs.
     *
     * @param podName the pod name to fetch logs from
     * @return command parts ready for ProcessBuilder
     */
    public List<String> buildLogsCommand(String podName) {
        List<String> cmd = buildBaseCommand();
        cmd.add("logs");
        cmd.add(podName);
        cmd.add("--tail=" + config.getEffectiveTailLines());

        return cmd;
    }

    /**
     * Builds kubectl delete command for cleanup.
     *
     * @return command parts ready for ProcessBuilder
     */
    public List<String> buildDeleteCommand() {
        List<String> cmd = buildBaseCommand();
        cmd.add("delete");
        cmd.add("-f");
        cmd.add("-");
        return cmd;
    }

    /**
     * Builds kubectl get pods command for finding pods by deployment.
     *
     * @return command parts ready for ProcessBuilder
     */
    public List<String> buildGetPodsCommand() {
        if (!config.hasWorkloadName()) {
            throw new IllegalStateException("workloadName must be set for get pods");
        }

        List<String> cmd = buildBaseCommand();
        cmd.add("get");
        cmd.add("pods");
        cmd.add("-l");
        cmd.add("app=" + config.getWorkloadName());
        cmd.add("-o");
        cmd.add("jsonpath={.items[*].metadata.name}");

        return cmd;
    }

    /**
     * Returns the effective timeout in seconds.
     */
    public int getTimeoutSeconds() {
        return config.getEffectiveTimeout();
    }

    private static void requireTargetField(String fieldName, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("kubectl commands require an explicit " + fieldName
                    + "; the default kubeconfig, the current context and KUBECONFIG are never used");
        }
    }
}
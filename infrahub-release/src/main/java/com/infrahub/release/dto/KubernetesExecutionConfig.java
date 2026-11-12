package com.infrahub.release.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Configuration DTO for Kubernetes executor.
 * Contains all settings needed for kubectl-based deployments.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class KubernetesExecutionConfig {

    /** Absolute path of the kubeconfig file this executor authenticates with. */
    private String kubeconfigPath;

    private String kubeContext;

    private String namespace;

    private String manifestPath;

    private String manifestContent;

    private String workloadName;

    private Integer rolloutTimeoutSeconds;

    private Integer logTailLines;

    public boolean hasKubeconfigPath() {
        return kubeconfigPath != null && !kubeconfigPath.isBlank();
    }

    public boolean hasKubeContext() {
        return kubeContext != null && !kubeContext.isBlank();
    }

    public boolean hasNamespace() {
        return namespace != null && !namespace.isBlank();
    }

    public boolean hasManifestPath() {
        return manifestPath != null && !manifestPath.isBlank();
    }

    public boolean hasManifestContent() {
        return manifestContent != null && !manifestContent.isBlank();
    }

    /**
     * Whether rollout verification was requested. Without it the executor only reports that the
     * manifest was applied and never claims the workload is ready.
     */
    public boolean hasWorkloadName() {
        return workloadName != null && !workloadName.isBlank();
    }

    public int getEffectiveTimeout() {
        return rolloutTimeoutSeconds != null && rolloutTimeoutSeconds > 0 ? rolloutTimeoutSeconds : 300;
    }

    public int getEffectiveTailLines() {
        return logTailLines != null && logTailLines > 0 ? logTailLines : 100;
    }
}
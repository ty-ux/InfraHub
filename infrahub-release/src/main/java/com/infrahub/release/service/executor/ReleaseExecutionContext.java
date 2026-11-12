package com.infrahub.release.service.executor;

import com.infrahub.base.entity.BaseEnv;
import com.infrahub.base.entity.BaseServiceEntity;
import com.infrahub.release.entity.ReleaseArtifact;
import com.infrahub.release.entity.ReleaseOrder;
import com.infrahub.release.entity.ReleaseVersion;
import lombok.Builder;
import lombok.Getter;

import java.util.Map;

/**
 * Context object containing all information needed for release execution.
 * Passed to ReleaseExecutor implementations during deployment.
 */
@Getter
@Builder
public class ReleaseExecutionContext {

    private final ReleaseOrder order;
    private final BaseServiceEntity service;
    private final BaseEnv env;
    private final ReleaseVersion version;
    private final ReleaseArtifact artifact;
    private final Map<String, String> deployConfig;
    private final Object executorConfig;
    private final Long hostId;
    private final Long instanceId;

    public String getServiceCode() {
        return service != null ? service.getServiceCode() : null;
    }

    public String getEnvCode() {
        return env != null ? env.getEnvCode() : null;
    }

    public String getVersionNo() {
        return version != null ? version.getVersionNo() : null;
    }

    public String getArtifactUri() {
        return artifact != null ? artifact.getArtifactUri() : null;
    }

    public String getOrderNo() {
        return order != null ? order.getOrderNo() : null;
    }
}

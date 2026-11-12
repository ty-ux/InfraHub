package com.infrahub.release.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.infrahub.common.error.ErrorCode;
import com.infrahub.common.exception.BizException;
import com.infrahub.release.dto.CreateReleaseExecutorConfigRequest;
import com.infrahub.release.entity.ReleaseExecutorConfig;
import com.infrahub.release.mapper.ReleaseExecutorConfigMapper;
import com.infrahub.release.service.executor.kubernetes.KubernetesConfigPolicy;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class ReleaseExecutorConfigService extends ServiceImpl<ReleaseExecutorConfigMapper, ReleaseExecutorConfig> {

    private static final String KUBERNETES = "KUBERNETES";

    public List<ReleaseExecutorConfig> findEnabledByEnvId(Long envId) {
        return baseMapper.findEnabledByEnvId(envId);
    }

    public ReleaseExecutorConfig findByEnvIdAndName(Long envId, String executorName) {
        return baseMapper.findByEnvIdAndName(envId, executorName);
    }

    public ReleaseExecutorConfig findEnabledById(Long id) {
        if (id == null) {
            return null;
        }
        return baseMapper.findEnabledById(id);
    }

    public ReleaseExecutorConfig getRequiredEnabledById(Long id) {
        ReleaseExecutorConfig config = findEnabledById(id);
        if (config == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "执行器配置不存在或未启用: " + id);
        }
        return config;
    }

    public ReleaseExecutorConfig createConfig(CreateReleaseExecutorConfigRequest request) {
        String executorType = validateExecutorType(request.getExecutorType());
        validatePayloadForWrite(executorType, request.getConfigPayload());

        ReleaseExecutorConfig existing = findByEnvIdAndName(request.getEnvId(), request.getExecutorName());
        if (existing != null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR,
                    "执行器配置名称已存在: " + request.getExecutorName());
        }

        ReleaseExecutorConfig config = new ReleaseExecutorConfig();
        config.setExecutorName(request.getExecutorName());
        config.setExecutorType(executorType);
        config.setEnvId(request.getEnvId());
        config.setConfigPayload(request.getConfigPayload());
        config.setEnabled(request.getEnabled() != null ? request.getEnabled() : true);
        save(config);
        return config;
    }

    public ReleaseExecutorConfig updateConfig(Long id, CreateReleaseExecutorConfigRequest request) {
        ReleaseExecutorConfig config = getById(id);
        if (config == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "执行器配置不存在: " + id);
        }
        String executorType = validateExecutorType(request.getExecutorType());
        validatePayloadForWrite(executorType, request.getConfigPayload());

        // The name has to stay unique within the target environment, so the check runs whenever the
        // pair could collide - the environment may be the half that moved, not the name. The row being
        // updated is allowed to hold the name; only another configuration is a conflict.
        ReleaseExecutorConfig existing = findByEnvIdAndName(request.getEnvId(), request.getExecutorName());
        if (existing != null && !existing.getId().equals(id)) {
            throw new BizException(ErrorCode.VALIDATION_ERROR,
                    "执行器配置名称已存在: " + request.getExecutorName());
        }

        config.setExecutorName(request.getExecutorName());
        config.setExecutorType(executorType);
        config.setEnvId(request.getEnvId());
        config.setConfigPayload(request.getConfigPayload());
        if (request.getEnabled() != null) {
            config.setEnabled(request.getEnabled());
        }
        updateById(config);
        return config;
    }

    public void toggleEnabled(Long id, boolean enabled) {
        ReleaseExecutorConfig config = getById(id);
        if (config == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "执行器配置不存在: " + id);
        }
        if (enabled) {
            // A configuration that cannot be executed must not become the one a release picks up.
            // The enabled flag is left untouched on rejection, and disabling is never gated.
            List<KubernetesConfigPolicy.Issue> issues = enableIssues(config);
            if (!issues.isEmpty()) {
                throw new BizException(ErrorCode.VALIDATION_ERROR,
                        "执行器配置存在无法启用的配置项，需先修正后再启用: " + renderIssues(issues));
            }
        }
        config.setEnabled(enabled);
        updateById(config);
    }

    /**
     * The problems that keep a stored configuration from being enabled: the same pure rules that gate
     * a save. Only the Kubernetes executor has them - the other executors carry their payload exactly
     * as they always have.
     */
    private List<KubernetesConfigPolicy.Issue> enableIssues(ReleaseExecutorConfig config) {
        if (!KUBERNETES.equals(normalizeType(config.getExecutorType()))) {
            return List.of();
        }
        return KubernetesConfigPolicy.payloadIssues(config.getConfigPayload());
    }

    /**
     * Rejects a payload the Kubernetes executor could not run, before it is stored. The checks cover
     * the shape of the configuration only: no kubeconfig is read, no manifest file is opened, no
     * cluster is contacted, and no file is required to exist on this host. Whether the referenced
     * files are present and readable is still decided per release, where the executor runs them.
     */
    private void validatePayloadForWrite(String executorType, String configPayload) {
        if (!KUBERNETES.equals(executorType)) {
            return;
        }
        List<KubernetesConfigPolicy.Issue> issues = KubernetesConfigPolicy.payloadIssues(configPayload);
        if (!issues.isEmpty()) {
            throw new BizException(ErrorCode.VALIDATION_ERROR,
                    "执行器配置校验失败: " + renderIssues(issues));
        }
    }

    /**
     * Names every rejected field so the operator can go straight to it. Only field names and reasons
     * are rendered - never a value from the payload, which may carry credentials or a whole manifest.
     */
    private String renderIssues(List<KubernetesConfigPolicy.Issue> issues) {
        return issues.stream()
                .map(this::renderIssue)
                .collect(Collectors.joining("; "));
    }

    private String renderIssue(KubernetesConfigPolicy.Issue issue) {
        String reason = switch (issue.reason()) {
            case EMPTY -> "配置内容不能为空";
            case NOT_JSON -> "配置内容不是有效的 JSON";
            case NOT_JSON_OBJECT -> "配置内容必须是 JSON 对象，不能是数组、数字、字符串或布尔值";
            case MISSING -> "缺少必填项";
            case NOT_NON_BLANK_TEXT -> "必须是非空字符串，不能是数字、布尔值、数组或空字符串";
            case NOT_ABSOLUTE_PATH -> "必须是绝对路径（按执行器所在主机识别，此处只校验路径形式，不检查文件是否存在）";
            case NOT_POSITIVE_INTEGER -> "必须是大于 0 的整数";
        };
        return issue.field() == KubernetesConfigPolicy.Field.PAYLOAD
                ? reason
                : issue.field().jsonKey() + " " + reason;
    }

    private String normalizeType(String executorType) {
        return executorType == null ? null : executorType.toUpperCase();
    }

    /** Validates the executor type and returns it normalized, so callers store the same form they checked. */
    private String validateExecutorType(String executorType) {
        if (executorType == null || executorType.isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "执行器类型不能为空");
        }
        String type = normalizeType(executorType);
        if (!type.equals("SSH") && !type.equals("DOCKER") && !type.equals(KUBERNETES)) {
            throw new BizException(ErrorCode.VALIDATION_ERROR,
                    "不支持的执行器类型: " + executorType + "，支持类型: SSH, DOCKER, KUBERNETES");
        }
        return type;
    }
}
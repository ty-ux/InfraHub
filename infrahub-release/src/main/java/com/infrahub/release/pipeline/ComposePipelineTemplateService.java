package com.infrahub.release.pipeline;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.infrahub.base.entity.ConfigEnvConfig;
import com.infrahub.base.entity.ConfigGroup;
import com.infrahub.base.mapper.BaseEnvMapper;
import com.infrahub.base.service.BaseEnvService;
import com.infrahub.base.service.BaseServiceEntityService;
import com.infrahub.base.service.ConfigEnvConfigService;
import com.infrahub.base.service.ConfigGroupService;
import com.infrahub.base.service.ConfigSecretRefService;
import com.infrahub.common.error.ErrorCode;
import com.infrahub.common.exception.BizException;
import com.infrahub.pipeline.entity.PipelineDef;
import com.infrahub.pipeline.entity.PipelineStage;
import com.infrahub.pipeline.entity.PipelineStep;
import com.infrahub.pipeline.service.PipelineDefService;
import com.infrahub.pipeline.service.PipelineStageService;
import com.infrahub.pipeline.service.PipelineStepService;
import com.infrahub.release.service.AssetHostSshConfigService;
import com.infrahub.release.service.DeployConfigService;
import com.infrahub.release.service.RuntimeServiceInstanceService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class ComposePipelineTemplateService {
    static final String EXECUTOR = "COMPOSE_TEMPLATE";

    private final ObjectMapper objectMapper;
    private final PipelineDefService definitions;
    private final PipelineStageService stages;
    private final PipelineStepService steps;
    private final BaseEnvMapper environmentMapper;
    private final BaseEnvService environments;
    private final BaseServiceEntityService services;
    private final RuntimeServiceInstanceService instances;
    private final AssetHostSshConfigService sshConfigs;
    private final ConfigSecretRefService secrets;
    private final DeployConfigService deployConfigs;
    private final ConfigGroupService groups;
    private final ConfigEnvConfigService configs;

    @Transactional
    public Long create(ComposePipelineSpec spec) {
        validate(spec);
        PipelineDef definition = new PipelineDef();
        definition.setAppId(spec.appId());
        definition.setPipelineCode(spec.pipelineCode());
        definition.setPipelineName(spec.pipelineName().strip());
        definition.setTriggerType("MANUAL");
        definition.setPipelineType("DEPLOY");
        definition.setEnabled(true);
        definition.setVersion(1);
        try {
            definitions.save(definition);
            saveDeployConfig(spec);
        } catch (DuplicateKeyException duplicate) {
            throw invalid("流水线编码已使用，或该应用环境已有 Compose 模板配置");
        }

        List<Stage> plan = new ArrayList<>();
        if (spec.preflight()) plan.add(new Stage("环境检测", List.of(new Step("检查构建节点", Operation.PREFLIGHT, 60))));
        plan.add(new Stage("代码拉取", List.of(new Step("拉取代码并记录提交", Operation.CHECKOUT, 300))));
        plan.add(new Stage("镜像构建", List.of(new Step("构建后端镜像", Operation.BUILD_BACKEND, 1200),
                new Step("构建前端镜像", Operation.BUILD_FRONTEND, 1200))));
        plan.add(new Stage("镜像推送", List.of(new Step("推送前后端镜像", Operation.PUSH, 900))));
        plan.add(new Stage("制品登记", List.of(new Step("生成 Compose 制品", Operation.MANIFEST, 120),
                new Step("登记版本和制品", "REGISTER_ARTIFACT", null, 180))));
        plan.add(new Stage(StringUtils.hasText(spec.healthUrl()) ? "部署与健康检查" : "目标服务器部署",
                List.of(new Step("部署本次运行的版本", "RELEASE_DEPLOY", null, 300))));
        for (int i = 0; i < plan.size(); i++) {
            Stage item = plan.get(i);
            PipelineStage stage = new PipelineStage();
            stage.setPipelineId(definition.getId());
            stage.setStageName(item.name());
            stage.setStageOrder(i + 1);
            stage.setTimeoutSeconds(item.steps().stream().mapToInt(Step::timeout).sum() + 20);
            stages.save(stage);
            for (int j = 0; j < item.steps().size(); j++) {
                Step task = item.steps().get(j);
                PipelineStep step = new PipelineStep();
                step.setStageId(stage.getId());
                step.setStepOrder(j + 1);
                step.setStepName(task.name());
                step.setTimeoutSeconds(task.timeout());
                step.setStepType("SCRIPT");
                step.setExecutorType(task.executor());
                if (task.operation() != null) {
                    step.setScriptContent(serialize(new Task(task.operation(), spec)));
                }
                steps.save(step);
            }
        }
        return definition.getId();
    }

    private void validate(ComposePipelineSpec spec) {
        var service = services.getById(spec.serviceId());
        if (service == null || Boolean.TRUE.equals(service.getIsDeleted()) || !spec.appId().equals(service.getAppId())) {
            throw invalid("请选择属于该应用的服务");
        }
        environmentMapper.lockById(spec.envId());
        var environment = environments.getById(spec.envId());
        if (environment == null || Boolean.TRUE.equals(environment.getIsDeleted())) throw invalid("请选择有效的部署环境");
        validateTargets(spec);
        validateUrl(spec.repositoryUrl(), true);
        if (spec.gitSshSecretRefId() != null) {
            var secret = secrets.getById(spec.gitSshSecretRefId());
            if (!spec.repositoryUrl().startsWith("ssh://") || secret == null || !"LOCAL_FILE".equals(secret.getSecretProvider())) {
                throw invalid("Git SSH 私钥引用需要 SSH 仓库地址和本地文件类型的密钥引用");
            }
        }
        if (StringUtils.hasText(spec.healthUrl())) validateUrl(spec.healthUrl(), false);
        validatePath(spec.composeFile(), false);
        validatePath(spec.backend().context(), true);
        validatePath(spec.frontend().context(), true);
        validatePath(spec.backend().dockerfile(), false);
        validatePath(spec.frontend().dockerfile(), false);
        validatePath(spec.deployRoot().substring(1), false);
        if (!(spec.registry().contains(".") || spec.registry().contains(":") || spec.registry().equals("localhost"))) {
            throw invalid("镜像仓库请使用完整域名、IP 或带端口的地址");
        }
        if (spec.registry().contains(":")) {
            int port = Integer.parseInt(spec.registry().substring(spec.registry().lastIndexOf(':') + 1));
            if (port < 1 || port > 65535) throw invalid("镜像仓库端口无效");
        }
        if (spec.backend().repository().equals(spec.frontend().repository())
                || spec.backend().composeService().equals(spec.frontend().composeService())) {
            throw invalid("前后端需要不同的镜像名称和 Compose 服务名");
        }
    }

    void validateTargets(ComposePipelineSpec spec) {
        var targets = instances.listByServiceAndEnv(spec.serviceId(), spec.envId());
        var hosts = new HashSet<Long>();
        if (targets.isEmpty() || targets.stream().anyMatch(target -> !"DOCKER_COMPOSE".equals(target.getInstanceType())
                || target.getInstanceName() == null || !target.getInstanceName().matches("[a-z0-9][a-z0-9_-]*")
                || target.getHostId() == null || !hosts.add(target.getHostId())
                || sshConfigs.getEnabledByHostId(target.getHostId()) == null)) {
            throw invalid("请先在目标环境登记 Compose 实例。实例名称须与 Compose 项目名一致，每台主机登记一个实例并启用 SSH 配置");
        }
    }

    public List<String> buildContexts() {
        Process process = null;
        try {
            process = new ProcessBuilder("docker", "context", "ls", "--format", "{{.Name}}")
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if (!process.waitFor(3, TimeUnit.SECONDS) || process.exitValue() != 0) return List.of();
            String names = new String(process.getInputStream().readNBytes(8192), StandardCharsets.UTF_8);
            return names.lines().filter(name -> name.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}")).distinct().toList();
        } catch (IOException unavailable) {
            return List.of();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return List.of();
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
        }
    }

    private static void validateUrl(String value, boolean repository) {
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            boolean ssh = repository && "ssh".equals(scheme);
            boolean validScheme = ssh || "https".equals(scheme) || (!repository && "http".equals(scheme));
            boolean validUser = uri.getUserInfo() == null || (ssh && uri.getUserInfo().matches("[A-Za-z0-9_-]+"));
            if (validScheme && validUser && uri.getHost() != null && uri.getFragment() == null
                    && uri.getPort() <= 65535 && (!repository || (uri.getQuery() == null && uri.getPath().length() > 1))) return;
        } catch (IllegalArgumentException ignored) {
            // Report the field without echoing URLs that might contain embedded credentials.
        }
        throw invalid(repository ? "请输入 HTTPS 或 SSH Git 仓库地址，凭据请单独配置" : "请输入有效的 HTTP 或 HTTPS 健康检查地址");
    }

    private static void validatePath(String path, boolean allowRoot) {
        if (allowRoot && ".".equals(path)) return;
        if (!path.matches("[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*")
                || List.of(path.split("/")).stream().anyMatch(part -> part.equals("..") || part.equals("."))) {
            throw invalid("请使用有效的仓库相对路径或服务器部署目录");
        }
    }

    private void saveDeployConfig(ComposePipelineSpec spec) {
        String command = ComposeRemoteCommand.create(spec);
        Map<String, String> values = Map.of("deploy.root", spec.deployRoot(), "deploy.command", command, "rollback.command", command);
        Map<String, String> current = deployConfigs.loadDeployConfig(spec.appId(), spec.envId());
        if (values.entrySet().stream().allMatch(entry -> entry.getValue().equals(current.get(entry.getKey())))) return;
        if (values.keySet().stream().anyMatch(current::containsKey)) {
            throw invalid("该应用环境已有部署配置，请选择新的环境或在配置中心核对原配置");
        }
        ConfigGroup group = new ConfigGroup();
        group.setAppId(spec.appId());
        group.setEnvId(spec.envId());
        group.setGroupCode("compose-pipeline");
        group.setGroupName("Docker Compose 交付");
        groups.save(group);
        values.forEach((key, value) -> {
            ConfigEnvConfig config = new ConfigEnvConfig();
            config.setAppId(spec.appId());
            config.setEnvId(spec.envId());
            config.setGroupId(group.getId());
            config.setConfigKey(key);
            config.setConfigValue(value);
            config.setValueType("STRING");
            configs.save(config);
        });
    }

    private String serialize(Task task) {
        try {
            return objectMapper.writeValueAsString(task);
        } catch (JsonProcessingException error) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "无法保存 Compose 模板参数");
        }
    }

    static BizException invalid(String message) {
        return new BizException(ErrorCode.VALIDATION_ERROR, message);
    }

    enum Operation { PREFLIGHT, CHECKOUT, BUILD_BACKEND, BUILD_FRONTEND, PUSH, MANIFEST }
    record Task(Operation operation, ComposePipelineSpec spec) {}
    private record Step(String name, String executor, Operation operation, int timeout) {
        Step(String name, Operation operation, int timeout) {
            this(name, EXECUTOR, operation, timeout);
        }
    }
    private record Stage(String name, List<Step> steps) {}
}

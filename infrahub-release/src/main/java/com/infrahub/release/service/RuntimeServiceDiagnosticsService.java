package com.infrahub.release.service;

import com.infrahub.common.error.ErrorCode;
import com.infrahub.common.exception.BizException;
import com.infrahub.base.entity.BaseServiceEntity;
import com.infrahub.base.service.BaseServiceEntityService;
import com.infrahub.infra.ssh.SshExecutionRequest;
import com.infrahub.infra.ssh.SshExecutionResult;
import com.infrahub.infra.ssh.SshExecutorService;
import com.infrahub.release.dto.RuntimeServiceInstanceDeployConfigResponse;
import com.infrahub.release.dto.RuntimeServiceInstanceLogsResponse;
import com.infrahub.release.dto.RuntimeServiceInstanceStatusResponse;
import com.infrahub.release.dto.CollectionTargetRef;
import com.infrahub.release.entity.AssetHost;
import com.infrahub.release.entity.AssetHostSshConfig;
import com.infrahub.release.entity.ReleaseArtifact;
import com.infrahub.release.entity.ReleaseVersion;
import com.infrahub.release.entity.RuntimeServiceInstance;
import com.infrahub.release.support.CollectionAdmissionChangedException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class RuntimeServiceDiagnosticsService {

    private static final int DEFAULT_LOG_LINES = 200;
    private static final int MAX_LOG_LINES = 1000;

    private final RuntimeServiceInstanceService runtimeServiceInstanceService;
    private final AssetHostService assetHostService;
    private final AssetHostSshConfigService assetHostSshConfigService;
    private final BaseServiceEntityService baseServiceEntityService;
    private final DeployConfigService deployConfigService;
    private final SecretValueResolver secretValueResolver;
    private final SshExecutorService sshExecutorService;
    private final CollectionDialResolver collectionDialResolver;
    private final ReleaseVersionService releaseVersionService;
    private final ReleaseArtifactService releaseArtifactService;

    public RuntimeServiceInstanceStatusResponse getStatus(Long instanceId) {
        return getStatus(instanceId, null);
    }

    /**
     * 与 {@link #getStatus(Long)} 相同，但把命令执行超时下推到 SSH 执行器。
     *
     * <p>采集侧必须用本重载：调用方只等固定预算，若底层不设超时，超时之后进程还会在后台继续跑，
     * 每轮采集压一层，进程与线程会一直涨。传 {@code null} 表示沿用执行器默认（既有接口调用方走这条）。
     */
    public RuntimeServiceInstanceStatusResponse getStatus(Long instanceId, Long timeoutMillis) {
        RuntimeServiceInstance instance = getRequiredInstance(instanceId);
        SshTarget target = getRequiredSshTarget(instance);
        DeployRuntimeConfig runtimeConfig = loadRuntimeConfig(instance);
        String composeProject = resolveComposeProject(instance);
        SshExecutionResult result = sshExecutorService.execute(
                buildRequest(target, buildStatusCommand(runtimeConfig, composeProject), timeoutMillis));
        return parseStatus(instance, result, composeProject);
    }

    /**
     * **采集专用**的状态探测：按准入快照拨号，不按当前库状态重新决定拨到哪里。
     *
     * <p>与 {@link #getStatus(Long, Long)} 的区别是刻意保留的、也是本方法存在的全部理由：
     * <ul>
     *   <li>手工诊断接口回答的是"**现在**这台机器怎么样"，因此它按当前绑定的主机与当前启用的
     *       SSH 配置去连，这是对的 —— 运维就是要连现在生效的那一条；</li>
     *   <li>采集接口回答的是"采样时批准的那次准入，执行结果如何"。因此端点与认证材料都必须
     *       来自快照：实例改绑主机、改服务/环境、改类型，主机改址，连接配置被替换/停用/改端口，
     *       任何一项对不上就抛 {@link CollectionAdmissionChangedException} 取消本轮，
     *       绝不把"据此实例身份解析出的新配置"跑到"旧主机"上去，也不把新服务/新环境的诊断参数
     *       套到旧实例上。</li>
     * </ul>
     *
     * <p>命令集与手工接口完全一致（同一份 {@code buildStatusCommand}），不因本方法而扩展远端能力；
     * 命令超时同样下推给执行器。
     */
    public RuntimeServiceInstanceStatusResponse getStatusForCollection(CollectionTargetRef ref, Long timeoutMillis) {
        RuntimeServiceInstance instance = runtimeServiceInstanceService.getById(ref.targetId());
        if (instance == null) {
            throw new CollectionAdmissionChangedException("采样后运行实例已被删除(id=" + ref.targetId() + ")，本轮取消");
        }
        requireSame("主机绑定", instance.getHostId(), ref.hostId(), ref);
        requireSame("实例类型", instance.getInstanceType(), ref.instanceType(), ref);
        requireSame("所属服务", instance.getServiceId(), ref.serviceId(), ref);
        requireSame("所属环境", instance.getEnvId(), ref.envId(), ref);

        // 诊断参数按**快照里那份实例身份**解析，而不是按库里当前的身份
        DeployRuntimeConfig runtimeConfig = resolveRuntimeConfig(ref.serviceId(), ref.envId()).config();
        String composeProject = resolveComposeProject(instance);
        SshExecutionResult result = sshExecutorService.execute(collectionDialResolver.buildRequest(
                ref, buildStatusCommand(runtimeConfig, composeProject), timeoutMillis));
        return parseStatus(instance, result, composeProject);
    }

    private String resolveComposeProject(RuntimeServiceInstance instance) {
        if (!"DOCKER_COMPOSE".equalsIgnoreCase(instance.getInstanceType()) && !hasComposeArtifact(instance)) {
            return null;
        }
        String project = instance.getInstanceName();
        if (project == null || !project.matches("[a-z0-9][a-z0-9_-]*")) {
            throw new BizException(ErrorCode.VALIDATION_ERROR,
                    "Compose 实例名称必须与部署时的 Compose 项目名一致");
        }
        return project;
    }

    private boolean hasComposeArtifact(RuntimeServiceInstance instance) {
        // 旧登记记录的类型可能仍为 SPRING_BOOT，使用正在运行的版本制品识别。
        ReleaseVersion version = instance.getVersionId() == null ? null : releaseVersionService.getById(instance.getVersionId());
        ReleaseArtifact artifact = version == null || version.getArtifactId() == null
                ? null : releaseArtifactService.getById(version.getArtifactId());
        return artifact != null && "DOCKER_COMPOSE".equalsIgnoreCase(artifact.getArtifactType());
    }

    /** 快照身份与当前实例身份必须逐项相同；不同即本轮取消（不是失败，也不是"重新绑定后继续"）。 */
    private static void requireSame(String what, Object current, Object sampled, CollectionTargetRef ref) {
        if (!Objects.equals(current, sampled)) {
            throw new CollectionAdmissionChangedException(
                    "采样后运行实例的" + what + "已变更(id=" + ref.targetId() + ")，本轮取消");
        }
    }

    private RuntimeServiceInstanceStatusResponse parseStatus(RuntimeServiceInstance instance, SshExecutionResult result,
                                                             String composeProject) {
        RuntimeServiceInstanceStatusResponse response = new RuntimeServiceInstanceStatusResponse();
        fillInstanceFields(response, instance);
        response.setCheckedAt(OffsetDateTime.now());
        response.setStdout(defaultString(result.getStdout()));
        response.setStderr(defaultString(result.getStderr()));
        response.setPid(extractMarker(result.getStdout(), "__INFRAHUB_PID__"));
        String runningMarker = extractMarker(result.getStdout(), "__INFRAHUB_RUNNING__");
        boolean observed = result.isSuccess() && ("true".equals(runningMarker) || "false".equals(runningMarker));
        response.setRunning(observed && "true".equals(runningMarker));
        response.setInstanceStatus(observed ? (response.isRunning() ? "RUNNING" : "STOPPED") : "UNKNOWN");
        response.setComposeProject(composeProject);
        response.setPortListening(Boolean.parseBoolean(extractMarker(result.getStdout(), "__INFRAHUB_PORT_LISTENING__")));
        response.setHealthBody(extractBetween(result.getStdout(), "__INFRAHUB_HEALTH_BEGIN__", "__INFRAHUB_HEALTH_END__").trim());

        String healthExit = extractMarker(result.getStdout(), "__INFRAHUB_HEALTH_EXIT__");
        boolean healthUp = "0".equals(healthExit) && response.getHealthBody().matches("(?s).*\"status\"\\s*:\\s*\"UP\".*");
        String composeHealth = extractMarker(result.getStdout(), "__INFRAHUB_COMPOSE_HEALTH__");
        String healthStatus = "UNKNOWN";
        if (response.isRunning()) {
            if (!healthUp || "DOWN".equals(composeHealth)) {
                healthStatus = "DOWN";
            } else if (composeProject == null || "UP".equals(composeHealth)) {
                healthStatus = "UP";
            }
        }
        response.setHealthStatus(healthStatus);
        response.setErrorMessage(buildStatusError(result, observed, healthStatus));
        return response;
    }

    public RuntimeServiceInstanceLogsResponse getLogs(Long instanceId, Integer requestedLines) {
        RuntimeServiceInstance instance = getRequiredInstance(instanceId);
        SshTarget target = getRequiredSshTarget(instance);
        DeployRuntimeConfig runtimeConfig = loadRuntimeConfig(instance);
        int lines = normalizeLines(requestedLines);
        SshExecutionResult result = sshExecutorService.execute(buildRequest(target, buildLogCommand(runtimeConfig, lines)));

        RuntimeServiceInstanceLogsResponse response = new RuntimeServiceInstanceLogsResponse();
        fillInstanceFields(response, instance);
        response.setFetchedAt(OffsetDateTime.now());
        response.setSuccess(result.isSuccess());
        response.setLines(lines);
        response.setLogPath(runtimeConfig.logFile());
        response.setContent(defaultString(result.getStdout()));
        response.setStdout(defaultString(result.getStdout()));
        response.setStderr(defaultString(result.getStderr()));
        response.setErrorMessage(result.isSuccess() ? "" : firstNonBlank(result.getStderr(), result.getStdout(), "读取运行日志失败"));
        return response;
    }

    public RuntimeServiceInstanceDeployConfigResponse getDeployConfig(Long instanceId) {
        RuntimeServiceInstance instance = getRequiredInstance(instanceId);
        ResolvedDeployRuntimeConfig resolved = resolveRuntimeConfig(instance);

        RuntimeServiceInstanceDeployConfigResponse response = new RuntimeServiceInstanceDeployConfigResponse();
        fillInstanceFields(response, instance);
        response.setDeployRoot(resolved.config().root());
        response.setDeployPort(resolved.config().port());
        response.setPidFile(resolved.config().pidFile());
        response.setLogFile(resolved.config().logFile());
        response.setHealthUrl(resolved.config().healthUrl());
        response.setSource(resolved.source());
        return response;
    }

    public List<RuntimeServiceInstance> listByServiceAndEnv(Long serviceId, Long envId) {
        return runtimeServiceInstanceService.listByServiceAndEnv(serviceId, envId);
    }

    private RuntimeServiceInstance getRequiredInstance(Long instanceId) {
        RuntimeServiceInstance instance = runtimeServiceInstanceService.getById(instanceId);
        if (instance == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "运行实例不存在(id=" + instanceId + ")，请检查实例是否已删除");
        }
        if (instance.getHostId() == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "运行实例(id=" + instanceId + ")未绑定目标主机，请先配置主机");
        }
        return instance;
    }

    private SshTarget getRequiredSshTarget(RuntimeServiceInstance instance) {
        AssetHost host = assetHostService.getById(instance.getHostId());
        if (host == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "目标主机不存在(hostId=" + instance.getHostId() + ")，请检查主机配置");
        }
        AssetHostSshConfig sshConfig = assetHostSshConfigService.getEnabledByHostId(instance.getHostId());
        if (sshConfig == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "目标主机(" + host.getIp() + ")未配置 SSH 连接信息，请先在主机管理中配置 SSH 认证");
        }
        return new SshTarget(host, sshConfig);
    }

    private DeployRuntimeConfig loadRuntimeConfig(RuntimeServiceInstance instance) {
        return resolveRuntimeConfig(instance).config();
    }

    private ResolvedDeployRuntimeConfig resolveRuntimeConfig(RuntimeServiceInstance instance) {
        return resolveRuntimeConfig(instance.getServiceId(), instance.getEnvId());
    }

    /**
     * 部署运行时参数的唯一解析点。按 {@code serviceId/envId} 取值而不是按实例对象取，
     * 采集路径才能用**快照里那份身份**解析参数，避免"重新绑定后用新服务/新环境的配置"。
     */
    private ResolvedDeployRuntimeConfig resolveRuntimeConfig(Long serviceId, Long envId) {
        BaseServiceEntity service = baseServiceEntityService.getById(serviceId);
        if (service == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "运行实例关联的服务不存在(serviceId=" + serviceId + ")");
        }
        Map<String, String> deployConfig = deployConfigService.loadDeployConfig(service.getAppId(), envId);
        if (hasDeployRuntimeConfig(deployConfig)) {
            return new ResolvedDeployRuntimeConfig(DeployRuntimeConfig.from(deployConfig), "CONFIG");
        }
        if ("infrahub-web".equals(service.getServiceCode())) {
            return new ResolvedDeployRuntimeConfig(DeployRuntimeConfig.from(deployConfig), "INFRAHUB_DEFAULT");
        }
        throw new BizException(ErrorCode.VALIDATION_ERROR, "服务(" + service.getServiceCode() + ")未配置部署诊断参数，请在环境配置中添加 deploy.root、deploy.port 等参数");
    }

    private static boolean hasDeployRuntimeConfig(Map<String, String> deployConfig) {
        return deployConfig != null
                && (deployConfig.containsKey("deploy.root")
                || deployConfig.containsKey("deploy.port")
                || deployConfig.containsKey("deploy.pidFile")
                || deployConfig.containsKey("deploy.logFile")
                || deployConfig.containsKey("deploy.healthUrl"));
    }

    private SshExecutionRequest buildRequest(SshTarget target, String command) {
        return buildRequest(target, command, null);
    }

    private SshExecutionRequest buildRequest(SshTarget target, String command, Long timeoutMillis) {
        return SshExecutionRequest.builder()
                .host(StringUtils.hasText(target.host().getIp()) ? target.host().getIp() : target.host().getHostname())
                .port(target.sshConfig().getSshPort())
                .username(target.sshConfig().getUsername())
                .authType(target.sshConfig().getAuthType())
                .password(secretValueResolver.resolve(target.sshConfig().getPasswordSecretRefId()))
                .privateKeyPath(secretValueResolver.resolve(target.sshConfig().getPrivateKeySecretRefId()))
                .passphrase(secretValueResolver.resolve(target.sshConfig().getPassphraseSecretRefId()))
                .command(command)
                .timeoutMillis(timeoutMillis)
                .build();
    }

    private static String buildStatusCommand(DeployRuntimeConfig runtimeConfig, String composeProject) {
        String runtimeProbe = composeProject == null ? """
                PID_FILE=%s
                PID=""
                if [ -f "$PID_FILE" ]; then PID="$(cat "$PID_FILE" 2>/dev/null || true)"; fi
                echo "__INFRAHUB_PID__=$PID"
                if [ -n "$PID" ] && ps -p "$PID" >/dev/null 2>&1; then echo "__INFRAHUB_RUNNING__=true"; else echo "__INFRAHUB_RUNNING__=false"; fi
                """.formatted(shellQuote(runtimeConfig.pidFile())) : buildComposeProbe(composeProject);
        return runtimeProbe + """
                PORT=%s
                HEALTH_URL=%s
                if ss -ltnp 2>/dev/null | grep -q ":${PORT} "; then echo "__INFRAHUB_PORT_LISTENING__=true"; else echo "__INFRAHUB_PORT_LISTENING__=false"; fi
                echo "__INFRAHUB_HEALTH_BEGIN__"
                curl -fsS -- "$HEALTH_URL" 2>&1
                HEALTH_EXIT=$?
                echo
                echo "__INFRAHUB_HEALTH_END__"
                echo "__INFRAHUB_HEALTH_EXIT__=$HEALTH_EXIT"
                exit 0
                """.formatted(
                shellQuote(String.valueOf(runtimeConfig.port())),
                shellQuote(runtimeConfig.healthUrl())
        );
    }

    private static String buildComposeProbe(String project) {
        return """
                COMPOSE_PROJECT=%s
                IDS=$(docker container ls -aq --filter "label=com.docker.compose.project=$COMPOSE_PROJECT" \\
                    --filter 'label=com.docker.compose.oneoff=False') || exit 1
                RUNNING=false
                COMPOSE_HEALTH=UP
                for ID in $IDS; do
                    STATE=$(docker inspect --format '{{.State.Status}} {{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$ID") || exit 1
                    case "$STATE" in
                        'running healthy'|'running none') RUNNING=true ;;
                        'running starting')
                            RUNNING=true
                            if [ "$COMPOSE_HEALTH" = UP ]; then COMPOSE_HEALTH=UNKNOWN; fi ;;
                        'running '*|'restarting '*|'paused '*) RUNNING=true; COMPOSE_HEALTH=DOWN ;;
                        'created '*|'exited '*|'dead '*|'removing '*) COMPOSE_HEALTH=DOWN ;;
                        *) echo "无法识别 Compose 容器状态" >&2; exit 1 ;;
                    esac
                done
                echo "__INFRAHUB_RUNNING__=$RUNNING"
                echo "__INFRAHUB_COMPOSE_HEALTH__=$COMPOSE_HEALTH"
                """.formatted(shellQuote(project));
    }

    private static String buildLogCommand(DeployRuntimeConfig runtimeConfig, int lines) {
        return "LOG_FILE=" + shellQuote(runtimeConfig.logFile()) + "\n"
                + "if [ ! -f \"$LOG_FILE\" ]; then echo \"日志文件不存在: $LOG_FILE\" >&2; exit 1; fi\n"
                + "tail -n " + lines + " \"$LOG_FILE\"\n";
    }

    private static int normalizeLines(Integer requestedLines) {
        if (requestedLines == null || requestedLines <= 0) {
            return DEFAULT_LOG_LINES;
        }
        return Math.min(requestedLines, MAX_LOG_LINES);
    }

    private static String extractMarker(String stdout, String marker) {
        String prefix = marker + "=";
        for (String line : defaultString(stdout).split("\\R")) {
            if (line.startsWith(prefix)) {
                return line.substring(prefix.length()).trim();
            }
        }
        return "";
    }

    private static String extractBetween(String stdout, String beginMarker, String endMarker) {
        String text = defaultString(stdout);
        int begin = text.indexOf(beginMarker);
        int end = text.indexOf(endMarker);
        if (begin < 0 || end < 0 || end < begin) {
            return "";
        }
        return text.substring(begin + beginMarker.length(), end);
    }

    private static String buildStatusError(SshExecutionResult result, boolean observed, String healthStatus) {
        if (!result.isSuccess()) {
            return "SSH 命令执行失败: " + firstNonBlank(result.getStderr(), result.getStdout(), "连接或执行失败");
        }
        if (!observed) {
            return "探测命令未返回有效的运行状态";
        }
        if ("DOWN".equals(healthStatus)) {
            return "健康检查未通过，服务可能未正常启动";
        }
        return "";
    }

    private static String firstNonBlank(String first, String second, String fallback) {
        if (StringUtils.hasText(first)) {
            return first.trim();
        }
        if (StringUtils.hasText(second)) {
            return second.trim();
        }
        return fallback;
    }

    private static String defaultString(String value) {
        return value == null ? "" : value;
    }

    private static String shellQuote(String value) {
        return "'" + defaultString(value).replace("'", "'\"'\"'") + "'";
    }

    private static void fillInstanceFields(RuntimeServiceInstanceStatusResponse response, RuntimeServiceInstance instance) {
        response.setInstanceId(instance.getId());
        response.setServiceId(instance.getServiceId());
        response.setEnvId(instance.getEnvId());
        response.setHostId(instance.getHostId());
        response.setVersionId(instance.getVersionId());
    }

    private static void fillInstanceFields(RuntimeServiceInstanceLogsResponse response, RuntimeServiceInstance instance) {
        response.setInstanceId(instance.getId());
        response.setServiceId(instance.getServiceId());
        response.setEnvId(instance.getEnvId());
        response.setHostId(instance.getHostId());
        response.setVersionId(instance.getVersionId());
    }

    private static void fillInstanceFields(RuntimeServiceInstanceDeployConfigResponse response, RuntimeServiceInstance instance) {
        response.setInstanceId(instance.getId());
        response.setServiceId(instance.getServiceId());
        response.setEnvId(instance.getEnvId());
        response.setHostId(instance.getHostId());
        response.setVersionId(instance.getVersionId());
    }

    private record SshTarget(AssetHost host, AssetHostSshConfig sshConfig) {
    }

    private record ResolvedDeployRuntimeConfig(DeployRuntimeConfig config, String source) {
    }
}

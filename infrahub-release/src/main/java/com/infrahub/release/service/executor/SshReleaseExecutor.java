package com.infrahub.release.service.executor;

import com.infrahub.base.entity.BaseEnv;
import com.infrahub.base.entity.BaseServiceEntity;
import com.infrahub.common.error.ErrorCode;
import com.infrahub.common.exception.BizException;
import com.infrahub.infra.ssh.SshExecutionRequest;
import com.infrahub.infra.ssh.SshExecutionResult;
import com.infrahub.release.support.DeployExecutionOutcome;
import com.infrahub.infra.ssh.SshExecutorService;
import com.infrahub.release.entity.AssetHost;
import com.infrahub.release.entity.AssetHostSshConfig;
import com.infrahub.release.entity.ReleaseArtifact;
import com.infrahub.release.entity.ReleaseOrder;
import com.infrahub.release.entity.ReleaseVersion;
import com.infrahub.release.service.AssetHostService;
import com.infrahub.release.service.AssetHostSshConfigService;
import com.infrahub.release.service.DeployRuntimeConfig;
import com.infrahub.release.service.ReleasePrecheckResult;
import com.infrahub.release.service.ReleasePrecheckService;
import com.infrahub.release.service.SecretValueResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class SshReleaseExecutor implements ReleaseExecutor {

    private static final String NAME = "SSH";

    private final SshExecutorService sshExecutorService;
    private final SecretValueResolver secretValueResolver;
    private final ReleasePrecheckService releasePrecheckService;
    private final AssetHostSshConfigService assetHostSshConfigService;
    private final AssetHostService assetHostService;

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public ValidationResult validate(ReleaseExecutionContext context) {
        if (context.getOrder() == null) {
            return ValidationResult.invalid("发布单不能为空");
        }
        if (context.getService() == null) {
            return ValidationResult.invalid("服务不能为空");
        }
        if (context.getEnv() == null) {
            return ValidationResult.invalid("环境不能为空");
        }
        if (context.getVersion() == null) {
            return ValidationResult.invalid("版本不能为空");
        }
        if (context.getArtifact() == null) {
            return ValidationResult.invalid("制品不能为空");
        }
        if (context.getDeployConfig() == null) {
            return ValidationResult.invalid("部署配置不能为空");
        }
        String commandTemplate = context.getDeployConfig().get("deploy.command");
        if (!StringUtils.hasText(commandTemplate)) {
            return ValidationResult.invalid("缺少 deploy.command 部署命令配置");
        }
        return ValidationResult.valid();
    }

    @Override
    public ReleaseExecutionResult execute(ReleaseExecutionContext context) {
        ValidationResult validation = validate(context);
        if (!validation.isValid()) {
            return ReleaseExecutionResult.notStarted(validation.errorMessage());
        }

        ReleaseOrder order = context.getOrder();
        BaseServiceEntity service = context.getService();
        BaseEnv env = context.getEnv();
        ReleaseVersion version = context.getVersion();
        ReleaseArtifact artifact = context.getArtifact();
        Map<String, String> deployConfig = context.getDeployConfig();

        Long hostId = context.getHostId();
        if (hostId == null) {
            return ReleaseExecutionResult.notStarted("主机 ID 不能为空");
        }
        AssetHost host = assetHostService.getById(hostId);
        if (host == null) {
            return ReleaseExecutionResult.notStarted("目标主机不存在");
        }

        AssetHostSshConfig sshConfig = assetHostSshConfigService.getEnabledByHostId(hostId);
        if (sshConfig == null) {
            return ReleaseExecutionResult.notStarted("目标主机缺少启用中的 SSH 配置");
        }

        try {
            ReleasePrecheckResult precheckResult = releasePrecheckService.check(
                    host,
                    sshConfig,
                    DeployRuntimeConfig.from(deployConfig),
                    artifact.getArtifactUri()
            );
            if (!precheckResult.isSuccess()) {
                // 前置检查失败：命令没有发出，确定属于“未开始”；由外层写成唯一的部署记录。
                return ReleaseExecutionResult.builder()
                        .success(false)
                        .outcome(DeployExecutionOutcome.NOT_STARTED)
                        .message("前置检查失败")
                        .deployLog(precheckResult.fullLog() + "\n\n前置检查失败: " + precheckResult.errorMessage())
                        .startedAt(precheckResult.executionResult().getStartedAt())
                        .endedAt(precheckResult.executionResult().getEndedAt())
                        .build();
            }

            String commandTemplate = deployConfig.get("deploy.command");
            String command = renderCommand(commandTemplate, service, env, version, artifact, deployConfig);

            SshExecutionResult executionResult = sshExecutorService.execute(
                    SshExecutionRequest.builder()
                            .host(StringUtils.hasText(host.getIp()) ? host.getIp() : host.getHostname())
                            .port(sshConfig.getSshPort())
                            .username(sshConfig.getUsername())
                            .authType(sshConfig.getAuthType())
                            .password(secretValueResolver.resolve(sshConfig.getPasswordSecretRefId()))
                            .privateKeyPath(secretValueResolver.resolve(sshConfig.getPrivateKeySecretRefId()))
                            .passphrase(secretValueResolver.resolve(sshConfig.getPassphraseSecretRefId()))
                            .command(command)
                            .build()
            );

            DeployExecutionOutcome outcome = switch (executionResult.resolvedEvidence()) {
                case SUCCEEDED -> DeployExecutionOutcome.SUCCEEDED;
                case FAILED -> DeployExecutionOutcome.FAILED;
                case UNKNOWN -> DeployExecutionOutcome.UNKNOWN;
            };
            return ReleaseExecutionResult.builder()
                    .success(outcome.isSuccess())
                    .outcome(outcome)
                    .message(outcome.isSuccess() ? "部署执行成功" : "部署未确认成功，请查看部署记录")
                    .stdout(executionResult.getStdout())
                    .stderr(executionResult.getStderr())
                    .deployLog(precheckResult.fullLog() + "\n\n" + executionResult.fullLog())
                    .startedAt(executionResult.getStartedAt())
                    .endedAt(executionResult.getEndedAt())
                    .build();
        } catch (Exception e) {
            // 不输出原始异常文本（可能含命令/主机细节），只保留订单号与异常类型。
            log.error("SSH execution failed for order {}, errorType={}",
                    order.getId(), e.getClass().getSimpleName());
            return ReleaseExecutionResult.builder()
                    .success(false)
                    .outcome(DeployExecutionOutcome.UNKNOWN)
                    .message("执行异常，结果未知")
                    .deployLog("执行异常，结果未知；详见服务端日志")
                    .endedAt(OffsetDateTime.now())
                    .build();
        }
    }

    private String renderCommand(
            String template,
            BaseServiceEntity service,
            BaseEnv env,
            ReleaseVersion version,
            ReleaseArtifact artifact,
            Map<String, String> deployConfig
    ) {
        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("serviceCode", service.getServiceCode());
        variables.put("serviceName", service.getServiceName());
        variables.put("envCode", env.getEnvCode());
        variables.put("envName", env.getEnvName());
        variables.put("versionNo", version.getVersionNo());
        variables.put("artifactUri", artifact.getArtifactUri());
        variables.put("artifactChecksum", artifact.getChecksum());
        DeployRuntimeConfig runtimeConfig = DeployRuntimeConfig.from(deployConfig);
        variables.put("deployRoot", runtimeConfig.root());
        variables.put("deployPort", String.valueOf(runtimeConfig.port()));
        variables.put("deployPidFile", runtimeConfig.pidFile());
        variables.put("deployLogFile", runtimeConfig.logFile());
        variables.put("deployHealthUrl", runtimeConfig.healthUrl());
        variables.put("deployPath", deployConfig.getOrDefault("deploy.deploy.path", ""));
        variables.put("backupPath", deployConfig.getOrDefault("deploy.backup.path", ""));
        variables.put("packagePath", deployConfig.getOrDefault("deploy.package.path", ""));

        String command = template;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            command = command.replace("${" + entry.getKey() + "}", entry.getValue() == null ? "" : entry.getValue());
        }
        return command;
    }
}

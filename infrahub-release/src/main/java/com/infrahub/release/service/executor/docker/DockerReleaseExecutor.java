package com.infrahub.release.service.executor.docker;

import com.infrahub.release.dto.DockerExecutionConfig;
import com.infrahub.release.service.executor.ReleaseExecutionContext;
import com.infrahub.release.service.executor.ReleaseExecutionResult;
import com.infrahub.release.service.executor.ReleaseExecutor;
import com.infrahub.release.support.DeployExecutionOutcome;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Docker 执行器实现。
 * 通过 Docker CLI 执行容器部署。
 */
@Slf4j
@Component("dockerReleaseExecutor")
@RequiredArgsConstructor
public class DockerReleaseExecutor implements ReleaseExecutor {

    private static final String EXECUTOR_NAME = "DOCKER";
    private static final int DEFAULT_START_TIMEOUT_SECONDS = 60;

    private final DockerCommandBuilder commandBuilder;

    @Override
    public String getName() {
        return EXECUTOR_NAME;
    }

    @Override
    public ValidationResult validate(ReleaseExecutionContext context) {
        if (context == null) {
            return ValidationResult.invalid("执行上下文不能为空");
        }

        Object executorConfig = context.getExecutorConfig();
        if (executorConfig == null) {
            return ValidationResult.invalid("Docker 执行器配置不能为空");
        }

        DockerExecutionConfig config = parseConfig(executorConfig);
        if (config == null) {
            return ValidationResult.invalid("无法解析 Docker 执行器配置");
        }

        if (!StringUtils.hasText(config.getImage())) {
            return ValidationResult.invalid("Docker 镜像地址不能为空");
        }

        return ValidationResult.valid();
    }

    @Override
    public ReleaseExecutionResult execute(ReleaseExecutionContext context) {
        OffsetDateTime startedAt = OffsetDateTime.now();

        ValidationResult validation = validate(context);
        if (!validation.isValid()) {
            // 配置校验失败：没有执行任何 docker 命令
            return ReleaseExecutionResult.notStarted("配置验证失败: " + validation.errorMessage());
        }

        DockerExecutionConfig config = parseConfig(context.getExecutorConfig());

        try {
            // 1. 停止并删除旧容器（如果存在）
            boolean oldContainerMutationAttempted = StringUtils.hasText(config.getContainerName());
            if (oldContainerMutationAttempted) {
                stopAndRemoveExistingContainer(config);
            }

            // 2. 启动新容器
            List<String> runArgs = commandBuilder.buildRunCommand(config);
            ProcessResult runResult = executeDockerCommand(runArgs, config.getDockerHost());

            if (!runResult.success) {
                // 尝试过 stop/rm 之后启动失败：旧容器可能已被删除、且无法证明它仍在运行，
                // 环境已不是原状态；无论 stop/rm 的退出码如何都按 UNKNOWN 处理。
                // 没有旧容器时，非零退出且进程正常结束才是可证明的失败。
                DeployExecutionOutcome outcome = oldContainerMutationAttempted || runResult.uncertain
                        ? DeployExecutionOutcome.UNKNOWN
                        : DeployExecutionOutcome.FAILED;
                return buildFailureResult("容器启动失败", runResult, startedAt, outcome);
            }

            // 3. 等待健康检查通过
            int timeout = config.getStartTimeoutSeconds() != null
                    ? config.getStartTimeoutSeconds()
                    : DEFAULT_START_TIMEOUT_SECONDS;
            HealthOutcome health = waitForHealthy(config, timeout);

            if (health != HealthOutcome.HEALTHY) {
                // 获取容器日志帮助诊断
                List<String> logsArgs = commandBuilder.buildLogsCommand(config.getContainerName(), 100, false);
                ProcessResult logsResult = executeDockerCommand(logsArgs, config.getDockerHost());
                ProcessResult detail = logsResult.success
                        ? new ProcessResult(false, runResult.stdout, logsResult.stdout, false)
                        : runResult;
                String message = health == HealthOutcome.UNHEALTHY
                        ? "容器健康检查未通过"
                        : "容器健康状态在等待窗口内无法确认";
                // 新容器已经启动：UNHEALTHY 表示“已生效但未达到健康”，属于 PARTIAL（需要人工核对），
                // 不是“完全没生效”的 FAILED；健康状态无法确认则只能是 UNKNOWN。
                DeployExecutionOutcome outcome = health == HealthOutcome.UNHEALTHY
                        ? DeployExecutionOutcome.PARTIAL
                        : DeployExecutionOutcome.UNKNOWN;
                return buildFailureResult(message, detail, startedAt, outcome);
            }

            String successMessage = String.format("容器 %s 启动成功，镜像 %s",
                    config.getContainerName(), config.getImage());
            return ReleaseExecutionResult.builder()
                    .success(true)
                    .message(successMessage)
                    .stdout(runResult.stdout)
                    .outcome(DeployExecutionOutcome.SUCCEEDED)
                    .startedAt(startedAt)
                    .endedAt(OffsetDateTime.now())
                    .build();

        } catch (Exception e) {
            log.error("Docker 执行异常，errorType={}", e.getClass().getSimpleName());
            // 异常发生点不确定（可能在 docker run 之后），不能报成确定失败
            return ReleaseExecutionResult.unknown("Docker 执行异常，结果未知", null, null, startedAt);
        }
    }

    private DockerExecutionConfig parseConfig(Object executorConfig) {
        if (executorConfig instanceof DockerExecutionConfig config) {
            return config;
        }
        return null;
    }

    private void stopAndRemoveExistingContainer(DockerExecutionConfig config) {
        // 停止容器
        List<String> stopArgs = commandBuilder.buildStopCommand(config.getContainerName(), 10);
        executeDockerCommand(stopArgs, config.getDockerHost());

        // 删除容器
        List<String> rmArgs = commandBuilder.buildRemoveCommand(config.getContainerName());
        executeDockerCommand(rmArgs, config.getDockerHost());
    }

    /** 健康等待的三种结局：明确健康、明确不健康、窗口内无法确认（不能当成确定失败）。 */
    private enum HealthOutcome {
        HEALTHY, UNHEALTHY, UNKNOWN
    }

    private HealthOutcome waitForHealthy(DockerExecutionConfig config, int timeoutSeconds) {
        // 如果没有配置健康检查，只检查容器是否在运行
        if (!StringUtils.hasText(config.getHealthCheckCmd())) {
            return waitForRunning(config, timeoutSeconds);
        }

        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(timeoutSeconds);

        while (System.currentTimeMillis() < deadline) {
            try {
                List<String> inspectArgs = commandBuilder.buildInspectHealthCommand(config.getContainerName());
                ProcessResult result = executeDockerCommand(inspectArgs, config.getDockerHost());

                if (result.uncertain) {
                    // 连健康状态都读不到，不能把“读不到”当成“不健康”
                    return HealthOutcome.UNKNOWN;
                }

                if (result.success && result.stdout != null) {
                    String status = result.stdout.trim();
                    if ("healthy".equalsIgnoreCase(status)) {
                        return HealthOutcome.HEALTHY;
                    }
                    if ("unhealthy".equalsIgnoreCase(status)) {
                        return HealthOutcome.UNHEALTHY;
                    }
                }

                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return HealthOutcome.UNKNOWN;
            }
        }

        // 等待窗口结束仍未拿到明确结论：容器可能仍在校验中，不能算确定失败
        return HealthOutcome.UNKNOWN;
    }

    private HealthOutcome waitForRunning(DockerExecutionConfig config, int timeoutSeconds) {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(timeoutSeconds);

        while (System.currentTimeMillis() < deadline) {
            try {
                List<String> psArgs = commandBuilder.buildPsCommand(config.getContainerName());
                ProcessResult result = executeDockerCommand(psArgs, config.getDockerHost());

                if (result.uncertain) {
                    return HealthOutcome.UNKNOWN;
                }

                if (result.success && result.stdout != null) {
                    String status = result.stdout.trim();
                    if (status.startsWith("Up")) {
                        return HealthOutcome.HEALTHY;
                    }
                    if (status.contains("Exited") || status.contains("Dead")) {
                        return HealthOutcome.UNHEALTHY;
                    }
                }

                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return HealthOutcome.UNKNOWN;
            }
        }

        return HealthOutcome.UNKNOWN;
    }

    private ProcessResult executeDockerCommand(List<String> args, String dockerHost) {
        try {
            List<String> command = new ArrayList<>();
            command.add("docker");
            if (StringUtils.hasText(dockerHost)) {
                command.add("-H");
                command.add(dockerHost);
            }
            command.addAll(args);

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);

            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            boolean completed = process.waitFor(5, TimeUnit.MINUTES);
            if (!completed) {
                process.destroyForcibly();
                // 本地 CLI 被强杀，远端 docker daemon 上的动作可能已经发生：结果未知
                return new ProcessResult(false, "", "命令执行超时", true);
            }

            int exitCode = process.exitValue();
            return new ProcessResult(exitCode == 0, output.toString(), "", false);

        } catch (Exception e) {
            // 不输出完整命令（可能含环境变量/凭据）或原始异常文本，只保留 docker 子命令与异常类型。
            log.error("执行 Docker 命令失败: subcommand={}, errorType={}",
                    args.isEmpty() ? null : args.get(0), e.getClass().getSimpleName());
            return new ProcessResult(false, "", "命令执行失败", true);
        }
    }

    private ReleaseExecutionResult buildFailureResult(String message, ProcessResult result,
                                                       OffsetDateTime startedAt,
                                                       DeployExecutionOutcome outcome) {
        return ReleaseExecutionResult.builder()
                .success(false)
                .message(message)
                .stdout(result.stdout)
                .stderr(result.stderr)
                .outcome(outcome)
                .startedAt(startedAt)
                .endedAt(OffsetDateTime.now())
                .build();
    }

    private record ProcessResult(boolean success, String stdout, String stderr, boolean uncertain) {}
}

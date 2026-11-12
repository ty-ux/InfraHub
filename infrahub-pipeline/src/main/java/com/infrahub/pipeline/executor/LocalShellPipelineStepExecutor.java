package com.infrahub.pipeline.executor;

import com.infrahub.common.error.ErrorCode;
import com.infrahub.common.exception.BizException;
import com.infrahub.pipeline.entity.PipelineRun;
import com.infrahub.pipeline.entity.PipelineStep;
import com.infrahub.pipeline.entity.PipelineStepRun;
import com.infrahub.pipeline.service.PipelineStepLogStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class LocalShellPipelineStepExecutor implements PipelineStepExecutor {

    private static final int DEFAULT_TIMEOUT_SECONDS = 300;
    private final PipelineStepLogStore logStore;

    @Override
    public String executorType() {
        return "LOCAL_SHELL";
    }

    @Override
    public PipelineStepExecutionResult execute(PipelineRun run, PipelineStep step, PipelineStepRun stepRun) {
        if (!"LOCAL_SHELL".equalsIgnoreCase(step.getExecutorType())) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "仅支持 LOCAL_SHELL 执行器");
        }
        if (!StringUtils.hasText(step.getScriptContent())) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "LOCAL_SHELL 步骤脚本不能为空");
        }

        List<String> command = isWindows()
                ? List.of("cmd.exe", "/c", step.getScriptContent())
                : List.of("sh", "-c", step.getScriptContent());
        OffsetDateTime startedAt = OffsetDateTime.now();
        Process process = null;
        Path log = null;
        try {
            log = logStore.create(stepRun);
            ProcessBuilder builder = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(log.toFile());
            builder.environment().put("INFRAHUB_RUN_ID", run.getId().toString());
            builder.environment().put("INFRAHUB_ARTIFACT_DESCRIPTOR", logStore.artifactDescriptor(run.getId()).toString());
            process = builder.start();
            process.getOutputStream().close();
            int timeoutSeconds = step.getTimeoutSeconds() == null ? DEFAULT_TIMEOUT_SECONDS : step.getTimeoutSeconds();
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            OffsetDateTime endedAt = OffsetDateTime.now();
            if (!finished) {
                stopProcess(process);
                String error = "LOCAL_SHELL step timed out after " + timeoutSeconds + " seconds";
                appendDiagnostic(log, error);
                return PipelineStepExecutionResult.failure(
                        -1,
                        PipelineStepLogStore.preview(log, 4000),
                        error,
                        startedAt,
                        endedAt
                );
            }

            String output = PipelineStepLogStore.preview(log, 4000);
            int exitCode = process.exitValue();
            if (exitCode == 0) return PipelineStepExecutionResult.success(output, startedAt, endedAt);
            String error = "LOCAL_SHELL exited with code " + exitCode + "\n" + PipelineStepLogStore.tail(log, 950);
            return PipelineStepExecutionResult.failure(exitCode, output, error, startedAt, endedAt);
        } catch (IOException exception) {
            appendDiagnostic(log, "LOCAL_SHELL 执行失败: " + exception.getMessage());
            throw new BizException(ErrorCode.INTERNAL_ERROR, "LOCAL_SHELL 执行失败: " + exception.getMessage());
        } catch (InterruptedException exception) {
            appendDiagnostic(log, "LOCAL_SHELL 执行被中断");
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCode.INTERNAL_ERROR, "LOCAL_SHELL 执行失败: " + exception.getMessage());
        } finally {
            if (process != null && process.isAlive()) {
                stopProcess(process);
            }
        }
    }

    private static void stopProcess(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    private static void appendDiagnostic(Path log, String message) {
        if (log == null) return;
        try {
            Files.writeString(log, "\n" + message + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // The execution result still reports the error if the log cannot be written.
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}

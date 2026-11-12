package com.infrahub.pipeline.executor;

import lombok.Builder;
import lombok.Value;

import java.time.OffsetDateTime;

@Value
@Builder
public class PipelineStepExecutionResult {

    boolean success;
    int exitCode;
    String stdout;
    String stderr;
    OffsetDateTime startedAt;
    OffsetDateTime endedAt;

    public static PipelineStepExecutionResult success(String stdout, OffsetDateTime startedAt, OffsetDateTime endedAt) {
        return PipelineStepExecutionResult.builder()
                .success(true)
                .exitCode(0)
                .stdout(stdout)
                .stderr("")
                .startedAt(startedAt)
                .endedAt(endedAt)
                .build();
    }

    public static PipelineStepExecutionResult failure(
            int exitCode,
            String stdout,
            String stderr,
            OffsetDateTime startedAt,
            OffsetDateTime endedAt
    ) {
        return PipelineStepExecutionResult.builder()
                .success(false)
                .exitCode(exitCode)
                .stdout(stdout)
                .stderr(stderr)
                .startedAt(startedAt)
                .endedAt(endedAt)
                .build();
    }

    public String fullLog() {
        return "stdout:\n" + (stdout == null ? "" : stdout) + "\n\nstderr:\n" + (stderr == null ? "" : stderr);
    }
}

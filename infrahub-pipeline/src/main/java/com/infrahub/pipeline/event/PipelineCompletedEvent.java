package com.infrahub.pipeline.event;

public record PipelineCompletedEvent(
        Long pipelineRunId,
        Long releaseId,
        String runStatus,
        String errorMessage
) {
}

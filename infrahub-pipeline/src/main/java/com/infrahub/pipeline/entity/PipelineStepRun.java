package com.infrahub.pipeline.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName("pipeline_step_run")
public class PipelineStepRun {

    private Long id;
    private Long pipelineRunId;
    private Long stepId;
    private String stageName;
    private String stepName;
    private String runStatus;
    private String executorNode;
    private OffsetDateTime startTime;
    private OffsetDateTime endTime;
    private Long durationMs;
    private String logExcerpt;
    private String errorMessage;
}

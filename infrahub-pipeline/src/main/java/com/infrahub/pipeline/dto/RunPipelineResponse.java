package com.infrahub.pipeline.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class RunPipelineResponse {

    private Long pipelineRunId;
    private String runStatus;
}

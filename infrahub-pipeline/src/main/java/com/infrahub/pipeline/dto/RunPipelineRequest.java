package com.infrahub.pipeline.dto;

import lombok.Data;

@Data
public class RunPipelineRequest {

    private Long envId;
    private Long serviceId;
    private Long versionId;
    private Long releaseId;
}

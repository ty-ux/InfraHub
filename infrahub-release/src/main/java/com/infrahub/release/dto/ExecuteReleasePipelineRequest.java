package com.infrahub.release.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ExecuteReleasePipelineRequest {

    private Long pipelineId;
    private Long envId;
    private Long versionId;
}

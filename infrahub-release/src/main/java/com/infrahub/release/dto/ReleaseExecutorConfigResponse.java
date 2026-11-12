package com.infrahub.release.dto;

import com.infrahub.release.entity.ReleaseExecutorConfig;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
public class ReleaseExecutorConfigResponse {

    private Long id;
    private String executorName;
    private String executorType;
    private Long envId;
    private String configPayload;
    private Boolean enabled;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    public static ReleaseExecutorConfigResponse fromEntity(ReleaseExecutorConfig entity) {
        ReleaseExecutorConfigResponse response = new ReleaseExecutorConfigResponse();
        response.setId(entity.getId());
        response.setExecutorName(entity.getExecutorName());
        response.setExecutorType(entity.getExecutorType());
        response.setEnvId(entity.getEnvId());
        response.setConfigPayload(entity.getConfigPayload());
        response.setEnabled(entity.getEnabled());
        response.setCreatedAt(entity.getCreatedAt());
        response.setUpdatedAt(entity.getUpdatedAt());
        return response;
    }
}
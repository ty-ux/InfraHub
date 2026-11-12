package com.infrahub.release.dto;

import com.infrahub.release.entity.CapacityPolicy;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * Capacity threshold policy response DTO.
 * Contains policy information and associated entity names for display.
 */
@Data
@Builder
public class CapacityPolicyResponse {

    private Long id;
    private String policyName;
    private Long resourcePoolId;
    private Long envId;
    private String metricType;
    private Integer warningThreshold;
    private Integer criticalThreshold;
    private Boolean enabled;
    private String description;
    private Long createdBy;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    // Display fields
    private String resourcePoolName;
    private String envName;

    /**
     * Converts a CapacityPolicy entity to a CapacityPolicyResponse.
     *
     * @param entity the CapacityPolicy entity
     * @return the response DTO, or null if entity is null
     */
    public static CapacityPolicyResponse fromEntity(CapacityPolicy entity) {
        if (entity == null) {
            return null;
        }
        return CapacityPolicyResponse.builder()
                .id(entity.getId())
                .policyName(entity.getPolicyName())
                .resourcePoolId(entity.getResourcePoolId())
                .envId(entity.getEnvId())
                .metricType(entity.getMetricType())
                .warningThreshold(entity.getWarningThreshold())
                .criticalThreshold(entity.getCriticalThreshold())
                .enabled(entity.getEnabled())
                .description(entity.getDescription())
                .createdBy(entity.getCreatedBy())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}

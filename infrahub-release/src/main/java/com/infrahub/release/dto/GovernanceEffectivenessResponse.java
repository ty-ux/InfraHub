package com.infrahub.release.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 治理效果评估响应 DTO.
 * Field names defined in V2/L Contract section 4.4.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GovernanceEffectivenessResponse {

    /**
     * 影响类型
     */
    private String impactType;

    /**
     * 治理前风险等级
     */
    private String beforeRiskLevel;

    /**
     * 治理后风险等级
     */
    private String afterRiskLevel;

    /**
     * 改善数
     */
    private Long improvedCount;

    /**
     * 未变化数
     */
    private Long unchangedCount;

    /**
     * 恶化数
     */
    private Long worsenedCount;

    /**
     * 改善率（0.0-1.0）
     */
    private Double improvementRate;

    /**
     * 样本量
     */
    private Long sampleSize;
}

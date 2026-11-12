package com.infrahub.release.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 工作台概览响应 DTO
 * Contract: v2-stage-p-contract.md Section 3.1
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GovernanceAlertWorkbenchSummaryResponse {

    /**
     * 待处理告警数（状态 OPEN）
     */
    private Long openCount;

    /**
     * 已确认告警数（状态 ACKED）
     */
    private Long ackedCount;

    /**
     * 已解决告警数（状态 RESOLVED）
     */
    private Long resolvedCount;

    /**
     * 分派给我的告警数
     */
    private Long assignedToMeCount;

    /**
     * 逾期告警数
     */
    private Long overdueCount;

    /**
     * 已升级告警数
     */
    private Long escalatedCount;

    /**
     * 严重告警数（等级 CRITICAL）
     */
    private Long criticalCount;

    /**
     * 统计更新时间
     */
    private OffsetDateTime updatedAt;
}
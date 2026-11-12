package com.infrahub.release.dto;

import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 回滚候选版本项 DTO，用于展示单个候选版本信息。
 *
 * Contract: Section 3.5 RollbackCandidateItem
 */
@Data
public class RollbackCandidateItem {

    /**
     * 版本 ID
     */
    private Long versionId;

    /**
     * 版本号
     */
    private String versionNo;

    /**
     * 该版本部署时间（如有）
     */
    private OffsetDateTime deployedAt;

    /**
     * 从该版本回滚的次数
     */
    private Integer rollbackCount;

    /**
     * 是否为当前版本
     */
    private Boolean isCurrent;

    /**
     * 推荐分值（0-100，分数越高越推荐）
     */
    private Integer recommendationScore;

    /**
     * 推荐理由
     */
    private String recommendationReason;
}
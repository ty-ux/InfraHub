package com.infrahub.release.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.List;

/**
 * 回滚候选版本分页响应 DTO。
 *
 * Contract: Section 3.4 RollbackCandidatePageResponse
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RollbackCandidatePageResponse {

    /**
     * 候选版本列表
     */
    private List<RollbackCandidateItem> records;

    /**
     * 总数量
     */
    private Long total;

    /**
     * 当前页码
     */
    private Integer pageNum;

    /**
     * 每页数量
     */
    private Integer pageSize;

    /**
     * 当前版本 ID
     */
    private Long currentVersionId;

    /**
     * 当前版本号
     */
    private String currentVersionNo;

    public static RollbackCandidatePageResponse empty(Integer pageNum, Integer pageSize, Long currentVersionId, String currentVersionNo) {
        return RollbackCandidatePageResponse.builder()
                .records(Collections.emptyList())
                .total(0L)
                .pageNum(pageNum)
                .pageSize(pageSize)
                .currentVersionId(currentVersionId)
                .currentVersionNo(currentVersionNo)
                .build();
    }
}
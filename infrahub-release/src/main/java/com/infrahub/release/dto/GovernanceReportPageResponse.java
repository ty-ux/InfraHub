package com.infrahub.release.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 治理运营报告分页响应
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GovernanceReportPageResponse {

    private List<GovernanceReportResponse> records;

    private Long total;

    private Integer pageNum;

    private Integer pageSize;
}
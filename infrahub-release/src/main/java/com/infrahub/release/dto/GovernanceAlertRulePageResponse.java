package com.infrahub.release.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 告警规则分页响应
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GovernanceAlertRulePageResponse {

    private List<GovernanceAlertRuleResponse> records;

    private Long total;

    private Integer pageNum;

    private Integer pageSize;
}
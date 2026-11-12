package com.infrahub.release.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 通知记录分页响应 DTO
 * Contract: v2-stage-q-contract.md Section 3.4
 *
 * 复用 P 阶段分页结构
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GovernanceAlertNotificationRecordPageResponse {

    private List<GovernanceAlertNotificationRecordResponse> records;

    private Long total;

    private Integer pageNum;

    private Integer pageSize;
}
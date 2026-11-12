package com.infrahub.release.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.infrahub.release.dto.*;
import com.infrahub.release.entity.GovernanceAlertRecord;
import com.infrahub.release.enums.GovernanceAlertLevel;
import com.infrahub.release.enums.GovernanceAlertStatus;
import com.infrahub.release.mapper.GovernanceAlertRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 告警协同工作台服务 - P 阶段实现
 * Contract: v2-stage-p-contract.md Section 2
 *
 * MANUAL_ONLY 约束（Contract section 1.1-1.3）：
 * - 所有 API 均为只读 GET 查询，不产生写副作用
 * - 不调用 SSH/Docker/Kubernetes 执行器
 * - 不自动扩容/清理/发布
 * - 不创建发布单
 * - 不自动分派/升级/ack/resolve/reopen
 * - 不改变 M/N/O 状态机语义
 *
 * 逾期口径（Contract section 6.2）：
 * - assignmentDueAt < now 且 alertStatus != RESOLVED
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GovernanceAlertWorkbenchService {

    private final GovernanceAlertRecordMapper alertRecordMapper;

    /**
     * 获取工作台概览统计
     * Contract: GET /api/assets/governance-ops/alert-workbench/summary
     *
     * GET 查询无写副作用
     *
     * @param currentUserId 当前登录用户 ID
     * @return 工作台概览响应
     */
    @Transactional(readOnly = true)
    public GovernanceAlertWorkbenchSummaryResponse getSummary(Long currentUserId) {
        long openCount = alertRecordMapper.countByStatus(GovernanceAlertStatus.OPEN.name());
        long ackedCount = alertRecordMapper.countByStatus(GovernanceAlertStatus.ACKED.name());
        long resolvedCount = alertRecordMapper.countByStatus(GovernanceAlertStatus.RESOLVED.name());
        long assignedToMeCount = currentUserId != null ? alertRecordMapper.countByAssigneeId(currentUserId) : 0L;
        long overdueCount = alertRecordMapper.countOverdue();
        long escalatedCount = alertRecordMapper.countEscalated();
        long criticalCount = alertRecordMapper.countByLevel(GovernanceAlertLevel.CRITICAL.name());

        return GovernanceAlertWorkbenchSummaryResponse.builder()
                .openCount(openCount)
                .ackedCount(ackedCount)
                .resolvedCount(resolvedCount)
                .assignedToMeCount(assignedToMeCount)
                .overdueCount(overdueCount)
                .escalatedCount(escalatedCount)
                .criticalCount(criticalCount)
                .updatedAt(OffsetDateTime.now())
                .build();
    }

    /**
     * 查询我的待办告警
     * Contract: GET /api/assets/governance-ops/alert-workbench/my-assigned
     *
     * GET 查询无写副作用
     * 语义：assigneeId = 当前登录用户的告警列表
     *
     * @param currentUserId 当前登录用户 ID
     * @param query 查询参数
     * @return 告警记录分页响应
     */
    @Transactional(readOnly = true)
    public GovernanceAlertRecordPageResponse getMyAssigned(Long currentUserId, GovernanceAlertMyAssignedQuery query) {
        Page<GovernanceAlertRecord> page = new Page<>(query.getPageNum(), query.getPageSize());

        // 使用 SQL 级别筛选，确保 total 与 records 一致
        Page<GovernanceAlertRecord> resultPage = alertRecordMapper.queryByAssigneeIdWithFilters(
                page,
                currentUserId,
                query.getStatus(),
                query.getLevel(),
                query.getOverdueOnly()
        );

        List<GovernanceAlertRecordResponse> records = resultPage.getRecords().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());

        GovernanceAlertRecordPageResponse response = new GovernanceAlertRecordPageResponse();
        response.setRecords(records);
        response.setTotal(resultPage.getTotal());
        response.setPageNum((int) resultPage.getCurrent());
        response.setPageSize((int) resultPage.getSize());
        return response;
    }

    /**
     * 查询逾期告警列表
     * Contract: GET /api/assets/governance-ops/alert-workbench/overdue
     *
     * GET 查询无写副作用
     * 逾期定义：assignmentDueAt < now 且 alertStatus != RESOLVED
     *
     * @param query 查询参数
     * @return 告警记录分页响应
     */
    @Transactional(readOnly = true)
    public GovernanceAlertRecordPageResponse getOverdueAlerts(GovernanceAlertOverdueQuery query) {
        Page<GovernanceAlertRecord> page = new Page<>(query.getPageNum(), query.getPageSize());
        Page<GovernanceAlertRecord> resultPage = alertRecordMapper.queryOverdueAlerts(
                page,
                query.getAssigneeId(),
                query.getLevel(),
                query.getEscalated()
        );

        List<GovernanceAlertRecordResponse> records = resultPage.getRecords().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());

        GovernanceAlertRecordPageResponse response = new GovernanceAlertRecordPageResponse();
        response.setRecords(records);
        response.setTotal(resultPage.getTotal());
        response.setPageNum((int) resultPage.getCurrent());
        response.setPageSize((int) resultPage.getSize());
        return response;
    }

    /**
     * 查询已升级告警列表
     * Contract: GET /api/assets/governance-ops/alert-workbench/escalated
     *
     * GET 查询无写副作用
     *
     * @param query 查询参数
     * @return 告警记录分页响应
     */
    @Transactional(readOnly = true)
    public GovernanceAlertRecordPageResponse getEscalatedAlerts(GovernanceAlertEscalatedQuery query) {
        Page<GovernanceAlertRecord> page = new Page<>(query.getPageNum(), query.getPageSize());

        // 使用 SQL 级别筛选，确保 total 与 records 一致
        Page<GovernanceAlertRecord> resultPage = alertRecordMapper.queryEscalatedAlertsWithFilters(
                page,
                query.getEscalationLevel(),
                query.getStatus()
        );

        List<GovernanceAlertRecordResponse> records = resultPage.getRecords().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());

        GovernanceAlertRecordPageResponse response = new GovernanceAlertRecordPageResponse();
        response.setRecords(records);
        response.setTotal(resultPage.getTotal());
        response.setPageNum((int) resultPage.getCurrent());
        response.setPageSize((int) resultPage.getSize());
        return response;
    }

    // ==================== 私有方法 ====================

    /**
     * 转换为响应 DTO
     * Contract: v2-stage-o-contract.md Section 3.9
     */
    private GovernanceAlertRecordResponse toResponse(GovernanceAlertRecord record) {
        GovernanceAlertRecordResponse response = new GovernanceAlertRecordResponse();
        response.setId(record.getId());
        response.setAlertNo(record.getAlertNo());
        response.setRuleId(record.getRuleId());
        response.setRuleName(record.getRuleName());
        response.setMetricType(record.getMetricType() != null ? record.getMetricType().name() : null);
        response.setCurrentValue(record.getCurrentValue());
        response.setThresholdValue(record.getThresholdValue());
        response.setOperator(record.getOperator() != null ? record.getOperator().name() : null);
        response.setAlertLevel(record.getAlertLevel() != null ? record.getAlertLevel().name() : null);
        response.setAlertStatus(record.getAlertStatus() != null ? record.getAlertStatus().name() : null);
        response.setEnvId(record.getEnvId());
        response.setResourcePoolId(record.getResourcePoolId());
        response.setServiceId(record.getServiceId());
        response.setReason(record.getReason());
        response.setCreatedAt(record.getCreatedAt());
        response.setAcknowledgedAt(record.getAcknowledgedAt());
        response.setResolvedAt(record.getResolvedAt());
        response.setAcknowledgedBy(record.getAcknowledgedBy());
        response.setAcknowledgedByName(record.getAcknowledgedByName());
        response.setResolvedBy(record.getResolvedBy());
        response.setResolvedByName(record.getResolvedByName());
        response.setHandleNote(record.getHandleNote());
        response.setEvidencePayload(record.getEvidencePayload());
        response.setAssigneeId(record.getAssigneeId());
        response.setAssigneeName(record.getAssigneeName());
        response.setAssignedAt(record.getAssignedAt());
        response.setAssignmentDueAt(record.getAssignmentDueAt());
        response.setEscalated(record.getEscalated());
        response.setEscalationLevel(record.getEscalationLevel());
        response.setEscalatedAt(record.getEscalatedAt());
        return response;
    }
}
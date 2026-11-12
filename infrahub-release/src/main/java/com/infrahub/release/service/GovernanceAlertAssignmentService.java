package com.infrahub.release.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.infrahub.release.dto.*;
import com.infrahub.release.entity.GovernanceAlertAssignmentRecord;
import com.infrahub.release.entity.GovernanceAlertRecord;
import com.infrahub.release.enums.GovernanceAlertAssignmentAction;
import com.infrahub.release.enums.GovernanceAlertStatus;
import com.infrahub.release.mapper.GovernanceAlertAssignmentRecordMapper;
import com.infrahub.release.mapper.GovernanceAlertRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 告警分派服务 - 分派操作实现
 * Contract: v2-stage-o-contract.md Section 5
 *
 * MANUAL_ONLY 约束（Contract Section 1.1-1.3）：
 * - 不调用 SSH/Docker/Kubernetes 执行器
 * - 不自动扩容/清理/发布
 * - 不自动发送通知
 * - 不自动分派/转派/取消分派/升级
 * - 分派/升级不影响告警状态 (alertStatus)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GovernanceAlertAssignmentService {

    private final GovernanceAlertRecordMapper alertRecordMapper;
    private final GovernanceAlertAssignmentRecordMapper assignmentRecordMapper;
    private final ResourceGovernanceRecordService governanceRecordService;

    // ==================== US-007: 分派操作 ====================

    /**
     * 分派告警责任人 (ASSIGN)
     * Contract: v2-stage-o-contract.md Section 5.1
     *
     * @param alertId 告警ID
     * @param request 分派请求
     * @return 更新后的告警响应
     */
    @Transactional
    public GovernanceAlertRecordResponse assign(Long alertId, GovernanceAlertAssignRequest request) {
        GovernanceAlertRecord record = getAndValidateAlertRecord(alertId);

        // 状态前置条件校验：ASSIGN 要求当前状态为 OPEN 或 ACKED
        validateAssignmentPrecondition(record.getAlertStatus(), "ASSIGN");

        // 幂等性保护：已有 assignee 时，应使用 REASSIGN
        if (record.getAssigneeId() != null) {
            throw new IllegalStateException("Alert already has assignee, use reassign instead: " + alertId);
        }

        OffsetDateTime now = OffsetDateTime.now();
        Long operatorId = resolveOperatorId(request.getOperatorId());
        String operatorName = resolveOperatorName(request.getOperatorName());

        // 更新告警分派字段
        record.setAssigneeId(request.getAssigneeId());
        record.setAssigneeName(request.getAssigneeName());
        record.setAssignedAt(now);
        record.setAssignmentDueAt(request.getDueAt());

        alertRecordMapper.updateById(record);

        // 创建分派记录
        createAssignmentRecord(record, GovernanceAlertAssignmentAction.ASSIGN,
                null, null,
                request.getAssigneeId(), request.getAssigneeName(),
                request.getAssignReason(), request.getDueAt(),
                request.getEvidencePayload(), operatorId, operatorName, now);

        // 记录治理证据链
        recordAssignmentGovernanceEvent(record, "GOVERNANCE_ALERT_ASSIGNED",
                null, null,
                request.getAssigneeId(), request.getAssigneeName(),
                request.getDueAt(), request.getAssignReason(),
                request.getEvidencePayload(), operatorId, operatorName, now);

        return toResponse(record);
    }

    /**
     * 转派告警责任人 (REASSIGN)
     * Contract: v2-stage-o-contract.md Section 5.2
     *
     * @param alertId 告警ID
     * @param request 转派请求
     * @return 更新后的告警响应
     */
    @Transactional
    public GovernanceAlertRecordResponse reassign(Long alertId, GovernanceAlertReassignRequest request) {
        GovernanceAlertRecord record = getAndValidateAlertRecord(alertId);

        // 状态前置条件校验：REASSIGN 要求当前状态为 OPEN 或 ACKED
        validateAssignmentPrecondition(record.getAlertStatus(), "REASSIGN");

        // 幂等性保护：无 assignee 时应使用 ASSIGN
        if (record.getAssigneeId() == null) {
            throw new IllegalStateException("Alert has no assignee, use assign instead: " + alertId);
        }

        // 可选校验：验证原分派人ID
        if (request.getFromAssigneeId() != null && !request.getFromAssigneeId().equals(record.getAssigneeId())) {
            throw new IllegalStateException(
                    String.format("Alert assignee mismatch: expected %d, actual %d",
                            request.getFromAssigneeId(), record.getAssigneeId()));
        }

        OffsetDateTime now = OffsetDateTime.now();
        Long operatorId = resolveOperatorId(request.getOperatorId());
        String operatorName = resolveOperatorName(request.getOperatorName());

        // 保存原分派人信息
        Long fromAssigneeId = record.getAssigneeId();
        String fromAssigneeName = record.getAssigneeName();

        // 更新告警分派字段
        record.setAssigneeId(request.getToAssigneeId());
        record.setAssigneeName(request.getToAssigneeName());
        record.setAssignmentDueAt(request.getDueAt());

        alertRecordMapper.updateById(record);

        // 创建分派记录
        createAssignmentRecord(record, GovernanceAlertAssignmentAction.REASSIGN,
                fromAssigneeId, fromAssigneeName,
                request.getToAssigneeId(), request.getToAssigneeName(),
                request.getReassignReason(), request.getDueAt(),
                request.getEvidencePayload(), operatorId, operatorName, now);

        // 记录治理证据链
        recordAssignmentGovernanceEvent(record, "GOVERNANCE_ALERT_REASSIGNED",
                fromAssigneeId, fromAssigneeName,
                request.getToAssigneeId(), request.getToAssigneeName(),
                request.getDueAt(), request.getReassignReason(),
                request.getEvidencePayload(), operatorId, operatorName, now);

        return toResponse(record);
    }

    /**
     * 取消告警分派 (UNASSIGN)
     * Contract: v2-stage-o-contract.md Section 5.3
     *
     * @param alertId 告警ID
     * @param request 取消分派请求
     * @return 更新后的告警响应
     */
    @Transactional
    public GovernanceAlertRecordResponse unassign(Long alertId, GovernanceAlertUnassignRequest request) {
        GovernanceAlertRecord record = getAndValidateAlertRecord(alertId);

        // 状态前置条件校验：UNASSIGN 要求当前状态为 OPEN 或 ACKED
        validateAssignmentPrecondition(record.getAlertStatus(), "UNASSIGN");

        // 幂等性保护：无 assignee 时不能取消分派
        if (record.getAssigneeId() == null) {
            throw new IllegalStateException("Alert has no assignee, cannot unassign: " + alertId);
        }

        OffsetDateTime now = OffsetDateTime.now();
        Long operatorId = resolveOperatorId(request.getOperatorId());
        String operatorName = resolveOperatorName(request.getOperatorName());

        // 保存原分派人信息
        Long fromAssigneeId = record.getAssigneeId();
        String fromAssigneeName = record.getAssigneeName();

        // 清除告警分派字段
        record.setAssigneeId(null);
        record.setAssigneeName(null);
        record.setAssignedAt(null);
        record.setAssignmentDueAt(null);

        alertRecordMapper.updateById(record);

        // 创建分派记录
        createAssignmentRecord(record, GovernanceAlertAssignmentAction.UNASSIGN,
                fromAssigneeId, fromAssigneeName,
                null, null,
                request.getUnassignReason(), null,
                request.getEvidencePayload(), operatorId, operatorName, now);

        // 记录治理证据链
        recordAssignmentGovernanceEvent(record, "GOVERNANCE_ALERT_UNASSIGNED",
                fromAssigneeId, fromAssigneeName,
                null, null,
                null, request.getUnassignReason(),
                request.getEvidencePayload(), operatorId, operatorName, now);

        return toResponse(record);
    }

    // ==================== US-008: 查询方法 ====================

    /**
     * 分页查询告警分派历史
     * Contract: GET /api/assets/governance-ops/alerts/{id}/assignment-records
     *
     * @param alertId 告警ID
     * @param pageNum 页码
     * @param pageSize 每页数量
     * @return 分派历史分页响应
     */
    @Transactional(readOnly = true)
    public GovernanceAlertAssignmentRecordPageResponse getAssignmentRecords(Long alertId, Integer pageNum, Integer pageSize) {
        // 验证告警记录存在
        getAndValidateAlertRecord(alertId);

        Page<GovernanceAlertAssignmentRecord> page = new Page<>(pageNum, pageSize);
        Page<GovernanceAlertAssignmentRecord> resultPage = assignmentRecordMapper.queryByAlertId(page, alertId);

        List<GovernanceAlertAssignmentRecordResponse> records = resultPage.getRecords().stream()
                .map(this::toAssignmentRecordResponse)
                .collect(Collectors.toList());

        GovernanceAlertAssignmentRecordPageResponse response = new GovernanceAlertAssignmentRecordPageResponse();
        response.setRecords(records);
        response.setTotal(resultPage.getTotal());
        response.setPageNum((int) resultPage.getCurrent());
        response.setPageSize((int) resultPage.getSize());
        return response;
    }

    /**
     * 查询当前用户待处理告警
     * Contract: GET /api/assets/governance-ops/alerts/my-assigned
     *
     * @param operatorId 操作人ID
     * @param pageNum 页码
     * @param pageSize 每页数量
     * @return 告警记录分页响应
     */
    @Transactional(readOnly = true)
    public GovernanceAlertRecordPageResponse getMyAssigned(Long operatorId, Integer pageNum, Integer pageSize) {
        Page<GovernanceAlertRecord> page = new Page<>(pageNum, pageSize);
        Page<GovernanceAlertRecord> resultPage = alertRecordMapper.queryByAssigneeId(page, operatorId);

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
     * 获取并验证告警记录存在
     */
    private GovernanceAlertRecord getAndValidateAlertRecord(Long alertId) {
        if (alertId == null) {
            throw new IllegalArgumentException("Alert ID cannot be null");
        }
        GovernanceAlertRecord record = alertRecordMapper.selectById(alertId);
        if (record == null) {
            throw new IllegalArgumentException("Alert record not found: " + alertId);
        }
        return record;
    }

    /**
     * 验证分派操作前置条件：状态必须为 OPEN 或 ACKED
     */
    private void validateAssignmentPrecondition(GovernanceAlertStatus currentStatus, String action) {
        if (currentStatus == GovernanceAlertStatus.RESOLVED) {
            throw new IllegalStateException(
                    String.format("Cannot %s: alert is already RESOLVED", action));
        }
        if (currentStatus != GovernanceAlertStatus.OPEN && currentStatus != GovernanceAlertStatus.ACKED) {
            throw new IllegalStateException(
                    String.format("Cannot %s: current status is %s, required status is OPEN or ACKED",
                            action, currentStatus));
        }
    }

    /**
     * 解析操作人ID
     */
    private Long resolveOperatorId(Long operatorId) {
        if (operatorId != null) {
            return operatorId;
        }
        // 默认返回 null，实际应用中可从 SecurityContext 获取当前用户
        return null;
    }

    /**
     * 解析操作人姓名
     */
    private String resolveOperatorName(String operatorName) {
        if (operatorName != null) {
            return operatorName;
        }
        return null;
    }

    /**
     * 创建分派记录
     */
    private void createAssignmentRecord(GovernanceAlertRecord alert,
                                         GovernanceAlertAssignmentAction action,
                                         Long fromAssigneeId, String fromAssigneeName,
                                         Long toAssigneeId, String toAssigneeName,
                                         String reason, OffsetDateTime dueAt,
                                         Map<String, Object> evidencePayload,
                                         Long operatorId, String operatorName,
                                         OffsetDateTime createdAt) {
        GovernanceAlertAssignmentRecord assignmentRecord = new GovernanceAlertAssignmentRecord();
        assignmentRecord.setAlertId(alert.getId());
        assignmentRecord.setAlertNo(alert.getAlertNo());
        assignmentRecord.setAction(action.name());
        assignmentRecord.setFromAssigneeId(fromAssigneeId);
        assignmentRecord.setFromAssigneeName(fromAssigneeName);
        assignmentRecord.setToAssigneeId(toAssigneeId);
        assignmentRecord.setToAssigneeName(toAssigneeName);
        assignmentRecord.setReason(reason);
        assignmentRecord.setDueAt(dueAt);
        assignmentRecord.setEvidencePayload(evidencePayload);
        assignmentRecord.setOperatorId(operatorId);
        assignmentRecord.setOperatorName(operatorName);
        assignmentRecord.setCreatedAt(createdAt);

        assignmentRecordMapper.insert(assignmentRecord);
    }

    /**
     * 记录分派治理证据链事件
     * Contract: v2-stage-o-contract.md Section 6
     *
     * Best-Effort: 证据链写入失败不影响主流程
     */
    private void recordAssignmentGovernanceEvent(GovernanceAlertRecord alert,
                                                  String eventType,
                                                  Long fromAssigneeId, String fromAssigneeName,
                                                  Long toAssigneeId, String toAssigneeName,
                                                  OffsetDateTime assignmentDueAt,
                                                  String reason,
                                                  Map<String, Object> evidencePayload,
                                                  Long operatorId,
                                                  String operatorName,
                                                  OffsetDateTime operatedAt) {
        try {
            // beforePayload: 变更前状态
            Map<String, Object> beforePayload = new HashMap<>();
            beforePayload.put("alertId", alert.getId());
            beforePayload.put("alertNo", alert.getAlertNo());
            beforePayload.put("assigneeId", fromAssigneeId);
            beforePayload.put("assigneeName", fromAssigneeName);
            beforePayload.put("escalationLevel", alert.getEscalationLevel());
            beforePayload.put("escalated", alert.getEscalated());

            // afterPayload: 变更后状态和处理信息
            Map<String, Object> afterPayload = new HashMap<>();
            afterPayload.put("alertId", alert.getId());
            afterPayload.put("alertNo", alert.getAlertNo());
            String action = eventType.replace("GOVERNANCE_ALERT_", "");
            afterPayload.put("action", action);
            afterPayload.put("assigneeId", toAssigneeId);
            afterPayload.put("assigneeName", toAssigneeName);
            afterPayload.put("assignmentDueAt", assignmentDueAt != null ? assignmentDueAt.toString() : null);
            afterPayload.put("escalationLevel", alert.getEscalationLevel());
            afterPayload.put("escalatedToId", null);
            afterPayload.put("escalatedToName", null);
            afterPayload.put("reason", reason);
            afterPayload.put("evidencePayload", evidencePayload);
            afterPayload.put("operatorId", operatorId);
            afterPayload.put("operatorName", operatorName);
            afterPayload.put("operatedAt", operatedAt != null ? operatedAt.toString() : null);

            governanceRecordService.record(
                    eventType,
                    alert.getResourcePoolId(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    alert.getEnvId(),
                    alert.getServiceId(),
                    null,
                    "GOVERNANCE_ALERT",
                    alert.getId(),
                    beforePayload,
                    afterPayload,
                    "SUCCESS",
                    null,
                    operatorId
            );
        } catch (Exception e) {
            // Best-Effort: 证据链写入失败不影响主流程
            log.warn("Failed to record governance event for alert {}: {}", alert.getId(), e.getMessage());
        }
    }

    /**
     * 转换为响应 DTO
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

    /**
     * 转换分派记录为响应 DTO
     */
    private GovernanceAlertAssignmentRecordResponse toAssignmentRecordResponse(GovernanceAlertAssignmentRecord record) {
        GovernanceAlertAssignmentRecordResponse response = new GovernanceAlertAssignmentRecordResponse();
        response.setId(record.getId());
        response.setAlertId(record.getAlertId());
        response.setAlertNo(record.getAlertNo());
        response.setAction(record.getAction());
        response.setFromAssigneeId(record.getFromAssigneeId());
        response.setFromAssigneeName(record.getFromAssigneeName());
        response.setToAssigneeId(record.getToAssigneeId());
        response.setToAssigneeName(record.getToAssigneeName());
        response.setReason(record.getReason());
        response.setDueAt(record.getDueAt());
        response.setEvidencePayload(record.getEvidencePayload());
        response.setOperatorId(record.getOperatorId());
        response.setOperatorName(record.getOperatorName());
        response.setCreatedAt(record.getCreatedAt());
        return response;
    }
}

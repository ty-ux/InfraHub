package com.infrahub.release.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.infrahub.release.dto.*;
import com.infrahub.release.entity.GovernanceAlertEscalationRecord;
import com.infrahub.release.entity.GovernanceAlertRecord;
import com.infrahub.release.enums.GovernanceAlertEscalationLevel;
import com.infrahub.release.enums.GovernanceAlertStatus;
import com.infrahub.release.mapper.GovernanceAlertEscalationRecordMapper;
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
 * 告警升级服务 - O 阶段实现
 * Contract: v2-stage-o-contract.md Section 5.4
 *
 * MANUAL_ONLY 约束（Contract section 1.1-1.3）：
 * - 不调用 SSH/Docker/Kubernetes 执行器
 * - 不自动扩容/清理/发布
 * - 不创建发布单
 * - 不自动发送通知（升级不发送任何通知）
 * - 不自动升级（escalate 必须人工触发）
 * - 不改变 M/N 状态机语义（不影响 alertStatus）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GovernanceAlertEscalationService {

    private final GovernanceAlertRecordMapper alertRecordMapper;
    private final GovernanceAlertEscalationRecordMapper escalationRecordMapper;
    private final ResourceGovernanceRecordService governanceRecordService;

    /**
     * 人工升级告警 (ESCALATE)
     * Contract: v2-stage-o-contract.md Section 5.4
     *
     * 前置状态: OPEN 或 ACKED
     * 禁止状态: RESOLVED（已解决告警不可升级）
     * 升级级别: escalationLevel 只能是 L1/L2/L3
     * 不发送通知: 升级只记录证据链，不发送任何通知
     *
     * @param alertId 告警ID
     * @param request 升级请求
     * @return 更新后的告警响应
     */
    @Transactional
    public GovernanceAlertRecordResponse escalate(Long alertId, GovernanceAlertEscalateRequest request) {
        GovernanceAlertRecord record = getAndValidateAlertRecord(alertId);

        // 状态前置条件校验：ESCALATE 要求当前状态为 OPEN 或 ACKED
        validateEscalatePrecondition(record.getAlertStatus());

        // 验证升级级别
        validateEscalationLevel(request.getEscalationLevel());

        OffsetDateTime now = OffsetDateTime.now();
        Long operatorId = resolveOperatorId(request);
        String operatorName = resolveOperatorName(request);

        // 构建 beforePayload（变更前状态）
        Map<String, Object> beforePayload = buildBeforePayload(record);

        // 更新告警记录的升级状态
        record.setEscalated(true);
        record.setEscalationLevel(request.getEscalationLevel());
        record.setEscalatedAt(now);

        alertRecordMapper.updateById(record);

        // 创建升级记录
        createEscalationRecord(record, request, operatorId, operatorName, now);

        // 记录治理证据链（Best-Effort）
        recordGovernanceEvent(record, beforePayload, request, operatorId, operatorName, now);

        return toResponse(record);
    }

    /**
     * 分页查询告警升级历史
     * Contract: GET /api/assets/governance-ops/alerts/{id}/escalation-records
     *
     * GET 查询无写副作用
     *
     * @param alertId 告警ID
     * @param pageNum 页码
     * @param pageSize 每页数量
     * @return 升级历史分页响应
     */
    @Transactional(readOnly = true)
    public GovernanceAlertEscalationRecordPageResponse getEscalationRecords(Long alertId, Integer pageNum, Integer pageSize) {
        // 验证告警记录存在
        getAndValidateAlertRecord(alertId);

        Page<GovernanceAlertEscalationRecord> page = new Page<>(pageNum, pageSize);
        Page<GovernanceAlertEscalationRecord> resultPage = escalationRecordMapper.queryByAlertId(page, alertId);

        List<GovernanceAlertEscalationRecordResponse> records = resultPage.getRecords().stream()
                .map(this::toEscalationRecordResponse)
                .collect(Collectors.toList());

        GovernanceAlertEscalationRecordPageResponse response = new GovernanceAlertEscalationRecordPageResponse();
        response.setRecords(records);
        response.setTotal(resultPage.getTotal());
        response.setPageNum((int) resultPage.getCurrent());
        response.setPageSize((int) resultPage.getSize());
        return response;
    }

    /**
     * 查询已升级告警列表
     * Contract: GET /api/assets/governance-ops/alerts/escalated
     *
     * GET 查询无写副作用
     *
     * @param pageNum 页码
     * @param pageSize 每页数量
     * @return 已升级告警分页响应
     */
    @Transactional(readOnly = true)
    public GovernanceAlertRecordPageResponse getEscalatedAlerts(Integer pageNum, Integer pageSize) {
        Page<GovernanceAlertRecord> page = new Page<>(pageNum, pageSize);
        Page<GovernanceAlertRecord> resultPage = alertRecordMapper.queryEscalatedAlerts(page);

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
     * 验证 ESCALATE 前置条件：OPEN 或 ACKED
     * Contract: v2-stage-o-contract.md Section 5.4
     */
    private void validateEscalatePrecondition(GovernanceAlertStatus currentStatus) {
        if (currentStatus != GovernanceAlertStatus.OPEN && currentStatus != GovernanceAlertStatus.ACKED) {
            throw new IllegalStateException(
                    String.format("Cannot ESCALATE: current status is %s, required status is OPEN or ACKED",
                            currentStatus));
        }
    }

    /**
     * 验证升级级别：只能是 L1/L2/L3
     * Contract: v2-stage-o-contract.md Section 5.4
     */
    private void validateEscalationLevel(String escalationLevel) {
        if (escalationLevel == null) {
            throw new IllegalArgumentException("escalationLevel is required");
        }
        try {
            GovernanceAlertEscalationLevel.valueOf(escalationLevel);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    String.format("Invalid escalationLevel: %s, allowed values are L1/L2/L3", escalationLevel));
        }
    }

    /**
     * 解析操作人ID
     */
    private Long resolveOperatorId(GovernanceAlertEscalateRequest request) {
        if (request != null && request.getOperatorId() != null) {
            return request.getOperatorId();
        }
        // 默认返回 null，实际应用中可从 SecurityContext 获取当前用户
        return null;
    }

    /**
     * 解析操作人姓名
     */
    private String resolveOperatorName(GovernanceAlertEscalateRequest request) {
        if (request != null && request.getOperatorName() != null) {
            return request.getOperatorName();
        }
        return null;
    }

    /**
     * 构建 beforePayload
     * Contract: v2-stage-o-contract.md Section 6.3
     */
    private Map<String, Object> buildBeforePayload(GovernanceAlertRecord record) {
        Map<String, Object> beforePayload = new HashMap<>();
        beforePayload.put("alertId", record.getId());
        beforePayload.put("alertNo", record.getAlertNo());
        beforePayload.put("assigneeId", record.getAssigneeId());
        beforePayload.put("assigneeName", record.getAssigneeName());
        beforePayload.put("escalationLevel", record.getEscalationLevel());
        beforePayload.put("escalated", record.getEscalated() != null ? record.getEscalated() : false);
        return beforePayload;
    }

    /**
     * 创建升级记录
     * Contract: v2-stage-o-contract.md Section 8.3
     */
    private void createEscalationRecord(GovernanceAlertRecord alert,
                                          GovernanceAlertEscalateRequest request,
                                          Long operatorId,
                                          String operatorName,
                                          OffsetDateTime createdAt) {
        GovernanceAlertEscalationRecord escalationRecord = new GovernanceAlertEscalationRecord();
        escalationRecord.setAlertId(alert.getId());
        escalationRecord.setAlertNo(alert.getAlertNo());
        escalationRecord.setEscalationLevel(request.getEscalationLevel());
        escalationRecord.setEscalatedToId(request.getEscalatedToId());
        escalationRecord.setEscalatedToName(request.getEscalatedToName());
        escalationRecord.setEscalateReason(request.getEscalateReason());
        escalationRecord.setEvidencePayload(request.getEvidencePayload());
        escalationRecord.setOperatorId(operatorId);
        escalationRecord.setOperatorName(operatorName);
        escalationRecord.setCreatedAt(createdAt);

        escalationRecordMapper.insert(escalationRecord);
    }

    /**
     * 记录治理证据链事件
     * Contract: v2-stage-o-contract.md Section 6
     *
     * Best-Effort: 证据链写入失败不影响主流程
     */
    private void recordGovernanceEvent(GovernanceAlertRecord alert,
                                         Map<String, Object> beforePayload,
                                         GovernanceAlertEscalateRequest request,
                                         Long operatorId,
                                         String operatorName,
                                         OffsetDateTime escalatedAt) {
        try {
            // 构建 afterPayload（变更后状态）
            Map<String, Object> afterPayload = new HashMap<>();
            afterPayload.put("alertId", alert.getId());
            afterPayload.put("alertNo", alert.getAlertNo());
            afterPayload.put("action", "ESCALATE");
            afterPayload.put("assigneeId", alert.getAssigneeId());
            afterPayload.put("assigneeName", alert.getAssigneeName());
            afterPayload.put("assignmentDueAt", alert.getAssignmentDueAt() != null ? alert.getAssignmentDueAt().toString() : null);
            afterPayload.put("escalationLevel", request.getEscalationLevel());
            afterPayload.put("escalatedToId", request.getEscalatedToId());
            afterPayload.put("escalatedToName", request.getEscalatedToName());
            afterPayload.put("reason", request.getEscalateReason());
            afterPayload.put("evidencePayload", request.getEvidencePayload());
            afterPayload.put("operatorId", operatorId);
            afterPayload.put("operatorName", operatorName);
            afterPayload.put("operatedAt", escalatedAt != null ? escalatedAt.toString() : null);

            governanceRecordService.record(
                    GovernanceEventType.GOVERNANCE_ALERT_ESCALATED,
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
            log.warn("Failed to record governance event for alert escalation {}: {}", alert.getId(), e.getMessage());
        }
    }

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

    /**
     * 转换升级记录为响应 DTO
     * Contract: v2-stage-o-contract.md Section 3.7
     */
    private GovernanceAlertEscalationRecordResponse toEscalationRecordResponse(GovernanceAlertEscalationRecord record) {
        GovernanceAlertEscalationRecordResponse response = new GovernanceAlertEscalationRecordResponse();
        response.setId(record.getId());
        response.setAlertId(record.getAlertId());
        response.setAlertNo(record.getAlertNo());
        response.setEscalationLevel(record.getEscalationLevel());
        response.setEscalatedToId(record.getEscalatedToId());
        response.setEscalatedToName(record.getEscalatedToName());
        response.setEscalateReason(record.getEscalateReason());
        response.setEvidencePayload(record.getEvidencePayload());
        response.setOperatorId(record.getOperatorId());
        response.setOperatorName(record.getOperatorName());
        response.setCreatedAt(record.getCreatedAt());
        return response;
    }
}

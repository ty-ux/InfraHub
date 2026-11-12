package com.infrahub.release.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.infrahub.release.dto.*;
import com.infrahub.release.entity.GovernanceAlertHandlingRecord;
import com.infrahub.release.entity.GovernanceAlertRecord;
import com.infrahub.release.entity.ResourceGovernanceRecord;
import com.infrahub.release.enums.GovernanceAlertHandleAction;
import com.infrahub.release.enums.GovernanceAlertStatus;
import com.infrahub.release.mapper.GovernanceAlertHandlingRecordMapper;
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
 * 告警处理服务 - 状态机实现
 * Contract: v2-stage-n-contract.md Section 5
 *
 * MANUAL_ONLY 约束（Contract section 1.1-1.3）：
 * - 不调用 SSH/Docker/Kubernetes 执行器
 * - 不自动扩容/清理/发布
 * - 不创建发布单
 * - 不自动发送通知
 * - 不自动生成报告
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GovernanceAlertHandlingService {

    private final GovernanceAlertRecordMapper alertRecordMapper;
    private final GovernanceAlertHandlingRecordMapper handlingRecordMapper;
    private final ResourceGovernanceRecordService governanceRecordService;

    /**
     * 确认告警 (ACK)
     * Contract: OPEN -> ACKED
     *
     * @param alertId 告警ID
     * @param request 处理请求
     * @return 更新后的告警响应
     */
    @Transactional
    public GovernanceAlertRecordResponse ack(Long alertId, GovernanceAlertHandleRequest request) {
        GovernanceAlertRecord record = getAndValidateAlertRecord(alertId);

        // 状态前置条件校验：ACK 要求当前状态为 OPEN
        validateStatusTransition(record.getAlertStatus(), GovernanceAlertStatus.OPEN, "ACK");

        // 幂等性保护：已确认的告警不能重复 ACK
        if (record.getAlertStatus() == GovernanceAlertStatus.ACKED) {
            throw new IllegalStateException("Alert already acknowledged: " + alertId);
        }

        // 执行状态转换
        GovernanceAlertStatus fromStatus = record.getAlertStatus();
        GovernanceAlertStatus toStatus = GovernanceAlertStatus.ACKED;

        OffsetDateTime now = OffsetDateTime.now();
        Long operatorId = resolveOperatorId(request);
        String operatorName = resolveOperatorName(request);

        record.setAlertStatus(toStatus);
        record.setAcknowledgedAt(now);
        record.setAcknowledgedBy(operatorId);
        record.setAcknowledgedByName(operatorName);
        record.setHandleNote(request != null ? request.getHandleNote() : null);
        record.setEvidencePayload(request != null ? request.getEvidencePayload() : null);

        alertRecordMapper.updateById(record);

        // 记录处理历史
        createHandlingRecord(record, fromStatus, toStatus, GovernanceAlertHandleAction.ACK,
                request != null ? request.getHandleNote() : null,
                request != null ? request.getEvidencePayload() : null, operatorId, operatorName, now);

        // 记录治理证据链
        recordGovernanceEvent(record, GovernanceEventType.GOVERNANCE_ALERT_ACKED, fromStatus, toStatus,
                GovernanceAlertHandleAction.ACK,
                request != null ? request.getHandleNote() : null,
                request != null ? request.getEvidencePayload() : null,
                operatorId, operatorName, now);

        return toResponse(record);
    }

    /**
     * 解决告警 (RESOLVE)
     * Contract: OPEN -> RESOLVED 或 ACKED -> RESOLVED
     *
     * @param alertId 告警ID
     * @param request 处理请求
     * @return 更新后的告警响应
     */
    @Transactional
    public GovernanceAlertRecordResponse resolve(Long alertId, GovernanceAlertHandleRequest request) {
        GovernanceAlertRecord record = getAndValidateAlertRecord(alertId);

        // 状态前置条件校验：RESOLVE 要求当前状态为 OPEN 或 ACKED
        validateResolvePrecondition(record.getAlertStatus());

        // 幂等性保护：已解决的告警不能重复 RESOLVE
        if (record.getAlertStatus() == GovernanceAlertStatus.RESOLVED) {
            throw new IllegalStateException("Alert already resolved: " + alertId);
        }

        // 执行状态转换
        GovernanceAlertStatus fromStatus = record.getAlertStatus();
        GovernanceAlertStatus toStatus = GovernanceAlertStatus.RESOLVED;

        OffsetDateTime now = OffsetDateTime.now();
        Long operatorId = resolveOperatorId(request);
        String operatorName = resolveOperatorName(request);

        record.setAlertStatus(toStatus);
        record.setResolvedAt(now);
        record.setResolvedBy(operatorId);
        record.setResolvedByName(operatorName);
        record.setHandleNote(request != null ? request.getHandleNote() : null);
        record.setEvidencePayload(request != null ? request.getEvidencePayload() : null);

        alertRecordMapper.updateById(record);

        // 记录处理历史
        createHandlingRecord(record, fromStatus, toStatus, GovernanceAlertHandleAction.RESOLVE,
                request != null ? request.getHandleNote() : null,
                request != null ? request.getEvidencePayload() : null, operatorId, operatorName, now);

        // 记录治理证据链
        recordGovernanceEvent(record, GovernanceEventType.GOVERNANCE_ALERT_RESOLVED, fromStatus, toStatus,
                GovernanceAlertHandleAction.RESOLVE,
                request != null ? request.getHandleNote() : null,
                request != null ? request.getEvidencePayload() : null,
                operatorId, operatorName, now);

        return toResponse(record);
    }

    /**
     * 重开告警 (REOPEN)
     * Contract: ACKED -> OPEN 或 RESOLVED -> OPEN
     *
     * @param alertId 告警ID
     * @param request 处理请求
     * @return 更新后的告警响应
     */
    @Transactional
    public GovernanceAlertRecordResponse reopen(Long alertId, GovernanceAlertHandleRequest request) {
        GovernanceAlertRecord record = getAndValidateAlertRecord(alertId);

        // 状态前置条件校验：REOPEN 要求当前状态为 ACKED 或 RESOLVED
        validateReopenPrecondition(record.getAlertStatus());

        // 执行状态转换
        GovernanceAlertStatus fromStatus = record.getAlertStatus();
        GovernanceAlertStatus toStatus = GovernanceAlertStatus.OPEN;

        OffsetDateTime now = OffsetDateTime.now();
        Long operatorId = resolveOperatorId(request);
        String operatorName = resolveOperatorName(request);

        record.setAlertStatus(toStatus);
        // 重开时清除确认/解决相关字段
        if (fromStatus == GovernanceAlertStatus.ACKED) {
            record.setAcknowledgedAt(null);
            record.setAcknowledgedBy(null);
            record.setAcknowledgedByName(null);
        } else if (fromStatus == GovernanceAlertStatus.RESOLVED) {
            record.setResolvedAt(null);
            record.setResolvedBy(null);
            record.setResolvedByName(null);
        }
        record.setHandleNote(request != null ? request.getHandleNote() : null);
        record.setEvidencePayload(request != null ? request.getEvidencePayload() : null);

        alertRecordMapper.updateById(record);

        // 记录处理历史
        createHandlingRecord(record, fromStatus, toStatus, GovernanceAlertHandleAction.REOPEN,
                request != null ? request.getHandleNote() : null,
                request != null ? request.getEvidencePayload() : null, operatorId, operatorName, now);

        // 记录治理证据链
        recordGovernanceEvent(record, GovernanceEventType.GOVERNANCE_ALERT_REOPENED, fromStatus, toStatus,
                GovernanceAlertHandleAction.REOPEN,
                request != null ? request.getHandleNote() : null,
                request != null ? request.getEvidencePayload() : null,
                operatorId, operatorName, now);

        return toResponse(record);
    }

    /**
     * 获取告警记录详情
     * Contract: GET /api/assets/governance-ops/alerts/{id}
     *
     * @param alertId 告警ID
     * @return 告警详情响应
     */
    @Transactional(readOnly = true)
    public GovernanceAlertRecordResponse getAlertDetail(Long alertId) {
        GovernanceAlertRecord record = getAndValidateAlertRecord(alertId);
        return toResponse(record);
    }

    /**
     * 分页查询告警处理历史
     * Contract: GET /api/assets/governance-ops/alerts/{id}/handling-records
     *
     * @param alertId 告警ID
     * @param pageNum 页码
     * @param pageSize 每页数量
     * @return 处理历史分页响应
     */
    @Transactional(readOnly = true)
    public GovernanceAlertHandlingRecordPageResponse getHandlingRecords(Long alertId, Integer pageNum, Integer pageSize) {
        // 验证告警记录存在
        getAndValidateAlertRecord(alertId);

        Page<GovernanceAlertHandlingRecord> page = new Page<>(pageNum, pageSize);
        Page<GovernanceAlertHandlingRecord> resultPage = handlingRecordMapper.queryByAlertId(page, alertId);

        List<GovernanceAlertHandlingRecordResponse> records = resultPage.getRecords().stream()
                .map(this::toHandlingRecordResponse)
                .collect(Collectors.toList());

        GovernanceAlertHandlingRecordPageResponse response = new GovernanceAlertHandlingRecordPageResponse();
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
     * 验证状态转换前置条件
     */
    private void validateStatusTransition(GovernanceAlertStatus currentStatus,
                                          GovernanceAlertStatus requiredStatus,
                                          String action) {
        if (currentStatus != requiredStatus) {
            throw new IllegalStateException(
                    String.format("Cannot %s: current status is %s, required status is %s",
                            action, currentStatus, requiredStatus));
        }
    }

    /**
     * 验证 RESOLVE 前置条件：OPEN 或 ACKED
     */
    private void validateResolvePrecondition(GovernanceAlertStatus currentStatus) {
        if (currentStatus != GovernanceAlertStatus.OPEN && currentStatus != GovernanceAlertStatus.ACKED) {
            throw new IllegalStateException(
                    String.format("Cannot RESOLVE: current status is %s, required status is OPEN or ACKED",
                            currentStatus));
        }
    }

    /**
     * 验证 REOPEN 前置条件：ACKED 或 RESOLVED
     */
    private void validateReopenPrecondition(GovernanceAlertStatus currentStatus) {
        if (currentStatus != GovernanceAlertStatus.ACKED && currentStatus != GovernanceAlertStatus.RESOLVED) {
            throw new IllegalStateException(
                    String.format("Cannot REOPEN: current status is %s, required status is ACKED or RESOLVED",
                            currentStatus));
        }
    }

    /**
     * 解析操作人ID
     */
    private Long resolveOperatorId(GovernanceAlertHandleRequest request) {
        if (request != null && request.getOperatorId() != null) {
            return request.getOperatorId();
        }
        // 默认返回 null，实际应用中可从 SecurityContext 获取当前用户
        return null;
    }

    /**
     * 解析操作人姓名
     */
    private String resolveOperatorName(GovernanceAlertHandleRequest request) {
        if (request != null && request.getOperatorName() != null) {
            return request.getOperatorName();
        }
        return null;
    }

    /**
     * 创建处理记录
     */
    private void createHandlingRecord(GovernanceAlertRecord alert,
                                      GovernanceAlertStatus fromStatus,
                                      GovernanceAlertStatus toStatus,
                                      GovernanceAlertHandleAction action,
                                      String handleNote,
                                      Map<String, Object> evidencePayload,
                                      Long operatorId,
                                      String operatorName,
                                      OffsetDateTime createdAt) {
        GovernanceAlertHandlingRecord handlingRecord = new GovernanceAlertHandlingRecord();
        handlingRecord.setAlertId(alert.getId());
        handlingRecord.setAlertNo(alert.getAlertNo());
        handlingRecord.setFromStatus(fromStatus.name());
        handlingRecord.setToStatus(toStatus.name());
        handlingRecord.setAction(action.name());
        handlingRecord.setHandleNote(handleNote);
        handlingRecord.setEvidencePayload(evidencePayload);
        handlingRecord.setOperatorId(operatorId);
        handlingRecord.setOperatorName(operatorName);
        handlingRecord.setCreatedAt(createdAt);

        handlingRecordMapper.insert(handlingRecord);
    }

    /**
     * 记录治理证据链事件
     * Contract: v2-stage-n-contract.md Section 6
     *
     * Best-Effort: 证据链写入失败不影响主流程
     */
    private void recordGovernanceEvent(GovernanceAlertRecord alert,
                                       String eventType,
                                       GovernanceAlertStatus fromStatus,
                                       GovernanceAlertStatus toStatus,
                                       GovernanceAlertHandleAction action,
                                       String handleNote,
                                       Map<String, Object> evidencePayload,
                                       Long operatorId,
                                       String operatorName,
                                       OffsetDateTime handledAt) {
        try {
            // beforePayload: 变更前状态
            Map<String, Object> beforePayload = new HashMap<>();
            beforePayload.put("alertId", alert.getId());
            beforePayload.put("alertNo", alert.getAlertNo());
            beforePayload.put("fromStatus", fromStatus.name());

            // afterPayload: 变更后状态和处理信息
            Map<String, Object> afterPayload = new HashMap<>();
            afterPayload.put("alertId", alert.getId());
            afterPayload.put("alertNo", alert.getAlertNo());
            afterPayload.put("toStatus", toStatus.name());
            afterPayload.put("action", action.name());
            afterPayload.put("handleNote", handleNote);
            afterPayload.put("evidencePayload", evidencePayload);
            afterPayload.put("operatorId", operatorId);
            afterPayload.put("operatorName", operatorName);
            afterPayload.put("handledAt", handledAt != null ? handledAt.toString() : null);

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
     * 转换处理记录为响应 DTO
     */
    private GovernanceAlertHandlingRecordResponse toHandlingRecordResponse(GovernanceAlertHandlingRecord record) {
        GovernanceAlertHandlingRecordResponse response = new GovernanceAlertHandlingRecordResponse();
        response.setId(record.getId());
        response.setAlertId(record.getAlertId());
        response.setAlertNo(record.getAlertNo());
        response.setFromStatus(record.getFromStatus());
        response.setToStatus(record.getToStatus());
        response.setAction(record.getAction());
        response.setHandleNote(record.getHandleNote());
        response.setEvidencePayload(record.getEvidencePayload());
        response.setOperatorId(record.getOperatorId());
        response.setOperatorName(record.getOperatorName());
        response.setCreatedAt(record.getCreatedAt());
        return response;
    }
}

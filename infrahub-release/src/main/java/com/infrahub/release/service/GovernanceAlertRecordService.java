package com.infrahub.release.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.infrahub.release.dto.GovernanceAlertRecordPageResponse;
import com.infrahub.release.dto.GovernanceAlertRecordQuery;
import com.infrahub.release.dto.GovernanceAlertRecordResponse;
import com.infrahub.release.entity.GovernanceAlertRecord;
import com.infrahub.release.mapper.GovernanceAlertRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 告警记录查询服务
 * Contract: v2-stage-m-contract.md Section 2.2
 * O 阶段新增字段: v2-stage-o-contract.md Section 8.1
 *
 * GET 查询无写副作用
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GovernanceAlertRecordService {

    private final GovernanceAlertRecordMapper alertRecordMapper;

    /**
     * 分页查询告警记录
     * Contract: GET /api/assets/governance-ops/alerts
     */
    @Transactional(readOnly = true)
    public GovernanceAlertRecordPageResponse queryAlertRecords(GovernanceAlertRecordQuery query) {
        Page<GovernanceAlertRecord> page = new Page<>(query.getPageNum(), query.getPageSize());

        Page<GovernanceAlertRecord> resultPage = alertRecordMapper.queryAlertRecords(
                page,
                query.getMetricType(),
                query.getAlertLevel(),
                query.getAlertStatus(),
                query.getEnvId(),
                query.getResourcePoolId(),
                query.getServiceId(),
                query.getStartTime(),
                query.getEndTime()
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
     * 获取告警记录详情
     */
    @Transactional(readOnly = true)
    public GovernanceAlertRecordResponse getAlertRecordById(Long id) {
        GovernanceAlertRecord record = alertRecordMapper.selectById(id);
        if (record == null) {
            throw new IllegalArgumentException("Alert record not found: " + id);
        }
        return toResponse(record);
    }

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

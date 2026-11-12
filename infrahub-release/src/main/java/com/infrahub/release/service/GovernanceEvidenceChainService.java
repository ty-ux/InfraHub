package com.infrahub.release.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.infrahub.release.dto.GovernanceEvidenceChain;
import com.infrahub.release.dto.GovernanceEvidenceChain.ApprovalRecord;
import com.infrahub.release.dto.GovernanceEvidenceChain.AuditRecord;
import com.infrahub.release.dto.GovernanceEvidenceChain.BusinessSummary;
import com.infrahub.release.dto.GovernanceEvidenceChain.NotificationRecord;
import com.infrahub.release.entity.*;
import com.infrahub.release.mapper.ApprovalNotificationLogMapper;
import com.infrahub.release.mapper.ApprovalRequestMapper;
import com.infrahub.release.mapper.SysAuditLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class GovernanceEvidenceChainService {

    private final ReleaseOrderService releaseOrderService;
    private final ReleaseRollbackOrderService releaseRollbackOrderService;
    private final MonitorAlertRuleService alertRuleService;
    private final MonitorHealthCheckService healthCheckService;
    private final ApprovalRequestMapper approvalRequestMapper;
    private final SysAuditLogMapper sysAuditLogMapper;
    private final ApprovalNotificationLogMapper notificationLogMapper;
    private final ObjectMapper objectMapper;

    public GovernanceEvidenceChain getEvidenceChain(String bizType, Long bizId) {
        GovernanceEvidenceChain chain = new GovernanceEvidenceChain();

        chain.setBusinessSummary(buildBusinessSummary(bizType, bizId));
        chain.setApprovals(buildApprovalRecords(bizType, bizId));
        chain.setAuditLogs(buildAuditRecords(bizType, bizId));
        chain.setNotifications(buildNotificationRecords(bizType, bizId));

        return chain;
    }

    private BusinessSummary buildBusinessSummary(String bizType, Long bizId) {
        BusinessSummary summary = new BusinessSummary();
        summary.setBizType(bizType);
        summary.setBizId(bizId);

        try {
            switch (bizType) {
                case ApprovalRequest.BIZ_TYPE_RELEASE_ORDER -> {
                    ReleaseOrder order = releaseOrderService.getById(bizId);
                    if (order != null) {
                        summary.setDisplayId(order.getOrderNo());
                        summary.setStatus(order.getStatus());
                    }
                }
                case ApprovalRequest.BIZ_TYPE_ROLLBACK_ORDER -> {
                    ReleaseRollbackOrder order = releaseRollbackOrderService.getById(bizId);
                    if (order != null) {
                        summary.setDisplayId(order.getRollbackNo());
                        summary.setStatus(order.getStatus());
                    }
                }
                case ApprovalRequest.BIZ_TYPE_MONITOR_ALERT_RULE -> {
                    MonitorAlertRule rule = alertRuleService.getById(bizId);
                    if (rule != null) {
                        summary.setDisplayId(rule.getRuleName());
                        summary.setStatus(rule.getEnabled() != null && rule.getEnabled() ? "ENABLED" : "DISABLED");
                    }
                }
                case ApprovalRequest.BIZ_TYPE_MONITOR_HEALTH_CHECK -> {
                    MonitorHealthCheck check = healthCheckService.getById(bizId);
                    if (check != null) {
                        summary.setDisplayId(check.getCheckEndpoint());
                        summary.setStatus(check.getEnabled() != null && check.getEnabled() ? "ENABLED" : "DISABLED");
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to build business summary: bizType={}, bizId={}", bizType, bizId, e);
        }

        return summary;
    }

    private List<ApprovalRecord> buildApprovalRecords(String bizType, Long bizId) {
        LambdaQueryWrapper<ApprovalRequest> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ApprovalRequest::getBizType, bizType)
               .eq(ApprovalRequest::getBizId, bizId)
               .orderByDesc(ApprovalRequest::getCreatedAt);

        List<ApprovalRequest> requests = approvalRequestMapper.selectList(wrapper);
        List<ApprovalRecord> records = new ArrayList<>();

        for (ApprovalRequest req : requests) {
            ApprovalRecord record = new ApprovalRecord();
            record.setId(req.getId());
            record.setAction(req.getAction());
            record.setStatus(req.getStatus());
            record.setApplicantId(req.getApplicantId());
            record.setApplyNote(req.getApplyNote());
            record.setApproverId(req.getApproverId());
            record.setApproveNote(req.getApproveNote());
            record.setCreatedAt(req.getCreatedAt() != null ? req.getCreatedAt().toString() : null);
            record.setApprovedAt(req.getApprovedAt() != null ? req.getApprovedAt().toString() : null);
            records.add(record);
        }

        return records;
    }

    private List<AuditRecord> buildAuditRecords(String bizType, Long bizId) {
        List<AuditRecord> records = new ArrayList<>();

        // Query audit logs with the original bizType/bizId
        LambdaQueryWrapper<SysAuditLog> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysAuditLog::getBizType, bizType)
               .eq(SysAuditLog::getBizId, bizId)
               .orderByDesc(SysAuditLog::getCreatedAt);
        List<SysAuditLog> logs = sysAuditLogMapper.selectList(wrapper);
        for (SysAuditLog log : logs) {
            records.add(toAuditRecord(log));
        }

        // Also query audit logs for related approvals (bizType=APPROVAL, bizId=approvalId)
        // where the approval has the same bizType/bizId as the query
        LambdaQueryWrapper<ApprovalRequest> approvalWrapper = new LambdaQueryWrapper<>();
        approvalWrapper.eq(ApprovalRequest::getBizType, bizType)
                       .eq(ApprovalRequest::getBizId, bizId);
        List<ApprovalRequest> approvals = approvalRequestMapper.selectList(approvalWrapper);
        for (ApprovalRequest approval : approvals) {
            LambdaQueryWrapper<SysAuditLog> approvalLogWrapper = new LambdaQueryWrapper<>();
            approvalLogWrapper.eq(SysAuditLog::getBizType, "APPROVAL")
                              .eq(SysAuditLog::getBizId, approval.getId())
                              .orderByDesc(SysAuditLog::getCreatedAt);
            List<SysAuditLog> approvalLogs = sysAuditLogMapper.selectList(approvalLogWrapper);
            for (SysAuditLog log : approvalLogs) {
                records.add(toAuditRecord(log));
            }
        }

        // Sort by occurredAt DESC
        records.sort((a, b) -> {
            if (a.getOccurredAt() == null) return 1;
            if (b.getOccurredAt() == null) return -1;
            return b.getOccurredAt().compareTo(a.getOccurredAt());
        });

        return records;
    }

    private AuditRecord toAuditRecord(SysAuditLog log) {
        AuditRecord record = new AuditRecord();
        record.setId(log.getId());
        record.setAction(log.getAction());
        record.setResult(log.getResult());
        record.setOccurredAt(log.getCreatedAt() != null ? log.getCreatedAt().toString() : null);
        record.setBeforeData(parseJsonToMap(log.getBeforeData()));
        record.setAfterData(parseJsonToMap(log.getAfterData()));
        return record;
    }

    private List<NotificationRecord> buildNotificationRecords(String bizType, Long bizId) {
        LambdaQueryWrapper<ApprovalNotificationLog> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ApprovalNotificationLog::getBizType, bizType)
               .eq(ApprovalNotificationLog::getBizId, bizId)
               .orderByDesc(ApprovalNotificationLog::getCreatedAt);

        List<ApprovalNotificationLog> logs = notificationLogMapper.selectList(wrapper);
        List<NotificationRecord> records = new ArrayList<>();

        for (ApprovalNotificationLog log : logs) {
            NotificationRecord record = new NotificationRecord();
            record.setId(log.getId());
            record.setEventType(log.getEventType());
            record.setStatus(log.getStatus());
            record.setTargetUrl(log.getTargetUrl());
            record.setErrorMessage(log.getErrorMessage());
            record.setCreatedAt(log.getCreatedAt() != null ? log.getCreatedAt().toString() : null);
            records.add(record);
        }

        return records;
    }

    @SuppressWarnings("unchecked")
    private java.util.Map<String, Object> parseJsonToMap(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, java.util.Map.class);
        } catch (Exception e) {
            log.warn("Failed to parse JSON to map: {}", json);
            return null;
        }
    }
}

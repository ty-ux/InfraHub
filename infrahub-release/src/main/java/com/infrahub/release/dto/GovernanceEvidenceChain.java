package com.infrahub.release.dto;

import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
public class GovernanceEvidenceChain {
    private BusinessSummary businessSummary;
    private List<ApprovalRecord> approvals;
    private List<AuditRecord> auditLogs;
    private List<NotificationRecord> notifications;

    @Data
    public static class BusinessSummary {
        private String bizType;
        private Long bizId;
        private String displayId;
        private String status;
        private Map<String, Object> details;
    }

    @Data
    public static class ApprovalRecord {
        private Long id;
        private String action;
        private String status;
        private Long applicantId;
        private String applyNote;
        private Long approverId;
        private String approveNote;
        private String createdAt;
        private String approvedAt;
    }

    @Data
    public static class AuditRecord {
        private Long id;
        private String action;
        private String result;
        private String occurredAt;
        private Map<String, Object> beforeData;
        private Map<String, Object> afterData;
    }

    @Data
    public static class NotificationRecord {
        private Long id;
        private String eventType;
        private String status;
        private String targetUrl;
        private String errorMessage;
        private String createdAt;
    }
}
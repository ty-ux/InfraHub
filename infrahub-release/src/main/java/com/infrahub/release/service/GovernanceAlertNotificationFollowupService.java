package com.infrahub.release.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.infrahub.common.context.UserContext;
import com.infrahub.common.context.UserContextHolder;
import com.infrahub.release.dto.*;
import com.infrahub.release.entity.GovernanceAlertNotificationFollowup;
import com.infrahub.release.entity.GovernanceAlertNotificationRecord;
import com.infrahub.release.entity.GovernanceAlertRecord;
import com.infrahub.release.enums.GovernanceAlertNotificationFollowupStatus;
import com.infrahub.release.enums.GovernanceAlertNotificationFollowupType;
import com.infrahub.release.enums.GovernanceAlertReceiptStatus;
import com.infrahub.release.mapper.GovernanceAlertNotificationFollowupMapper;
import com.infrahub.release.mapper.GovernanceAlertNotificationRecordMapper;
import com.infrahub.release.mapper.GovernanceAlertRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 告警通知跟进服务
 * Contract: v2-stage-r-contract.md
 *
 * MANUAL_ONLY 约束（Contract Section 1.1, 14.1）：
 * - 不自动发送通知
 * - 不自动确认回执
 * - 不自动处理告警
 * - 不做 SSH/Docker/Kubernetes 调用
 * - 不改变 M/N/O/P/Q 阶段状态机语义
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GovernanceAlertNotificationFollowupService {

    private static final int MAX_PAGE_SIZE = 200;
    private static final String FOLLOWUP_NO_PREFIX = "FLW-";
    private static final DateTimeFormatter FOLLOWUP_NO_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final GovernanceAlertNotificationFollowupMapper followupMapper;
    private final GovernanceAlertNotificationRecordMapper notificationRecordMapper;
    private final GovernanceAlertRecordMapper alertRecordMapper;
    private final ResourceGovernanceRecordService governanceRecordService;

    // ==================== US-R-A-01: 创建跟进记录 ====================

    /**
     * 创建跟进记录
     * Contract: POST /api/assets/governance-ops/alert-notification-followups
     *
     * 此接口产生写副作用，创建跟进记录，生成治理证据链事件。
     * MANUAL_ONLY: 显式手动触发，不做自动跟进
     *
     * @param request 跟进请求
     * @return 跟进记录响应
     */
    @Transactional
    public GovernanceAlertNotificationFollowupResponse createFollowup(GovernanceAlertNotificationFollowupRequest request) {
        // 验证通知记录存在
        GovernanceAlertNotificationRecord notificationRecord = getAndValidateNotificationRecord(request.getNotificationRecordId());

        // 获取关联告警记录
        GovernanceAlertRecord alertRecord = getAndValidateAlertRecord(notificationRecord.getAlertRecordId());

        // 生成跟进编号
        String followUpNo = generateFollowupNo();

        LocalDateTime now = LocalDateTime.now();

        // 创建跟进记录
        GovernanceAlertNotificationFollowup followup = new GovernanceAlertNotificationFollowup();
        followup.setFollowUpNo(followUpNo);
        followup.setNotificationRecordId(request.getNotificationRecordId());
        followup.setNotificationNo(notificationRecord.getNotificationNo());
        followup.setAlertRecordId(notificationRecord.getAlertRecordId());
        followup.setAlertNo(notificationRecord.getAlertNo());

        // 解析跟进类型
        try {
            followup.setFollowUpType(GovernanceAlertNotificationFollowupType.valueOf(request.getFollowUpType()));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid follow up type: " + request.getFollowUpType());
        }

        followup.setFollowUpContent(request.getFollowUpContent());
        followup.setFollowUpResult(request.getFollowUpResult());

        // 设置回执状态（默认 PENDING）
        if (request.getReceiptStatus() != null && !request.getReceiptStatus().isEmpty()) {
            try {
                followup.setReceiptStatus(GovernanceAlertReceiptStatus.valueOf(request.getReceiptStatus()));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid receipt status: " + request.getReceiptStatus());
            }
        } else {
            followup.setReceiptStatus(GovernanceAlertReceiptStatus.PENDING);
        }

        // 跟进状态默认为 IN_PROGRESS
        followup.setFollowUpStatus(GovernanceAlertNotificationFollowupStatus.IN_PROGRESS);

        followup.setEvidencePayload(request.getEvidencePayload());
        followup.setCreatedBy(resolveCreatedBy());
        followup.setCreatedByName(resolveCreatedByName());
        followup.setCreatedAt(now);
        followup.setUpdatedAt(now);
        followup.setDeleted(0);

        followupMapper.insert(followup);

        // 记录治理证据链事件 (Best-Effort)
        recordFollowupCreatedEvent(followup, notificationRecord, alertRecord);

        return toResponse(followup);
    }

    // ==================== US-R-A-02: 确认回执 ====================

    /**
     * 确认回执
     * Contract: POST /api/assets/governance-ops/alert-notification-followups/{id}/confirm-receipt
     *
     * 此接口产生写副作用，更新跟进记录的回执状态，生成治理证据链事件。
     * MANUAL_ONLY: 显式手动触发，不做自动确认
     *
     * @param id 跟进记录ID
     * @param request 回执确认请求
     * @return 跟进记录响应
     */
    @Transactional
    public GovernanceAlertNotificationFollowupResponse confirmReceipt(Long id, GovernanceAlertReceiptConfirmRequest request) {
        // 获取跟进记录
        GovernanceAlertNotificationFollowup followup = getAndValidateFollowup(id);

        // Contract 6.3: 状态机验证 - 当前状态必须为 PENDING
        // PENDING -> CONFIRMED / FAILED / NO_RESPONSE
        // CONFIRMED / FAILED / NO_RESPONSE 为终态，不可回退
        if (followup.getReceiptStatus() != GovernanceAlertReceiptStatus.PENDING) {
            throw new IllegalArgumentException(
                "Cannot confirm receipt: current receipt status is " + followup.getReceiptStatus() +
                ", but must be PENDING to transition. Terminal states (CONFIRMED/FAILED/NO_RESPONSE) are final.");
        }

        // 验证回执状态只能为 CONFIRMED 或 FAILED
        GovernanceAlertReceiptStatus newReceiptStatus;
        try {
            newReceiptStatus = GovernanceAlertReceiptStatus.valueOf(request.getReceiptStatus());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid receipt status: " + request.getReceiptStatus());
        }

        if (newReceiptStatus != GovernanceAlertReceiptStatus.CONFIRMED
            && newReceiptStatus != GovernanceAlertReceiptStatus.FAILED) {
            throw new IllegalArgumentException("Receipt status must be CONFIRMED or FAILED");
        }

        LocalDateTime now = LocalDateTime.now();

        // 更新回执状态
        followup.setReceiptStatus(newReceiptStatus);
        followup.setReceiptNote(request.getReceiptNote());
        if (request.getEvidencePayload() != null) {
            followup.setEvidencePayload(request.getEvidencePayload());
        }
        followup.setConfirmedBy(resolveCreatedBy());
        followup.setConfirmedByName(resolveCreatedByName());
        followup.setConfirmedAt(now);
        followup.setUpdatedAt(now);

        followupMapper.updateById(followup);

        // 记录治理证据链事件 (Best-Effort)
        recordReceiptConfirmedEvent(followup);

        return toResponse(followup);
    }

    // ==================== US-R-A-03: 跟进记录查询 ====================

    /**
     * 分页查询跟进记录
     * Contract: GET /api/assets/governance-ops/alert-notification-followups
     *
     * @param query 查询参数
     * @return 跟进记录分页响应
     */
    @Transactional(readOnly = true)
    public GovernanceAlertNotificationFollowupPageResponse queryFollowupRecords(GovernanceAlertNotificationFollowupQuery query) {
        // 限制 pageSize 上限
        int pageSize = Math.min(query.getPageSize(), MAX_PAGE_SIZE);
        int pageNum = query.getPageNum() != null ? query.getPageNum() : 1;

        Page<GovernanceAlertNotificationFollowup> page = new Page<>(pageNum, pageSize);
        Page<GovernanceAlertNotificationFollowup> resultPage = followupMapper.queryFollowupRecords(
                page,
                query.getNotificationRecordId(),
                query.getFollowUpType(),
                query.getReceiptStatus(),
                query.getFollowUpStatus(),
                query.getAlertRecordId()
        );

        List<GovernanceAlertNotificationFollowupResponse> records = resultPage.getRecords().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());

        GovernanceAlertNotificationFollowupPageResponse response = new GovernanceAlertNotificationFollowupPageResponse();
        response.setRecords(records);
        response.setTotal(resultPage.getTotal());
        response.setPageNum((int) resultPage.getCurrent());
        response.setPageSize((int) resultPage.getSize());
        return response;
    }

    /**
     * 查询单条跟进记录详情
     * Contract: GET /api/assets/governance-ops/alert-notification-followups/{id}
     *
     * @param id 跟进记录ID
     * @return 跟进记录响应
     */
    @Transactional(readOnly = true)
    public GovernanceAlertNotificationFollowupResponse getFollowupDetail(Long id) {
        GovernanceAlertNotificationFollowup followup = getAndValidateFollowup(id);
        return toResponse(followup);
    }

    /**
     * 按通知记录查询跟进历史
     * Contract: GET /api/assets/governance-ops/alert-notification-followups/by-notification/{notificationId}
     *
     * @param notificationId 通知记录ID
     * @param pageNum 页码
     * @param pageSize 每页数量
     * @return 跟进记录分页响应
     */
    @Transactional(readOnly = true)
    public GovernanceAlertNotificationFollowupPageResponse queryByNotificationId(Long notificationId, Integer pageNum, Integer pageSize) {
        // 限制 pageSize 上限
        int actualPageSize = Math.min(pageSize != null ? pageSize : 20, MAX_PAGE_SIZE);
        int actualPageNum = pageNum != null ? pageNum : 1;

        Page<GovernanceAlertNotificationFollowup> page = new Page<>(actualPageNum, actualPageSize);
        Page<GovernanceAlertNotificationFollowup> resultPage = followupMapper.queryByNotificationId(page, notificationId);

        List<GovernanceAlertNotificationFollowupResponse> records = resultPage.getRecords().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());

        GovernanceAlertNotificationFollowupPageResponse response = new GovernanceAlertNotificationFollowupPageResponse();
        response.setRecords(records);
        response.setTotal(resultPage.getTotal());
        response.setPageNum((int) resultPage.getCurrent());
        response.setPageSize((int) resultPage.getSize());
        return response;
    }

    // ==================== 私有方法 ====================

    /**
     * 获取并验证通知记录存在
     */
    private GovernanceAlertNotificationRecord getAndValidateNotificationRecord(Long notificationRecordId) {
        if (notificationRecordId == null) {
            throw new IllegalArgumentException("Notification record ID cannot be null");
        }
        GovernanceAlertNotificationRecord record = notificationRecordMapper.selectById(notificationRecordId);
        if (record == null) {
            throw new IllegalArgumentException("Notification record not found: " + notificationRecordId);
        }
        return record;
    }

    /**
     * 获取并验证告警记录存在
     */
    private GovernanceAlertRecord getAndValidateAlertRecord(Long alertRecordId) {
        if (alertRecordId == null) {
            throw new IllegalArgumentException("Alert record ID cannot be null");
        }
        GovernanceAlertRecord record = alertRecordMapper.selectById(alertRecordId);
        if (record == null) {
            throw new IllegalArgumentException("Alert record not found: " + alertRecordId);
        }
        return record;
    }

    /**
     * 获取并验证跟进记录存在
     */
    private GovernanceAlertNotificationFollowup getAndValidateFollowup(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("Followup ID cannot be null");
        }
        GovernanceAlertNotificationFollowup followup = followupMapper.selectById(id);
        if (followup == null) {
            throw new IllegalArgumentException("Followup record not found: " + id);
        }
        return followup;
    }

    /**
     * 生成跟进编号
     * 格式：FUP-yyyyMMdd-序号
     */
    private String generateFollowupNo() {
        Long sequence = followupMapper.getNextFollowupNoSequence();
        String dateStr = LocalDateTime.now().format(FOLLOWUP_NO_DATE_FORMAT);
        return String.format("%s%s-%06d", FOLLOWUP_NO_PREFIX, dateStr, sequence);
    }

    /**
     * 解析创建人ID
     */
    private Long resolveCreatedBy() {
        return UserContextHolder.getUserIdOrDefault(null);
    }

    /**
     * 解析创建人姓名
     */
    private String resolveCreatedByName() {
        UserContext userContext = UserContextHolder.get();
        if (userContext == null) {
            return null;
        }
        if (userContext.getDisplayName() != null) {
            return userContext.getDisplayName();
        }
        return userContext.getUsername();
    }

    /**
     * 记录跟进创建治理证据链事件
     * Contract: v2-stage-r-contract.md Section 7.1
     *
     * Best-Effort: 证据链写入失败不影响主流程
     */
    private void recordFollowupCreatedEvent(GovernanceAlertNotificationFollowup followup,
                                              GovernanceAlertNotificationRecord notification,
                                              GovernanceAlertRecord alertRecord) {
        try {
            Map<String, Object> afterPayload = new LinkedHashMap<>();
            afterPayload.put("followUpNo", followup.getFollowUpNo());
            afterPayload.put("notificationRecordId", followup.getNotificationRecordId());
            afterPayload.put("notificationNo", followup.getNotificationNo());
            afterPayload.put("alertRecordId", followup.getAlertRecordId());
            afterPayload.put("alertNo", followup.getAlertNo());
            afterPayload.put("followUpType", followup.getFollowUpType() != null ? followup.getFollowUpType().name() : null);
            afterPayload.put("followUpContent", followup.getFollowUpContent());
            afterPayload.put("followUpResult", followup.getFollowUpResult());
            afterPayload.put("followUpStatus", followup.getFollowUpStatus() != null ? followup.getFollowUpStatus().name() : null);
            afterPayload.put("receiptStatus", followup.getReceiptStatus() != null ? followup.getReceiptStatus().name() : null);
            afterPayload.put("evidencePayload", followup.getEvidencePayload());
            afterPayload.put("operatorUserId", followup.getCreatedBy());
            afterPayload.put("operatorUserName", followup.getCreatedByName());
            afterPayload.put("operatedAt", followup.getCreatedAt() != null ? followup.getCreatedAt().toString() : null);

            governanceRecordService.record(
                    GovernanceEventType.GOVERNANCE_ALERT_NOTIFICATION_FOLLOWUP_CREATED,
                    alertRecord.getResourcePoolId(),
                    null, null, null, null, null,
                    alertRecord.getEnvId(),
                    alertRecord.getServiceId(),
                    null,
                    "GOVERNANCE_ALERT_NOTIFICATION_FOLLOWUP",
                    followup.getId(),
                    null,
                    afterPayload,
                    "SUCCESS",
                    null,
                    followup.getCreatedBy()
            );
        } catch (Exception e) {
            // Best-Effort: 证据链写入失败不影响主流程
            log.warn("Failed to record governance event for followup {}: {}", followup.getId(), e.getMessage());
        }
    }

    /**
     * 记录回执确认治理证据链事件
     * Contract: v2-stage-r-contract.md Section 7.1
     *
     * Best-Effort: 证据链写入失败不影响主流程
     */
    private void recordReceiptConfirmedEvent(GovernanceAlertNotificationFollowup followup) {
        try {
            Map<String, Object> afterPayload = new LinkedHashMap<>();
            afterPayload.put("followUpNo", followup.getFollowUpNo());
            afterPayload.put("notificationRecordId", followup.getNotificationRecordId());
            afterPayload.put("notificationNo", followup.getNotificationNo());
            afterPayload.put("alertRecordId", followup.getAlertRecordId());
            afterPayload.put("alertNo", followup.getAlertNo());
            afterPayload.put("receiptStatus", followup.getReceiptStatus() != null ? followup.getReceiptStatus().name() : null);
            afterPayload.put("receiptNote", followup.getReceiptNote());
            afterPayload.put("evidencePayload", followup.getEvidencePayload());
            afterPayload.put("operatorUserId", followup.getConfirmedBy());
            afterPayload.put("operatorUserName", followup.getConfirmedByName());
            afterPayload.put("operatedAt", followup.getConfirmedAt() != null ? followup.getConfirmedAt().toString() : null);

            governanceRecordService.record(
                    GovernanceEventType.GOVERNANCE_ALERT_NOTIFICATION_RECEIPT_CONFIRMED,
                    null, null, null, null, null, null, null, null, null,
                    "GOVERNANCE_ALERT_NOTIFICATION_FOLLOWUP",
                    followup.getId(),
                    null,
                    afterPayload,
                    "SUCCESS",
                    null,
                    followup.getConfirmedBy()
            );
        } catch (Exception e) {
            // Best-Effort: 证据链写入失败不影响主流程
            log.warn("Failed to record governance event for receipt confirmation {}: {}", followup.getId(), e.getMessage());
        }
    }

    /**
     * 转换为响应 DTO
     */
    private GovernanceAlertNotificationFollowupResponse toResponse(GovernanceAlertNotificationFollowup followup) {
        return GovernanceAlertNotificationFollowupResponse.builder()
                .id(followup.getId())
                .followUpNo(followup.getFollowUpNo())
                .notificationRecordId(followup.getNotificationRecordId())
                .notificationNo(followup.getNotificationNo())
                .alertRecordId(followup.getAlertRecordId())
                .alertNo(followup.getAlertNo())
                .followUpType(followup.getFollowUpType() != null ? followup.getFollowUpType().name() : null)
                .followUpContent(followup.getFollowUpContent())
                .followUpResult(followup.getFollowUpResult())
                .followUpStatus(followup.getFollowUpStatus() != null ? followup.getFollowUpStatus().name() : null)
                .receiptStatus(followup.getReceiptStatus() != null ? followup.getReceiptStatus().name() : null)
                .receiptNote(followup.getReceiptNote())
                .evidencePayload(followup.getEvidencePayload())
                .createdBy(followup.getCreatedBy())
                .createdByName(followup.getCreatedByName())
                .createdAt(followup.getCreatedAt())
                .confirmedBy(followup.getConfirmedBy())
                .confirmedByName(followup.getConfirmedByName())
                .confirmedAt(followup.getConfirmedAt())
                .build();
    }
}

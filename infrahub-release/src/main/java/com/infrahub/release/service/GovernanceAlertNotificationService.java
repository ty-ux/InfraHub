package com.infrahub.release.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.infrahub.common.context.UserContext;
import com.infrahub.common.context.UserContextHolder;
import com.infrahub.release.dto.*;
import com.infrahub.release.entity.GovernanceAlertNotificationRecord;
import com.infrahub.release.entity.GovernanceAlertRecord;
import com.infrahub.release.enums.GovernanceAlertNotificationChannelType;
import com.infrahub.release.enums.GovernanceAlertNotificationStatus;
import com.infrahub.release.mapper.GovernanceAlertNotificationRecordMapper;
import com.infrahub.release.mapper.GovernanceAlertRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 告警通知服务
 * Contract: v2-stage-q-contract.md
 *
 * MANUAL_ONLY 约束（Contract Section 1.1-1.3）：
 * - 不调用真实 SMTP/Webhook/短信/企业微信/钉钉/飞书发送器
 * - 不自动发送通知
 * - 不自动分派通知人
 * - 不自动升级通知
 * - 不做定时任务自动提醒
 * - 不改变告警记录状态机、处理状态机、分派/升级状态机
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GovernanceAlertNotificationService {

    private static final int MAX_PAGE_SIZE = 200;
    private static final String NOTIFICATION_NO_PREFIX = "NTF-";
    private static final DateTimeFormatter NOTIFICATION_NO_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final GovernanceAlertRecordMapper alertRecordMapper;
    private final GovernanceAlertNotificationRecordMapper notificationRecordMapper;
    private final ResourceGovernanceRecordService governanceRecordService;

    // ==================== US-009: 通知草稿预览 ====================

    /**
     * 生成通知草稿预览
     * Contract: GET /api/assets/governance-ops/alert-notifications/drafts
     *
     * 此接口为只读查询，不产生写副作用。
     *
     * @param alertRecordId 告警记录ID（必填）
     * @param channelType 通知渠道类型（默认 INTERNAL）
     * @param targetUserId 目标用户ID（可选）
     * @param targetUserName 目标用户姓名（可选）
     * @return 通知草稿响应
     */
    @Transactional(readOnly = true)
    public GovernanceAlertNotificationDraftResponse generateDraft(Long alertRecordId, String channelType,
                                                                   Long targetUserId, String targetUserName) {
        // 验证告警记录存在
        GovernanceAlertRecord alertRecord = getAndValidateAlertRecord(alertRecordId);

        // 解析渠道类型，默认 INTERNAL
        GovernanceAlertNotificationChannelType ct = parseChannelType(channelType);

        // 构建草稿响应
        return GovernanceAlertNotificationDraftResponse.builder()
                .alertRecordId(alertRecord.getId())
                .alertTitle(buildAlertTitle(alertRecord))
                .alertLevel(alertRecord.getAlertLevel() != null ? alertRecord.getAlertLevel().name() : null)
                .alertStatus(alertRecord.getAlertStatus() != null ? alertRecord.getAlertStatus().name() : null)
                .assignedTo(alertRecord.getAssigneeId())
                .assignedToName(alertRecord.getAssigneeName())
                .escalatedTo(null) // O 阶段升级目标人存储在升级记录中，主记录不存储
                .escalatedToName(null)
                .channelType(ct.name())
                .targetUserId(targetUserId)
                .targetUserName(targetUserName)
                .subject(buildNotificationSubject(alertRecord))
                .content(buildNotificationContent(alertRecord))
                .suggestedAction(buildSuggestedAction(alertRecord))
                .generatedAt(LocalDateTime.now())
                .build();
    }

    // ==================== US-010: 人工确认已提醒 ====================

    /**
     * 人工确认已提醒
     * Contract: POST /api/assets/governance-ops/alert-notifications/manual-confirm
     *
     * 此接口产生写副作用，创建通知记录，生成治理证据链事件。
     *
     * @param request 人工确认请求
     * @return 通知记录响应
     */
    @Transactional
    public GovernanceAlertNotificationRecordResponse manualConfirm(GovernanceAlertManualNotificationRequest request) {
        // 验证告警记录存在
        GovernanceAlertRecord alertRecord = getAndValidateAlertRecord(request.getAlertRecordId());

        // 解析渠道类型
        GovernanceAlertNotificationChannelType channelType = parseChannelType(request.getChannelType());

        // 生成通知编号
        String notificationNo = generateNotificationNo();

        LocalDateTime now = LocalDateTime.now();

        // 创建通知记录
        GovernanceAlertNotificationRecord record = new GovernanceAlertNotificationRecord();
        record.setNotificationNo(notificationNo);
        record.setAlertRecordId(request.getAlertRecordId());
        record.setAlertNo(alertRecord.getAlertNo());
        record.setAlertTitle(buildAlertTitle(alertRecord));
        record.setChannelType(channelType);
        record.setNotificationStatus(GovernanceAlertNotificationStatus.MANUALLY_CONFIRMED);
        record.setTargetUserId(request.getTargetUserId());
        record.setTargetUserName(request.getTargetUserName());
        record.setSubject(request.getSubject());
        record.setContent(request.getContent());
        record.setNotifyReason(request.getNotifyReason());
        record.setEvidencePayload(request.getEvidencePayload());
        record.setCreatedBy(resolveCreatedBy());
        record.setCreatedByName(resolveCreatedByName());
        record.setCreatedAt(now);
        record.setUpdatedAt(now);
        record.setDeleted(0);

        notificationRecordMapper.insert(record);

        // 记录治理证据链事件
        recordNotificationGovernanceEvent(record, alertRecord);

        return toResponse(record);
    }

    // ==================== US-011: 通知记录查询 ====================

    /**
     * 分页查询通知记录
     * Contract: GET /api/assets/governance-ops/alert-notifications/records
     *
     * @param query 查询参数
     * @return 通知记录分页响应
     */
    @Transactional(readOnly = true)
    public GovernanceAlertNotificationRecordPageResponse queryNotificationRecords(GovernanceAlertNotificationRecordQuery query) {
        // 限制 pageSize 上限
        int pageSize = Math.min(query.getPageSize(), MAX_PAGE_SIZE);
        int pageNum = query.getPageNum() != null ? query.getPageNum() : 1;

        Page<GovernanceAlertNotificationRecord> page = new Page<>(pageNum, pageSize);
        Page<GovernanceAlertNotificationRecord> resultPage = notificationRecordMapper.queryNotificationRecords(
                page,
                query.getAlertRecordId(),
                query.getChannelType(),
                query.getNotificationStatus(),
                query.getTargetUserId()
        );

        List<GovernanceAlertNotificationRecordResponse> records = resultPage.getRecords().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());

        GovernanceAlertNotificationRecordPageResponse response = new GovernanceAlertNotificationRecordPageResponse();
        response.setRecords(records);
        response.setTotal(resultPage.getTotal());
        response.setPageNum((int) resultPage.getCurrent());
        response.setPageSize((int) resultPage.getSize());
        return response;
    }

    /**
     * 查询单条通知记录详情
     * Contract: GET /api/assets/governance-ops/alert-notifications/records/{id}
     *
     * @param id 通知记录ID
     * @return 通知记录响应
     */
    @Transactional(readOnly = true)
    public GovernanceAlertNotificationRecordResponse getNotificationRecordDetail(Long id) {
        GovernanceAlertNotificationRecord record = getAndValidateNotificationRecord(id);
        return toResponse(record);
    }

    // ==================== 私有方法 ====================

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
     * 获取并验证通知记录存在
     */
    private GovernanceAlertNotificationRecord getAndValidateNotificationRecord(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("Notification record ID cannot be null");
        }
        GovernanceAlertNotificationRecord record = notificationRecordMapper.selectById(id);
        if (record == null) {
            throw new IllegalArgumentException("Notification record not found: " + id);
        }
        return record;
    }

    /**
     * 解析渠道类型
     */
    private GovernanceAlertNotificationChannelType parseChannelType(String channelType) {
        if (channelType == null || channelType.isEmpty()) {
            return GovernanceAlertNotificationChannelType.INTERNAL;
        }
        try {
            return GovernanceAlertNotificationChannelType.valueOf(channelType);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid channel type: " + channelType);
        }
    }

    /**
     * 生成通知编号
     * 格式：NTF-yyyyMMdd-序号
     * 遵循项目已有编号风格（参考告警编号 ALT-yyyyMMdd-序号）
     */
    private String generateNotificationNo() {
        Long sequence = notificationRecordMapper.getNextNotificationNoSequence();
        String dateStr = LocalDateTime.now().format(NOTIFICATION_NO_DATE_FORMAT);
        return String.format("%s%s-%06d", NOTIFICATION_NO_PREFIX, dateStr, sequence);
    }

    /**
     * 构建告警标题
     */
    private String buildAlertTitle(GovernanceAlertRecord record) {
        if (record.getRuleName() != null) {
            return record.getRuleName();
        }
        return "告警-" + record.getAlertNo();
    }

    /**
     * 构建通知主题
     */
    private String buildNotificationSubject(GovernanceAlertRecord record) {
        String level = record.getAlertLevel() != null ? record.getAlertLevel().name() : "UNKNOWN";
        return String.format("[%s] 告警通知：%s", level, buildAlertTitle(record));
    }

    /**
     * 构建通知内容
     */
    private String buildNotificationContent(GovernanceAlertRecord record) {
        StringBuilder sb = new StringBuilder();
        sb.append("告警编号：").append(record.getAlertNo()).append("\n");
        sb.append("告警标题：").append(buildAlertTitle(record)).append("\n");
        sb.append("告警等级：").append(record.getAlertLevel() != null ? record.getAlertLevel().name() : "-").append("\n");
        sb.append("告警状态：").append(record.getAlertStatus() != null ? record.getAlertStatus().name() : "-").append("\n");
        if (record.getReason() != null) {
            sb.append("告警原因：").append(record.getReason()).append("\n");
        }
        if (record.getAssigneeName() != null) {
            sb.append("分派人：").append(record.getAssigneeName()).append("\n");
        }
        return sb.toString();
    }

    /**
     * 构建建议操作
     */
    private String buildSuggestedAction(GovernanceAlertRecord record) {
        if (record.getAlertStatus() == null) {
            return "请处理该告警";
        }
        return switch (record.getAlertStatus()) {
            case OPEN -> "请确认并处理该告警";
            case ACKED -> "请继续处理并解决该告警";
            case RESOLVED -> "该告警已解决";
        };
    }

    /**
     * 解析创建人ID
     * 使用项目已有登录用户上下文模式
     */
    private Long resolveCreatedBy() {
        return UserContextHolder.getUserIdOrDefault(null);
    }

    /**
     * 解析创建人姓名
     * 使用项目已有登录用户上下文模式
     */
    private String resolveCreatedByName() {
        UserContext userContext = UserContextHolder.get();
        if (userContext == null) {
            return null;
        }
        // 优先使用 displayName，其次 username
        if (userContext.getDisplayName() != null) {
            return userContext.getDisplayName();
        }
        return userContext.getUsername();
    }

    /**
     * 记录通知治理证据链事件
     * Contract: v2-stage-q-contract.md Section 7
     *
     * Best-Effort: 证据链写入失败不影响主流程
     */
    private void recordNotificationGovernanceEvent(GovernanceAlertNotificationRecord notification,
                                                    GovernanceAlertRecord alertRecord) {
        try {
            // beforePayload: 变更前状态（通知为新建，before 为空）
            Map<String, Object> beforePayload = new HashMap<>();
            beforePayload.put("alertRecordId", notification.getAlertRecordId());
            beforePayload.put("alertNo", notification.getAlertNo());

            // afterPayload: 通知确认信息
            Map<String, Object> afterPayload = new HashMap<>();
            afterPayload.put("notificationNo", notification.getNotificationNo());
            afterPayload.put("alertRecordId", notification.getAlertRecordId());
            afterPayload.put("alertNo", notification.getAlertNo());
            afterPayload.put("channelType", notification.getChannelType() != null ? notification.getChannelType().name() : null);
            afterPayload.put("notificationStatus", notification.getNotificationStatus() != null ? notification.getNotificationStatus().name() : null);
            afterPayload.put("targetUserId", notification.getTargetUserId());
            afterPayload.put("targetUserName", notification.getTargetUserName());
            afterPayload.put("subject", notification.getSubject());
            afterPayload.put("notifyReason", notification.getNotifyReason());
            afterPayload.put("evidencePayload", notification.getEvidencePayload());
            afterPayload.put("operatorUserId", notification.getCreatedBy());
            afterPayload.put("operatorUserName", notification.getCreatedByName());
            afterPayload.put("operatedAt", notification.getCreatedAt() != null ? notification.getCreatedAt().toString() : null);

            governanceRecordService.record(
                    "GOVERNANCE_ALERT_NOTIFICATION_CONFIRMED",
                    alertRecord.getResourcePoolId(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    alertRecord.getEnvId(),
                    alertRecord.getServiceId(),
                    null,
                    "GOVERNANCE_ALERT_NOTIFICATION",
                    notification.getId(),
                    beforePayload,
                    afterPayload,
                    "SUCCESS",
                    null,
                    notification.getCreatedBy()
            );
        } catch (Exception e) {
            // Best-Effort: 证据链写入失败不影响主流程
            log.warn("Failed to record governance event for notification {}: {}", notification.getId(), e.getMessage());
        }
    }

    /**
     * 转换为响应 DTO
     */
    private GovernanceAlertNotificationRecordResponse toResponse(GovernanceAlertNotificationRecord record) {
        return GovernanceAlertNotificationRecordResponse.builder()
                .id(record.getId())
                .notificationNo(record.getNotificationNo())
                .alertRecordId(record.getAlertRecordId())
                .alertNo(record.getAlertNo())
                .alertTitle(record.getAlertTitle())
                .channelType(record.getChannelType() != null ? record.getChannelType().name() : null)
                .notificationStatus(record.getNotificationStatus() != null ? record.getNotificationStatus().name() : null)
                .targetUserId(record.getTargetUserId())
                .targetUserName(record.getTargetUserName())
                .subject(record.getSubject())
                .content(record.getContent())
                .notifyReason(record.getNotifyReason())
                .evidencePayload(record.getEvidencePayload())
                .createdBy(record.getCreatedBy())
                .createdByName(record.getCreatedByName())
                .createdAt(record.getCreatedAt())
                .build();
    }
}
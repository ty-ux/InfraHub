package com.infrahub.release.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.infrahub.common.context.UserContextHolder;
import com.infrahub.common.error.ErrorCode;
import com.infrahub.common.exception.BizException;
import com.infrahub.release.dto.CreateAlertRuleRequest;
import com.infrahub.release.dto.MonitorAlertRuleQuery;
import com.infrahub.release.dto.UpdateAlertRuleRequest;
import com.infrahub.release.entity.MonitorAlertEvent;
import com.infrahub.release.entity.MonitorAlertRule;
import com.infrahub.release.mapper.MonitorAlertRuleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Map;

import static com.infrahub.release.service.MonitorAuditLogService.*;

@Service
@RequiredArgsConstructor
public class MonitorAlertRuleService extends ServiceImpl<MonitorAlertRuleMapper, MonitorAlertRule> {

    private final MonitorAlertEventService alertEventService;
    private final MonitorAuditLogService auditLogService;
    private final ApprovalRequestService approvalRequestService;
    private final ApprovalPolicyService approvalPolicyService;
    private final MonitorTargetGuard monitorTargetGuard;

    public static final String BIZ_TYPE_MONITOR_ALERT_RULE = "MONITOR_ALERT_RULE";

    /**
     * 创建规则。实例型目标先与实例移除登记共用同一行锁并校验目标存在，再插入。
     */
    @Transactional
    public Long create(CreateAlertRuleRequest request) {
        monitorTargetGuard.requireInstanceTargetExists(request.getTargetType(), request.getTargetId());
        MonitorAlertRule rule = new MonitorAlertRule();
        rule.setRuleName(request.getRuleName());
        rule.setTargetType(request.getTargetType());
        rule.setTargetId(request.getTargetId());
        rule.setTargetGroupCode(request.getTargetGroupCode());
        rule.setMetricName(request.getMetricName());
        rule.setCompareOp(request.getCompareOp());
        rule.setThresholdValue(request.getThresholdValue());
        rule.setDurationSeconds(request.getDurationSeconds());
        rule.setLevel(request.getLevel());
        rule.setNotifyChannel(request.getNotifyChannel());
        rule.setEnabled(request.getEnabled());
        rule.setCreatedAt(OffsetDateTime.now());
        save(rule);

        // Record audit log
        auditLogService.recordAlertRule(rule.getId(), ACTION_CREATE_RULE, RESULT_SUCCESS,
                null,
                auditData("ruleId", rule.getId(), "ruleName", rule.getRuleName(),
                        "metricName", rule.getMetricName(), "enabled", rule.getEnabled()));

        return rule.getId();
    }

    /**
     * 修改规则。新目标若是实例，同样先锁定实例行并校验存在，再写回。
     */
    @Transactional
    public void update(Long id, UpdateAlertRuleRequest request) {
        MonitorAlertRule rule = baseMapper.selectForUpdate(id);
        if (rule == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "告警规则不存在: " + id);
        }
        monitorTargetGuard.requireInstanceTargetExists(request.getTargetType(), request.getTargetId());

        // Capture before state for audit
        Map<String, Object> beforeData = auditData("ruleId", rule.getId(), "ruleName", rule.getRuleName(),
                "metricName", rule.getMetricName(), "compareOp", rule.getCompareOp(),
                "thresholdValue", rule.getThresholdValue(), "enabled", rule.getEnabled());

        rule.setRuleName(request.getRuleName());
        rule.setTargetType(request.getTargetType());
        rule.setTargetId(request.getTargetId());
        rule.setTargetGroupCode(request.getTargetGroupCode());
        rule.setMetricName(request.getMetricName());
        rule.setCompareOp(request.getCompareOp());
        rule.setThresholdValue(request.getThresholdValue());
        rule.setDurationSeconds(request.getDurationSeconds());
        rule.setLevel(request.getLevel());
        rule.setNotifyChannel(request.getNotifyChannel());
        rule.setEnabled(request.getEnabled());
        updateById(rule);
        // updateById 默认跳过 null；目标切换为分组时必须显式清空旧 target_id。
        if (request.getTargetId() == null && rule.getId() != null) {
            lambdaUpdate().eq(MonitorAlertRule::getId, id)
                    .set(MonitorAlertRule::getTargetId, null).update();
        }

        // Record audit log
        auditLogService.recordAlertRule(rule.getId(), ACTION_UPDATE_RULE, RESULT_SUCCESS,
                beforeData,
                auditData("ruleId", rule.getId(), "ruleName", rule.getRuleName(),
                        "metricName", rule.getMetricName(), "compareOp", rule.getCompareOp(),
                        "thresholdValue", rule.getThresholdValue(), "enabled", rule.getEnabled()));
    }

    @Transactional
    public void delete(Long id) {
        MonitorAlertRule rule = getById(id);
        if (rule == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "告警规则不存在: " + id);
        }

        // Capture before state for audit
        Map<String, Object> beforeData = auditData("ruleId", rule.getId(), "ruleName", rule.getRuleName(),
                "enabled", rule.getEnabled());

        // Delete associated alert events first to avoid FK constraint violation
        LambdaQueryWrapper<MonitorAlertEvent> eventWrapper = new LambdaQueryWrapper<>();
        eventWrapper.eq(MonitorAlertEvent::getRuleId, id);
        alertEventService.remove(eventWrapper);
        // Then delete the rule
        removeById(id);

        // Record audit log
        auditLogService.recordAlertRule(id, ACTION_DELETE_RULE, RESULT_SUCCESS, beforeData, null);
    }

    @Transactional
    public Long toggleEnabled(Long id, Boolean enabled) {
        return toggleEnabled(id, enabled, null);
    }

    /**
     * Toggle alert rule enabled status.
     *
     * @param id      rule ID
     * @param enabled new enabled status
     * @param applyNote optional note for approval request
     * @return approval request ID if approval required, null if executed directly
     */
    @Transactional
    public Long toggleEnabled(Long id, Boolean enabled, String applyNote) {
        // Check if approval is required
        if (approvalPolicyService.isApprovalRequired(BIZ_TYPE_MONITOR_ALERT_RULE, "TOGGLE_RULE")) {
            // Create approval request with payload containing the target enabled value
            Long userId = UserContextHolder.getUserIdOrDefault(1L);
            String payload = "{\"enabled\":" + enabled + "}";
            Long approvalId = approvalRequestService.createRequest(
                    BIZ_TYPE_MONITOR_ALERT_RULE, id, "TOGGLE_RULE", userId, applyNote, payload);

            if (approvalId != null) {
                // Approval required and created
                return approvalId;
            }
        }

        // No approval required, execute directly
        doToggleEnabled(id, enabled);
        return null;
    }

    /**
     * This is the actual toggle implementation, called directly or after approval.
     *
     * <p>启用（{@code enabled=true}）会把一条已存在的规则变成"有效引用"，因此必须与实例移除登记
     * 共用实例行锁并校验目标存在；停用只是移除引用，不做校验，始终允许（用户需要能清理旧配置）。
     */
    @Transactional
    public void doToggleEnabled(Long id, Boolean enabled) {
        MonitorAlertRule rule = baseMapper.selectForUpdate(id);
        if (rule == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "告警规则不存在: " + id);
        }
        if (Boolean.TRUE.equals(enabled)) {
            monitorTargetGuard.requireInstanceTargetExists(rule.getTargetType(), rule.getTargetId());
        }

        Boolean previousEnabled = rule.getEnabled();
        rule.setEnabled(enabled);
        updateById(rule);

        // Record audit log with ruleId and ruleName for traceability
        auditLogService.recordAlertRule(id, ACTION_TOGGLE_RULE, RESULT_SUCCESS,
                auditData("ruleId", id, "ruleName", rule.getRuleName(), "enabled", previousEnabled),
                auditData("ruleId", id, "ruleName", rule.getRuleName(), "enabled", enabled));
    }

    public Page<MonitorAlertRule> page(MonitorAlertRuleQuery query) {
        LambdaQueryWrapper<MonitorAlertRule> wrapper = new LambdaQueryWrapper<>();

        if (query.getTargetType() != null && !query.getTargetType().isBlank()) {
            wrapper.eq(MonitorAlertRule::getTargetType, query.getTargetType());
        }
        if (query.getTargetId() != null) {
            wrapper.eq(MonitorAlertRule::getTargetId, query.getTargetId());
        }
        if (query.getLevel() != null && !query.getLevel().isBlank()) {
            wrapper.eq(MonitorAlertRule::getLevel, query.getLevel());
        }
        if (query.getEnabled() != null) {
            wrapper.eq(MonitorAlertRule::getEnabled, query.getEnabled());
        }
        if (query.getKeyword() != null && !query.getKeyword().isBlank()) {
            wrapper.like(MonitorAlertRule::getRuleName, query.getKeyword());
        }

        wrapper.orderByDesc(MonitorAlertRule::getCreatedAt);

        return page(new Page<>(query.getPageNum(), query.getPageSize()), wrapper);
    }
}

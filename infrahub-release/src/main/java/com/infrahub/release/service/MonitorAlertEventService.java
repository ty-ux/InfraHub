package com.infrahub.release.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.infrahub.common.error.ErrorCode;
import com.infrahub.common.exception.BizException;
import com.infrahub.release.dto.MonitorAlertEventQuery;
import com.infrahub.release.entity.MonitorAlertEvent;
import com.infrahub.release.entity.MonitorAlertRule;
import com.infrahub.release.mapper.MonitorAlertEventMapper;
import com.infrahub.release.mapper.MonitorAlertRuleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import static com.infrahub.release.service.MonitorAuditLogService.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class MonitorAlertEventService extends ServiceImpl<MonitorAlertEventMapper, MonitorAlertEvent> {

    private final MonitorNotificationService notificationService;
    private final MonitorAlertRuleMapper ruleMapper;
    private final MonitorAuditLogService auditLogService;

    public Page<MonitorAlertEvent> page(MonitorAlertEventQuery query) {
        LambdaQueryWrapper<MonitorAlertEvent> wrapper = new LambdaQueryWrapper<>();

        if (query.getEventId() != null) {
            wrapper.eq(MonitorAlertEvent::getId, query.getEventId());
        }
        if (query.getTargetType() != null && !query.getTargetType().isBlank()) {
            wrapper.eq(MonitorAlertEvent::getTargetType, query.getTargetType());
        }
        if (query.getTargetId() != null) {
            wrapper.eq(MonitorAlertEvent::getTargetId, query.getTargetId());
        }
        if (query.getAlertStatus() != null && !query.getAlertStatus().isBlank()) {
            wrapper.eq(MonitorAlertEvent::getAlertStatus, query.getAlertStatus());
        }
        if (query.getRuleId() != null) {
            wrapper.eq(MonitorAlertEvent::getRuleId, query.getRuleId());
        }

        wrapper.orderByDesc(MonitorAlertEvent::getFiredAt);

        return page(new Page<>(query.getPageNum(), query.getPageSize()), wrapper);
    }

    public Long createAlertEvent(Long ruleId, String ruleName, String targetType, Long targetId,
                                  String metricName, String compareOp, BigDecimal thresholdValue,
                                  BigDecimal actualValue) {
        MonitorAlertEvent event = new MonitorAlertEvent();
        event.setRuleId(ruleId);
        event.setTargetType(targetType);
        event.setTargetId(targetId);
        event.setAlertStatus("FIRING");
        event.setAlertValue(actualValue);
        event.setSummary(String.format("规则 %s 命中: %s %s %s, 当前值 %s",
                ruleName, metricName, compareOp, thresholdValue, actualValue));
        event.setFiredAt(OffsetDateTime.now());
        event.setRecoveredAt(null);
        event.setHandleBy(null);
        event.setHandleNote(null);
        save(event);
        return event.getId();
    }

    public MonitorAlertEvent findFiringEventByRuleAndTarget(Long ruleId, String targetType, Long targetId) {
        LambdaQueryWrapper<MonitorAlertEvent> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MonitorAlertEvent::getRuleId, ruleId)
               .eq(MonitorAlertEvent::getTargetType, targetType)
               .eq(MonitorAlertEvent::getTargetId, targetId)
               .eq(MonitorAlertEvent::getAlertStatus, "FIRING")
               .orderByDesc(MonitorAlertEvent::getFiredAt)
               .last("LIMIT 1");
        return getOne(wrapper);
    }

    public void resolveEvent(Long eventId, OffsetDateTime recoveredAt) {
        MonitorAlertEvent event = getById(eventId);
        if (event != null && "FIRING".equals(event.getAlertStatus())) {
            event.setAlertStatus("RESOLVED");
            event.setRecoveredAt(recoveredAt);
            updateById(event);
        }
    }

    /**
     * Manually resolve an alert event.
     * Only FIRING events can be manually resolved.
     *
     * @param eventId    the event ID
     * @param handleBy   the user ID who resolved the event
     * @param handleNote optional handling note
     * @return the updated event
     * @throws BizException if event not found or not in FIRING status
     */
    public MonitorAlertEvent manualResolve(Long eventId, Long handleBy, String handleNote) {
        MonitorAlertEvent event = getById(eventId);
        if (event == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "告警事件不存在");
        }
        if (!"FIRING".equals(event.getAlertStatus())) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "只有 FIRING 状态的告警可以手动恢复");
        }
        event.setAlertStatus("RESOLVED");
        event.setRecoveredAt(OffsetDateTime.now());
        event.setHandleBy(handleBy);
        event.setHandleNote(handleNote);
        updateById(event);

        // Record audit log for manual resolve
        auditLogService.recordAlertEvent(eventId, ACTION_MANUAL_RESOLVE, RESULT_SUCCESS,
                auditData("alertStatus", "FIRING", "ruleId", event.getRuleId(),
                        "targetType", event.getTargetType(), "targetId", event.getTargetId()),
                auditData("alertStatus", "RESOLVED", "handleBy", handleBy,
                        "handleNote", handleNote));

        // Send Webhook notification for manual resolve (B10)
        // Notification failure does not affect manual resolve success
        try {
            MonitorAlertRule rule = ruleMapper.selectById(event.getRuleId());
            if (rule != null) {
                notificationService.notifyResolved(event, rule, null);
            }
        } catch (Exception e) {
            log.warn("Failed to send manual resolve notification for event {}: {}", eventId, e.getMessage());
        }

        return event;
    }
}

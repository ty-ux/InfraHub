package com.infrahub.release.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.infrahub.release.dto.EvaluateMonitorRulesRequest;
import com.infrahub.release.dto.MonitorEvaluationResult;
import com.infrahub.release.entity.MonitorAlertEvent;
import com.infrahub.release.entity.MonitorAlertRule;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static com.infrahub.release.service.MonitorAuditLogService.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class MonitorEvaluationService {

    private final MonitorAlertRuleService ruleService;
    private final MonitorAlertEventService eventService;
    private final MonitorNotificationService notificationService;
    private final ObjectMapper objectMapper;
    private final MonitorAuditLogService auditLogService;

    @Transactional
    public MonitorEvaluationResult evaluate(EvaluateMonitorRulesRequest request) {
        MonitorEvaluationResult result = new MonitorEvaluationResult();
        result.setTargetType(request.getTargetType());
        result.setTargetId(request.getTargetId());
        result.setHitCount(0);
        result.setEventIds(new ArrayList<>());
        result.setHitRules(new ArrayList<>());
        result.setRecoveredCount(0);
        result.setRecoveredEventIds(new ArrayList<>());

        // Parse metricPayload JSON
        JsonNode metricNode;
        try {
            metricNode = objectMapper.readTree(request.getMetricPayload());
        } catch (Exception e) {
            log.warn("Failed to parse metricPayload: {}", e.getMessage());
            return result;
        }

        // Query enabled rules for the target
        LambdaQueryWrapper<MonitorAlertRule> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MonitorAlertRule::getEnabled, true);

        List<MonitorAlertRule> rules = ruleService.list(wrapper);

        for (MonitorAlertRule rule : rules) {
            // Skip rules that don't match target type
            if (rule.getTargetType() != null && !rule.getTargetType().isBlank()
                    && !rule.getTargetType().equals(request.getTargetType())) {
                continue;
            }

            // Skip rules that have specific target ID but don't match
            if (rule.getTargetId() != null && !rule.getTargetId().equals(request.getTargetId())) {
                continue;
            }

            // Evaluate rule
            BigDecimal actualValue = extractMetricValue(metricNode, rule.getMetricName());
            if (actualValue == null) {
                continue;
            }

            boolean hit = evaluateCondition(actualValue, rule.getCompareOp(), rule.getThresholdValue());

            if (hit) {
                // Check if there's already a FIRING event for this rule + target
                MonitorAlertEvent existingEvent = eventService.findFiringEventByRuleAndTarget(
                        rule.getId(), request.getTargetType(), request.getTargetId());

                if (existingEvent != null) {
                    // Already has FIRING event, don't create duplicate, return existing ID
                    result.getEventIds().add(existingEvent.getId());
                    // Do NOT send notification for existing FIRING event (B7 requirement)
                } else {
                    // No existing FIRING event, create new one
                    Long eventId = eventService.createAlertEvent(
                            rule.getId(),
                            rule.getRuleName(),
                            request.getTargetType(),
                            request.getTargetId(),
                            rule.getMetricName(),
                            rule.getCompareOp(),
                            rule.getThresholdValue(),
                            actualValue
                    );
                    result.getEventIds().add(eventId);

                    // Send FIRING notification for newly created event
                    MonitorAlertEvent newEvent = eventService.getById(eventId);
                    notificationService.notifyFiring(newEvent, rule, actualValue);
                }

                result.getHitRules().add(new MonitorEvaluationResult.HitRuleSummary(
                        rule.getId(),
                        rule.getRuleName(),
                        rule.getMetricName(),
                        rule.getCompareOp(),
                        rule.getThresholdValue(),
                        actualValue
                ));
            } else {
                // Rule not hit - check if there's a FIRING event to resolve
                MonitorAlertEvent firingEvent = eventService.findFiringEventByRuleAndTarget(
                        rule.getId(), request.getTargetType(), request.getTargetId());

                if (firingEvent != null) {
                    // Resolve the event
                    eventService.resolveEvent(firingEvent.getId(), OffsetDateTime.now());
                    result.getRecoveredEventIds().add(firingEvent.getId());

                    // Send RESOLVED notification
                    notificationService.notifyResolved(firingEvent, rule, actualValue);
                }
            }
        }

        result.setHitCount(result.getEventIds().size());
        result.setRecoveredCount(result.getRecoveredEventIds().size());

        // Record audit log for rule evaluation
        auditLogService.recordEvaluation(request.getTargetId(), RESULT_SUCCESS,
                auditData("targetType", request.getTargetType(), "targetId", request.getTargetId(),
                        "hitCount", result.getHitCount(), "recoveredCount", result.getRecoveredCount(),
                        "eventIds", result.getEventIds().isEmpty() ? null : result.getEventIds().toString(),
                        "recoveredEventIds", result.getRecoveredEventIds().isEmpty() ? null : result.getRecoveredEventIds().toString()));

        return result;
    }

    private BigDecimal extractMetricValue(JsonNode metricNode, String metricName) {
        if (metricName == null || metricName.isBlank()) {
            return null;
        }

        JsonNode valueNode = metricNode.get(metricName);
        if (valueNode == null || !valueNode.isNumber()) {
            return null;
        }

        return valueNode.decimalValue();
    }

    private boolean evaluateCondition(BigDecimal actualValue, String compareOp, BigDecimal thresholdValue) {
        if (actualValue == null || compareOp == null || thresholdValue == null) {
            return false;
        }

        int comparison = actualValue.compareTo(thresholdValue);

        return switch (compareOp) {
            case "GT" -> comparison > 0;
            case "LT" -> comparison < 0;
            case "EQ" -> comparison == 0;
            case "GE" -> comparison >= 0;
            case "LE" -> comparison <= 0;
            default -> false;
        };
    }
}

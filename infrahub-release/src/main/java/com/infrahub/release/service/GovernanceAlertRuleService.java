package com.infrahub.release.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.infrahub.release.dto.GovernanceAlertRulePageResponse;
import com.infrahub.release.dto.GovernanceAlertRuleQuery;
import com.infrahub.release.dto.GovernanceAlertRuleRequest;
import com.infrahub.release.dto.GovernanceAlertRuleResponse;
import com.infrahub.release.entity.GovernanceAlertRule;
import com.infrahub.release.enums.AlertOperator;
import com.infrahub.release.enums.GovernanceAlertLevel;
import com.infrahub.release.enums.GovernanceOpsMetricType;
import com.infrahub.release.mapper.GovernanceAlertRuleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 告警规则服务
 * Contract: v2-stage-m-contract.md Section 2.1
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GovernanceAlertRuleService {

    private final GovernanceAlertRuleMapper alertRuleMapper;

    /**
     * 分页查询告警规则列表
     * Contract: GET /api/assets/governance-ops/alert-rules
     */
    public GovernanceAlertRulePageResponse queryRulesPage(GovernanceAlertRuleQuery query) {
        Page<GovernanceAlertRule> page = new Page<>(query.getPageNum(), query.getPageSize());
        Page<GovernanceAlertRule> result = alertRuleMapper.queryRulesPage(
                page, query.getMetricType(), query.getAlertLevel(),
                query.getEnvId(), query.getResourcePoolId(), query.getServiceId(), query.getEnabled());

        List<GovernanceAlertRuleResponse> records = result.getRecords().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());

        GovernanceAlertRulePageResponse response = new GovernanceAlertRulePageResponse();
        response.setRecords(records);
        response.setTotal(result.getTotal());
        response.setPageNum((int) result.getCurrent());
        response.setPageSize((int) result.getSize());
        return response;
    }

    /**
     * 查询告警规则列表（不分页，保留兼容）
     */
    public List<GovernanceAlertRuleResponse> queryRules(String metricType, String alertLevel,
                                                          Long envId, Long resourcePoolId, Long serviceId,
                                                          Boolean enabled) {
        List<GovernanceAlertRule> rules = alertRuleMapper.queryRules(metricType, alertLevel, envId, resourcePoolId, serviceId, enabled);
        return rules.stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    /**
     * 创建告警规则
     * Contract: POST /api/assets/governance-ops/alert-rules
     */
    @Transactional
    public GovernanceAlertRuleResponse createRule(GovernanceAlertRuleRequest request) {
        GovernanceAlertRule rule = new GovernanceAlertRule();
        rule.setRuleName(request.getRuleName());
        rule.setMetricType(GovernanceOpsMetricType.valueOf(request.getMetricType()));
        rule.setOperator(AlertOperator.valueOf(request.getOperator()));
        rule.setThresholdValue(request.getThresholdValue());
        rule.setAlertLevel(GovernanceAlertLevel.valueOf(request.getAlertLevel()));
        rule.setEnvId(request.getEnvId());
        rule.setResourcePoolId(request.getResourcePoolId());
        rule.setServiceId(request.getServiceId());
        rule.setEnabled(request.getEnabled());
        rule.setDescription(request.getDescription());
        rule.setCreatedAt(OffsetDateTime.now());
        rule.setUpdatedAt(OffsetDateTime.now());

        alertRuleMapper.insert(rule);

        log.info("Created alert rule: id={}, ruleName={}", rule.getId(), rule.getRuleName());
        return toResponse(rule);
    }

    /**
     * 更新告警规则
     * Contract: PUT /api/assets/governance-ops/alert-rules/{id}
     */
    @Transactional
    public GovernanceAlertRuleResponse updateRule(Long id, GovernanceAlertRuleRequest request) {
        GovernanceAlertRule rule = alertRuleMapper.selectById(id);
        if (rule == null) {
            throw new IllegalArgumentException("Alert rule not found: " + id);
        }

        rule.setRuleName(request.getRuleName());
        rule.setMetricType(GovernanceOpsMetricType.valueOf(request.getMetricType()));
        rule.setOperator(AlertOperator.valueOf(request.getOperator()));
        rule.setThresholdValue(request.getThresholdValue());
        rule.setAlertLevel(GovernanceAlertLevel.valueOf(request.getAlertLevel()));
        rule.setEnvId(request.getEnvId());
        rule.setResourcePoolId(request.getResourcePoolId());
        rule.setServiceId(request.getServiceId());
        rule.setEnabled(request.getEnabled());
        rule.setDescription(request.getDescription());
        rule.setUpdatedAt(OffsetDateTime.now());

        alertRuleMapper.updateById(rule);

        log.info("Updated alert rule: id={}, ruleName={}", rule.getId(), rule.getRuleName());
        return toResponse(rule);
    }

    /**
     * 启用/禁用告警规则
     * Contract: POST /api/assets/governance-ops/alert-rules/{id}/toggle?enabled=
     */
    @Transactional
    public GovernanceAlertRuleResponse toggleRule(Long id, Boolean enabled) {
        GovernanceAlertRule rule = alertRuleMapper.selectById(id);
        if (rule == null) {
            throw new IllegalArgumentException("Alert rule not found: " + id);
        }

        rule.setEnabled(enabled);
        rule.setUpdatedAt(OffsetDateTime.now());

        alertRuleMapper.updateById(rule);

        log.info("Toggled alert rule: id={}, enabled={}", rule.getId(), enabled);
        return toResponse(rule);
    }

    /**
     * 获取告警规则详情
     */
    public GovernanceAlertRuleResponse getRuleById(Long id) {
        GovernanceAlertRule rule = alertRuleMapper.selectById(id);
        if (rule == null) {
            throw new IllegalArgumentException("Alert rule not found: " + id);
        }
        return toResponse(rule);
    }

    /**
     * 获取所有启用的规则（供评估服务使用）
     */
    public List<GovernanceAlertRule> getAllEnabledRules() {
        return alertRuleMapper.selectAllEnabledRules();
    }

    /**
     * 获取指定范围的启用规则（供评估服务使用）
     */
    public List<GovernanceAlertRule> getEnabledRulesForScope(Long envId, Long resourcePoolId, Long serviceId) {
        return alertRuleMapper.selectEnabledRulesForScope(envId, resourcePoolId, serviceId);
    }

    private GovernanceAlertRuleResponse toResponse(GovernanceAlertRule rule) {
        GovernanceAlertRuleResponse response = new GovernanceAlertRuleResponse();
        response.setId(rule.getId());
        response.setRuleName(rule.getRuleName());
        response.setMetricType(rule.getMetricType() != null ? rule.getMetricType().name() : null);
        response.setOperator(rule.getOperator() != null ? rule.getOperator().name() : null);
        response.setThresholdValue(rule.getThresholdValue());
        response.setAlertLevel(rule.getAlertLevel() != null ? rule.getAlertLevel().name() : null);
        response.setEnvId(rule.getEnvId());
        response.setResourcePoolId(rule.getResourcePoolId());
        response.setServiceId(rule.getServiceId());
        response.setEnabled(rule.getEnabled());
        response.setDescription(rule.getDescription());
        response.setCreatedAt(rule.getCreatedAt());
        response.setUpdatedAt(rule.getUpdatedAt());
        return response;
    }
}

package com.infrahub.release.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.infrahub.release.entity.ApprovalPolicy;
import com.infrahub.release.entity.ApprovalRequest;
import com.infrahub.release.mapper.ApprovalPolicyMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ApprovalPolicyService extends ServiceImpl<ApprovalPolicyMapper, ApprovalPolicy> {

    // Fixed policy combinations that the system supports
    private static final List<PolicyDefinition> FIXED_POLICIES = List.of(
            new PolicyDefinition(ApprovalRequest.BIZ_TYPE_MONITOR_ALERT_RULE, ApprovalRequest.ACTION_TOGGLE_RULE),
            new PolicyDefinition(ApprovalRequest.BIZ_TYPE_MONITOR_HEALTH_CHECK, ApprovalRequest.ACTION_TOGGLE_CHECK),
            new PolicyDefinition(ApprovalRequest.BIZ_TYPE_RELEASE_ORDER, ApprovalRequest.ACTION_EXECUTE_RELEASE),
            new PolicyDefinition(ApprovalRequest.BIZ_TYPE_ROLLBACK_ORDER, ApprovalRequest.ACTION_EXECUTE_ROLLBACK)
    );

    /**
     * Check if approval is required for a given (bizType, action) combination.
     *
     * @param bizType business type (e.g., MONITOR_ALERT_RULE)
     * @param action action (e.g., TOGGLE_RULE)
     * @return true if approval is required and enabled, false otherwise
     */
    public boolean isApprovalRequired(String bizType, String action) {
        LambdaQueryWrapper<ApprovalPolicy> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ApprovalPolicy::getBizType, bizType)
                .eq(ApprovalPolicy::getAction, action)
                .eq(ApprovalPolicy::getEnabled, true)
                .select(ApprovalPolicy::getId);

        return Optional.ofNullable(getOne(wrapper)).isPresent();
    }

    /**
     * Get the policy by bizType and action.
     */
    public ApprovalPolicy getPolicy(String bizType, String action) {
        LambdaQueryWrapper<ApprovalPolicy> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ApprovalPolicy::getBizType, bizType)
                .eq(ApprovalPolicy::getAction, action);

        return getOne(wrapper);
    }

    /**
     * Get all policies including fixed supported policies merged with database values.
     * Fixed policies that don't exist in database will be returned with enabled=false.
     */
    public List<ApprovalPolicy> listAllPolicies() {
        // Get existing policies from database
        List<ApprovalPolicy> dbPolicies = list();
        Map<String, ApprovalPolicy> dbPolicyMap = dbPolicies.stream()
                .collect(Collectors.toMap(p -> p.getBizType() + ":" + p.getAction(), p -> p));

        // Build complete list with fixed policies
        List<ApprovalPolicy> result = new ArrayList<>();
        for (PolicyDefinition def : FIXED_POLICIES) {
            String key = def.bizType + ":" + def.action;
            ApprovalPolicy policy = dbPolicyMap.get(key);
            if (policy != null) {
                result.add(policy);
            } else {
                // Create a virtual policy entry for fixed supported policy not yet in database
                ApprovalPolicy virtualPolicy = new ApprovalPolicy();
                virtualPolicy.setBizType(def.bizType);
                virtualPolicy.setAction(def.action);
                virtualPolicy.setEnabled(false);
                result.add(virtualPolicy);
            }
        }

        return result;
    }

    private static class PolicyDefinition {
        final String bizType;
        final String action;

        PolicyDefinition(String bizType, String action) {
            this.bizType = bizType;
            this.action = action;
        }
    }
}
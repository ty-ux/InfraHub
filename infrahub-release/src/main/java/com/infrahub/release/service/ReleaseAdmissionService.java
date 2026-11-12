package com.infrahub.release.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.infrahub.release.dto.AdmissionDecision;
import com.infrahub.release.entity.ReleaseEnvironmentPolicy;
import com.infrahub.release.entity.ReleasePrecheckRecord;
import com.infrahub.release.entity.SysAuditLog;
import com.infrahub.release.mapper.ReleaseEnvironmentPolicyMapper;
import com.infrahub.release.mapper.ReleasePrecheckRecordMapper;
import com.infrahub.release.mapper.SysAuditLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.OffsetDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReleaseAdmissionService {

    private final ReleaseEnvironmentPolicyMapper policyMapper;
    private final ReleasePrecheckRecordMapper precheckRecordMapper;
    private final SysAuditLogMapper sysAuditLogMapper;

    private static final int PRECHECK_VALIDITY_MINUTES = 30;

    // 审计 action 常量
    public static final String RELEASE_ADMISSION_PASS = "RELEASE_ADMISSION_PASS";
    public static final String RELEASE_ADMISSION_BLOCKED = "RELEASE_ADMISSION_BLOCKED";
    public static final String RELEASE_ADMISSION_APPROVAL_REQUIRED = "RELEASE_ADMISSION_APPROVAL_REQUIRED";
    public static final String ROLLBACK_ADMISSION_PASS = "ROLLBACK_ADMISSION_PASS";
    public static final String ROLLBACK_ADMISSION_BLOCKED = "ROLLBACK_ADMISSION_BLOCKED";
    public static final String ROLLBACK_ADMISSION_APPROVAL_REQUIRED = "ROLLBACK_ADMISSION_APPROVAL_REQUIRED";

    /**
     * 准入决策
     * @param envId 环境ID
     * @param action 动作：EXECUTE_RELEASE 或 EXECUTE_ROLLBACK
     * @param riskLevel 风险等级：LOW/MEDIUM/HIGH/CRITICAL
     * @param bizType 业务类型：RELEASE_ORDER 或 ROLLBACK_ORDER
     * @param bizId 业务ID（发布单或回滚单ID）
     * @return 准入决策结果
     */
    public AdmissionDecision decide(Long envId, String action, String riskLevel,
                                     String bizType, Long bizId) {
        AdmissionDecision decision = new AdmissionDecision();
        decision.setAllowed(true);
        decision.setApprovalRequired(false);
        decision.setPrecheckRequired(false);

        // 查询生效策略：优先具体风险等级，然后 ALL 兜底
        ReleaseEnvironmentPolicy policy = findEffectivePolicy(envId, action, riskLevel);

        if (policy == null || !Boolean.TRUE.equals(policy.getEnabled())) {
            // 无策略命中或策略禁用，保持现有行为
            return decision;
        }

        decision.setMatchedPolicyId(policy.getId());

        // 1. 检查阻断
        if (Boolean.TRUE.equals(policy.getBlocked())) {
            decision.setAllowed(false);
            decision.setBlockedReason("策略配置阻断：" + (policy.getDescription() != null ? policy.getDescription() : "该环境/动作/风险等级已被管理员阻断"));
            writeAdmissionAudit(bizType, bizId, decision, policy);
            return decision;
        }

        // 2. 检查冻结窗口
        if (Boolean.TRUE.equals(policy.getFreezeWindowEnabled()) && isInFreezeWindow(policy)) {
            decision.setAllowed(false);
            decision.setBlockedReason("冻结窗口内禁止执行");
            writeAdmissionAudit(bizType, bizId, decision, policy);
            return decision;
        }

        // 3. 检查前置检查要求
        if (Boolean.TRUE.equals(policy.getPrecheckRequired())) {
            decision.setPrecheckRequired(true);
            // 检查是否有有效的前置检查记录
            if (!hasValidPrecheckRecord(bizType, bizId, envId)) {
                decision.setAllowed(false);
                decision.setBlockedReason("策略要求前置检查，但无有效成功记录（需30分钟内）");
                writeAdmissionAudit(bizType, bizId, decision, policy);
                return decision;
            }
        }

        // 4. 检查审批要求
        if (Boolean.TRUE.equals(policy.getApprovalRequired())) {
            decision.setApprovalRequired(true);
        }

        // 写入准入审计日志
        writeAdmissionAudit(bizType, bizId, decision, policy);

        return decision;
    }

    /**
     * 写准入审计日志
     */
    private void writeAdmissionAudit(String bizType, Long bizId,
                                     AdmissionDecision decision, ReleaseEnvironmentPolicy policy) {
        try {
            String action;
            String result;
            String summary;

            if (decision.isAllowed()) {
                action = "RELEASE_ORDER".equals(bizType) ? RELEASE_ADMISSION_PASS : ROLLBACK_ADMISSION_PASS;
                result = "SUCCESS";
                summary = "准入通过";
            } else {
                action = "RELEASE_ORDER".equals(bizType) ? RELEASE_ADMISSION_BLOCKED : ROLLBACK_ADMISSION_BLOCKED;
                result = "BLOCKED";
                summary = decision.getBlockedReason() != null ? decision.getBlockedReason() : "准入阻断";
            }

            // 如果需要审批，写入审批要求日志
            if (decision.isApprovalRequired()) {
                String approvalAction = "RELEASE_ORDER".equals(bizType) ? RELEASE_ADMISSION_APPROVAL_REQUIRED : ROLLBACK_ADMISSION_APPROVAL_REQUIRED;
                SysAuditLog approvalAudit = new SysAuditLog();
                approvalAudit.setBizType(bizType);
                approvalAudit.setBizId(bizId);
                approvalAudit.setAction(approvalAction);
                approvalAudit.setResult("INFO");
                approvalAudit.setCreatedAt(OffsetDateTime.now());
                sysAuditLogMapper.insert(approvalAudit);
            }

            // 写入主审计日志
            SysAuditLog auditLog = new SysAuditLog();
            auditLog.setBizType(bizType);
            auditLog.setBizId(bizId);
            auditLog.setAction(action);
            auditLog.setResult(result);
            auditLog.setCreatedAt(OffsetDateTime.now());
            sysAuditLogMapper.insert(auditLog);

            log.debug("准入审计日志写入成功: bizType={}, bizId={}, action={}, result={}",
                      bizType, bizId, action, result);
        } catch (Exception e) {
            log.error("准入审计日志写入失败: bizType={}, bizId={}, error={}",
                      bizType, bizId, e.getMessage(), e);
        }
    }

    /**
     * 查找生效策略：具体风险等级优先，ALL 兜底
     */
    private ReleaseEnvironmentPolicy findEffectivePolicy(Long envId, String action, String riskLevel) {
        // 先查具体风险等级
        LambdaQueryWrapper<ReleaseEnvironmentPolicy> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ReleaseEnvironmentPolicy::getEnvId, envId)
               .eq(ReleaseEnvironmentPolicy::getAction, action)
               .eq(ReleaseEnvironmentPolicy::getRiskLevel, riskLevel)
               .eq(ReleaseEnvironmentPolicy::getEnabled, true);

        ReleaseEnvironmentPolicy policy = policyMapper.selectOne(wrapper);

        if (policy != null) {
            return policy;
        }

        // 再查 ALL 兜底
        wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ReleaseEnvironmentPolicy::getEnvId, envId)
               .eq(ReleaseEnvironmentPolicy::getAction, action)
               .eq(ReleaseEnvironmentPolicy::getRiskLevel, "ALL")
               .eq(ReleaseEnvironmentPolicy::getEnabled, true);

        return policyMapper.selectOne(wrapper);
    }

    /**
     * 判断当前时间是否在冻结窗口内
     */
    private boolean isInFreezeWindow(ReleaseEnvironmentPolicy policy) {
        if (policy.getFreezeWindowStart() == null || policy.getFreezeWindowEnd() == null) {
            return false;
        }

        ZonedDateTime now = ZonedDateTime.now(ZoneId.systemDefault());
        int dayOfWeek = now.getDayOfWeek().getValue(); // 1-7

        // 检查是否在指定的日期
        if (policy.getFreezeWindowDays() != null && !policy.getFreezeWindowDays().isBlank()) {
            String[] days = policy.getFreezeWindowDays().split(",");
            boolean dayMatch = false;
            for (String day : days) {
                try {
                    if (Integer.parseInt(day.trim()) == dayOfWeek) {
                        dayMatch = true;
                        break;
                    }
                } catch (NumberFormatException e) {
                    // ignore
                }
            }
            if (!dayMatch) {
                return false;
            }
        }

        LocalTime currentTime = now.toLocalTime();
        LocalTime start = policy.getFreezeWindowStart();
        LocalTime end = policy.getFreezeWindowEnd();

        // 处理跨午夜的情况
        if (start.isBefore(end)) {
            return !currentTime.isBefore(start) && !currentTime.isAfter(end);
        } else {
            return !currentTime.isBefore(start) || !currentTime.isAfter(end);
        }
    }

    /**
     * 检查是否有有效的前置检查记录（30分钟内成功）
     * 使用 OffsetDateTime 与实体字段类型一致，避免 MyBatis 时间类型比较错误
     */
    private boolean hasValidPrecheckRecord(String bizType, Long bizId, Long envId) {
        OffsetDateTime threshold = OffsetDateTime.now().minusMinutes(PRECHECK_VALIDITY_MINUTES);

        LambdaQueryWrapper<ReleasePrecheckRecord> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ReleasePrecheckRecord::getBizType, bizType)
               .eq(ReleasePrecheckRecord::getBizId, bizId)
               .eq(ReleasePrecheckRecord::getEnvId, envId)
               .eq(ReleasePrecheckRecord::getSuccess, true)
               .ge(ReleasePrecheckRecord::getCreatedAt, threshold);

        return precheckRecordMapper.selectCount(wrapper) > 0;
    }
}

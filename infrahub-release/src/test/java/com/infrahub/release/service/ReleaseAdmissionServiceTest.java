package com.infrahub.release.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.infrahub.release.dto.AdmissionDecision;
import com.infrahub.release.entity.ReleaseEnvironmentPolicy;
import com.infrahub.release.entity.ReleasePrecheckRecord;
import com.infrahub.release.entity.SysAuditLog;
import com.infrahub.release.mapper.ReleaseEnvironmentPolicyMapper;
import com.infrahub.release.mapper.ReleasePrecheckRecordMapper;
import com.infrahub.release.mapper.SysAuditLogMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Collection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ReleaseAdmissionService 纯单元测试：LambdaQueryWrapper 在本机环境按 camelToUnderline
 * 解析列名，mapper 调用全部 Mock，不启动 Spring 容器。
 */
class ReleaseAdmissionServiceTest {

    private ReleaseEnvironmentPolicyMapper policyMapper;
    private ReleasePrecheckRecordMapper precheckRecordMapper;
    private SysAuditLogMapper sysAuditLogMapper;
    private ReleaseAdmissionService service;

    @BeforeEach
    void setUp() {
        policyMapper = mock(ReleaseEnvironmentPolicyMapper.class);
        precheckRecordMapper = mock(ReleasePrecheckRecordMapper.class);
        sysAuditLogMapper = mock(SysAuditLogMapper.class);
        service = new ReleaseAdmissionService(policyMapper, precheckRecordMapper, sysAuditLogMapper);
    }

    private ReleaseEnvironmentPolicy policy() {
        ReleaseEnvironmentPolicy policy = new ReleaseEnvironmentPolicy();
        policy.setId(7L);
        policy.setEnvId(1L);
        policy.setAction("EXECUTE_RELEASE");
        policy.setRiskLevel("HIGH");
        policy.setEnabled(true);
        return policy;
    }

    @Test
    @DisplayName("无策略命中（具体风险与 ALL 兜底均未命中）→ 放行且不写审计")
    void noPolicyAllowsWithoutAudit() {
        when(policyMapper.selectOne(any())).thenReturn(null);

        AdmissionDecision decision = service.decide(1L, "EXECUTE_RELEASE", "LOW", "RELEASE_ORDER", 100L);

        assertTrue(decision.isAllowed());
        assertFalse(decision.isApprovalRequired());
        assertFalse(decision.isPrecheckRequired());
        assertNotNull(decision);
        verify(sysAuditLogMapper, never()).insert(any(SysAuditLog.class));
    }

    @Test
    @DisplayName("具体风险等级未命中时 ALL 兜底策略生效")
    void allRiskLevelPolicyAsFallback() {
        ReleaseEnvironmentPolicy fallback = policy();
        fallback.setRiskLevel("ALL");
        when(policyMapper.selectOne(any())).thenReturn(null, fallback);

        AdmissionDecision decision = service.decide(1L, "EXECUTE_RELEASE", "HIGH", "RELEASE_ORDER", 100L);

        assertEquals(7L, decision.getMatchedPolicyId());
        verify(policyMapper, times(2)).selectOne(any());
    }

    @Test
    @DisplayName("策略被禁用 → 放行且不写审计")
    void disabledPolicyAllowsWithoutAudit() {
        ReleaseEnvironmentPolicy disabled = policy();
        disabled.setEnabled(false);
        when(policyMapper.selectOne(any())).thenReturn(disabled);

        AdmissionDecision decision = service.decide(1L, "EXECUTE_RELEASE", "HIGH", "RELEASE_ORDER", 100L);

        assertTrue(decision.isAllowed());
        verify(sysAuditLogMapper, never()).insert(any(SysAuditLog.class));
        assertNotNull(decision);
    }

    @Test
    @DisplayName("策略阻断 → 不允许并写 RELEASE_ADMISSION_BLOCKED 审计")
    void blockedPolicyRejectsAndAudits() {
        ReleaseEnvironmentPolicy blocked = policy();
        blocked.setBlocked(true);
        blocked.setDescription("高危发布已停用");
        when(policyMapper.selectOne(any())).thenReturn(blocked);

        AdmissionDecision decision = service.decide(1L, "EXECUTE_RELEASE", "HIGH", "RELEASE_ORDER", 100L);

        assertFalse(decision.isAllowed());
        assertTrue(decision.getBlockedReason().startsWith("策略配置阻断"));
        assertTrue(decision.getBlockedReason().contains("高危发布已停用"));

        ArgumentCaptor<SysAuditLog> captor = ArgumentCaptor.forClass(SysAuditLog.class);
        verify(sysAuditLogMapper, times(1)).insert(captor.capture());
        SysAuditLog audit = captor.getValue();
        assertEquals(ReleaseAdmissionService.RELEASE_ADMISSION_BLOCKED, audit.getAction());
        assertEquals("BLOCKED", audit.getResult());
        assertEquals("RELEASE_ORDER", audit.getBizType());
        assertEquals(100L, audit.getBizId());
    }

    @Test
    @DisplayName("回滚阻断 → ROLLBACK_ADMISSION_BLOCKED 审计")
    void rollbackBlockedWritesRollbackAudit() {
        ReleaseEnvironmentPolicy blocked = policy();
        blocked.setAction("EXECUTE_ROLLBACK");
        blocked.setBlocked(true);
        when(policyMapper.selectOne(any())).thenReturn(blocked);

        AdmissionDecision decision = service.decide(1L, "EXECUTE_ROLLBACK", "HIGH", "ROLLBACK_ORDER", 200L);

        assertFalse(decision.isAllowed());
        ArgumentCaptor<SysAuditLog> captor = ArgumentCaptor.forClass(SysAuditLog.class);
        verify(sysAuditLogMapper, times(1)).insert(captor.capture());
        assertEquals(ReleaseAdmissionService.ROLLBACK_ADMISSION_BLOCKED, captor.getValue().getAction());
    }

    @Test
    @DisplayName("要求前置检查但无 30 分钟内成功记录 → 阻断")
    void precheckRequiredWithoutValidRecordBlocks() {
        ReleaseEnvironmentPolicy requirement = policy();
        requirement.setPrecheckRequired(true);
        when(policyMapper.selectOne(any())).thenReturn(requirement);
        when(precheckRecordMapper.selectCount(any())).thenReturn(0L);

        AdmissionDecision decision = service.decide(1L, "EXECUTE_RELEASE", "HIGH", "RELEASE_ORDER", 100L);

        assertFalse(decision.isAllowed());
        assertTrue(decision.isPrecheckRequired());
        assertTrue(decision.getBlockedReason().contains("30分钟内"));
        ArgumentCaptor<SysAuditLog> captor = ArgumentCaptor.forClass(SysAuditLog.class);
        verify(sysAuditLogMapper, times(1)).insert(captor.capture());
        assertEquals(ReleaseAdmissionService.RELEASE_ADMISSION_BLOCKED, captor.getValue().getAction());
    }

    @Test
    @DisplayName("要求前置检查且存在有效成功记录 → 放行且 precheckRequired=true")
    void precheckRequiredWithValidRecordAllows() {
        ReleaseEnvironmentPolicy requirement = policy();
        requirement.setPrecheckRequired(true);
        when(policyMapper.selectOne(any())).thenReturn(requirement);
        when(precheckRecordMapper.selectCount(any())).thenReturn(1L);

        AdmissionDecision decision = service.decide(1L, "EXECUTE_RELEASE", "HIGH", "RELEASE_ORDER", 100L);

        assertTrue(decision.isAllowed());
        assertTrue(decision.isPrecheckRequired());
        verify(precheckRecordMapper, times(1)).selectCount(any());
        verify(sysAuditLogMapper, times(1)).insert(any(SysAuditLog.class));
    }

    @Test
    @DisplayName("不要求前置检查 → 不查询 precheck 记录")
    void precheckNotRequiredSkipsSelectCount() {
        when(policyMapper.selectOne(any())).thenReturn(policy());

        AdmissionDecision decision = service.decide(1L, "EXECUTE_RELEASE", "HIGH", "RELEASE_ORDER", 100L);

        assertTrue(decision.isAllowed());
        verify(precheckRecordMapper, never()).selectCount(any());
    }

    @Test
    @DisplayName("前置检查 30 分钟阈值参数为 OffsetDateTime 而非 LocalDateTime")
    void precheckThresholdUsesOffsetDateTime() {
        ReleaseEnvironmentPolicy requirement = policy();
        requirement.setPrecheckRequired(true);
        when(policyMapper.selectOne(any())).thenReturn(requirement);
        when(precheckRecordMapper.selectCount(any())).thenReturn(0L);

        service.decide(1L, "EXECUTE_RELEASE", "HIGH", "RELEASE_ORDER", 100L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<ReleasePrecheckRecord>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(precheckRecordMapper, times(1)).selectCount(captor.capture());
        LambdaQueryWrapper<ReleasePrecheckRecord> wrapper = captor.getValue();

        // 渲染 SQL 片段触发参数装载（纯 JVM，camelToUnderline 解析列名）
        String sqlSegment = wrapper.getSqlSegment();
        assertTrue(sqlSegment.contains("created_at"), "SQL 应包含 created_at 列: " + sqlSegment);

        Collection<Object> params = wrapper.getParamNameValuePairs().values();
        boolean hasOffsetDateTime = params.stream().anyMatch(p -> p instanceof OffsetDateTime);
        boolean hasLocalDateTime = params.stream().anyMatch(p -> p instanceof LocalDateTime);
        assertTrue(hasOffsetDateTime, "created_at 阈值参数应为 OffsetDateTime，实际: " + params);
        assertFalse(hasLocalDateTime, "created_at 阈值参数不得为 LocalDateTime，实际: " + params);
    }

    @Test
    @DisplayName("冻结窗口内禁止执行 → 阻断")
    void freezeWindowBlocks() {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.systemDefault());
        String today = String.valueOf(now.getDayOfWeek().getValue());

        ReleaseEnvironmentPolicy freezing = policy();
        freezing.setFreezeWindowEnabled(true);
        freezing.setFreezeWindowDays(today);
        freezing.setFreezeWindowStart(now.toLocalTime().minusHours(1));
        freezing.setFreezeWindowEnd(now.toLocalTime().plusHours(1));
        when(policyMapper.selectOne(any())).thenReturn(freezing);

        AdmissionDecision decision = service.decide(1L, "EXECUTE_RELEASE", "HIGH", "RELEASE_ORDER", 100L);

        assertFalse(decision.isAllowed());
        assertEquals("冻结窗口内禁止执行", decision.getBlockedReason());
    }

    @Test
    @DisplayName("冻结窗口天数不匹配今天 → 不阻断")
    void freezeWindowDayMismatchDoesNotBlock() {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.systemDefault());
        int otherDay = now.getDayOfWeek().getValue() == 7 ? 1 : now.getDayOfWeek().getValue() + 1;

        ReleaseEnvironmentPolicy freezing = policy();
        freezing.setFreezeWindowEnabled(true);
        freezing.setFreezeWindowDays(String.valueOf(otherDay));
        freezing.setFreezeWindowStart(now.toLocalTime().minusHours(1));
        freezing.setFreezeWindowEnd(now.toLocalTime().plusHours(1));
        when(policyMapper.selectOne(any())).thenReturn(freezing);

        AdmissionDecision decision = service.decide(1L, "EXECUTE_RELEASE", "HIGH", "RELEASE_ORDER", 100L);

        assertTrue(decision.isAllowed());
    }

    @Test
    @DisplayName("要求审批 → 放行且 approvalRequired=true，写审批要求与通过两条审计")
    void approvalRequiredAllowsAndWritesApprovalAudit() {
        ReleaseEnvironmentPolicy approval = policy();
        approval.setApprovalRequired(true);
        when(policyMapper.selectOne(any())).thenReturn(approval);

        AdmissionDecision decision = service.decide(1L, "EXECUTE_RELEASE", "HIGH", "RELEASE_ORDER", 100L);

        assertTrue(decision.isAllowed());
        assertTrue(decision.isApprovalRequired());

        ArgumentCaptor<SysAuditLog> captor = ArgumentCaptor.forClass(SysAuditLog.class);
        verify(sysAuditLogMapper, times(2)).insert(captor.capture());
        boolean hasApproval = false;
        boolean hasPass = false;
        for (SysAuditLog audit : captor.getAllValues()) {
            if (ReleaseAdmissionService.RELEASE_ADMISSION_APPROVAL_REQUIRED.equals(audit.getAction())) {
                hasApproval = true;
                assertEquals("INFO", audit.getResult());
            }
            if (ReleaseAdmissionService.RELEASE_ADMISSION_PASS.equals(audit.getAction())) {
                hasPass = true;
                assertEquals("SUCCESS", audit.getResult());
            }
        }
        assertTrue(hasApproval, "expected approval-required audit");
        assertTrue(hasPass, "expected pass audit");
    }

    @Test
    @DisplayName("无附加策略要求 → 放行并写 RELEASE_ADMISSION_PASS 审计")
    void plainPolicyPassesAndAudits() {
        when(policyMapper.selectOne(any())).thenReturn(policy());

        AdmissionDecision decision = service.decide(1L, "EXECUTE_RELEASE", "HIGH", "RELEASE_ORDER", 100L);

        assertTrue(decision.isAllowed());
        assertFalse(decision.isApprovalRequired());
        assertFalse(decision.isPrecheckRequired());
        ArgumentCaptor<SysAuditLog> captor = ArgumentCaptor.forClass(SysAuditLog.class);
        verify(sysAuditLogMapper, times(1)).insert(captor.capture());
        assertEquals(ReleaseAdmissionService.RELEASE_ADMISSION_PASS, captor.getValue().getAction());
    }
}
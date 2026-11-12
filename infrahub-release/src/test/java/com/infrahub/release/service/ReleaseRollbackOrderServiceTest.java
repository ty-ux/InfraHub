package com.infrahub.release.service;

import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.infrahub.base.service.BaseAppService;
import com.infrahub.base.service.BaseEnvService;
import com.infrahub.base.service.BaseServiceEntityService;
import com.infrahub.base.entity.BaseServiceEntity;
import com.infrahub.base.entity.BaseEnv;
import com.infrahub.common.error.ErrorCode;
import com.infrahub.common.exception.BizException;
import com.infrahub.infra.ssh.SshExecutionResult;
import com.infrahub.infra.ssh.SshExecutorService;
import com.infrahub.release.dto.ApplicationRollbackRequest;
import com.infrahub.release.entity.AssetHost;
import com.infrahub.release.entity.AssetHostSshConfig;
import com.infrahub.release.entity.ReleaseArtifact;
import com.infrahub.release.entity.ReleaseOrder;
import com.infrahub.release.entity.ReleaseRollbackOrder;
import com.infrahub.release.entity.ReleaseVersion;
import com.infrahub.release.entity.RuntimeDeployRecord;
import com.infrahub.release.entity.RuntimeServiceInstance;
import com.infrahub.release.mapper.ReleaseRollbackOrderMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ReleaseRollbackOrderService 纯单元测试：precheckRollbackOrder 的 SSH 异常分支必须同时
 * 产出失败 response 与失败 outcome（与发布侧行为一致，stdout/stderr 均为 null）；
 * 并覆盖“回滚目标协调必须先于目标读取”的调用顺序与拒绝分支。
 * 不连接真实 DB/Docker/SSH/Kubernetes；baseMapper 经最小反射注入。
 */
class ReleaseRollbackOrderServiceTest {

    private static final String ARTIFACT_URI = "ssh://repo/infrahub-demo-v1.0.0.tar.gz";

    private ReleaseOrderService releaseOrderService;
    private ReleaseVersionService releaseVersionService;
    private ReleaseArtifactService releaseArtifactService;
    private ReleaseCurrentVersionService releaseCurrentVersionService;
    private RuntimeDeployRecordService runtimeDeployRecordService;
    private RuntimeServiceInstanceService runtimeServiceInstanceService;
    private BaseServiceEntityService baseServiceEntityService;
    private BaseEnvService baseEnvService;
    private AssetHostSshConfigService assetHostSshConfigService;
    private AssetHostService assetHostService;
    private DeployConfigService deployConfigService;
    private ReleasePrecheckService releasePrecheckService;
    private SshExecutorService sshExecutorService;
    private ReleasePrecheckRecordService releasePrecheckRecordService;
    /**
     * 回滚目标协调器：本用例只验证“预检入口先取锁、再读目标”的调用顺序，
     * 协调器自身的冻结判据与事务守卫另见 {@link RollbackTargetCoordinatorTest}。
     */
    private RollbackTargetCoordinator rollbackTargetCoordinator;
    private ReleaseRollbackOrderMapper rollbackOrderMapper;

    private ReleaseRollbackOrderService service;

    @BeforeEach
    void setUp() {
        // 依赖按构造器顺序精确对齐（ReleaseRollbackOrderService 22 参构造器，协调器为末位新增参数）
        releaseOrderService = mock(ReleaseOrderService.class);
        BaseAppService baseAppService = mock(BaseAppService.class);
        releaseVersionService = mock(ReleaseVersionService.class);
        releaseArtifactService = mock(ReleaseArtifactService.class);
        releaseCurrentVersionService = mock(ReleaseCurrentVersionService.class);
        runtimeDeployRecordService = mock(RuntimeDeployRecordService.class);
        runtimeServiceInstanceService = mock(RuntimeServiceInstanceService.class);
        assetHostSshConfigService = mock(AssetHostSshConfigService.class);
        assetHostService = mock(AssetHostService.class);
        baseServiceEntityService = mock(BaseServiceEntityService.class);
        baseEnvService = mock(BaseEnvService.class);
        deployConfigService = mock(DeployConfigService.class);
        SecretValueResolver secretValueResolver = mock(SecretValueResolver.class);
        sshExecutorService = mock(SshExecutorService.class);
        releasePrecheckService = mock(ReleasePrecheckService.class);
        DeployFailureCategorizer deployFailureCategorizer = mock(DeployFailureCategorizer.class);
        ApprovalPolicyService approvalPolicyService = mock(ApprovalPolicyService.class);
        ApprovalRequestService approvalRequestService = mock(ApprovalRequestService.class);
        ReleaseAdmissionService admissionService = mock(ReleaseAdmissionService.class);
        releasePrecheckRecordService = mock(ReleasePrecheckRecordService.class);
        // 回滚边界判定用真实实现（配置服务为 mock）；本用例发布单的执行器类型与配置 ID 均为空 = 既有 SSH 兼容路径
        RollbackExecutorSupportService rollbackExecutorSupportService =
                new RollbackExecutorSupportService(mock(ReleaseExecutorConfigService.class));
        // 协调器整体 mock：本用例不启动事务，真实协调器会在无事务时 fail-closed
        rollbackTargetCoordinator = mock(RollbackTargetCoordinator.class);

        service = new ReleaseRollbackOrderService(
                releaseOrderService, baseAppService, releaseVersionService, releaseArtifactService,
                releaseCurrentVersionService, runtimeDeployRecordService, runtimeServiceInstanceService,
                assetHostSshConfigService, assetHostService, baseServiceEntityService, baseEnvService,
                deployConfigService, secretValueResolver, sshExecutorService, releasePrecheckService,
                deployFailureCategorizer, approvalPolicyService, approvalRequestService, admissionService,
                releasePrecheckRecordService, rollbackExecutorSupportService, rollbackTargetCoordinator, org.mockito.Mockito.mock(RuntimeExecutionUnknownService.class), org.mockito.Mockito.mock(com.infrahub.release.mapper.RuntimeDeployRecordMapper.class));

        // ServiceImpl 数据访问经 baseMapper；用最小反射注入 mock（不启动 Spring 容器）
        rollbackOrderMapper = mock(ReleaseRollbackOrderMapper.class);
        ReflectionTestUtils.setField(service, "baseMapper", rollbackOrderMapper);
        when(rollbackOrderMapper.selectById(10L)).thenReturn(rollbackOrder());

        ReleaseOrder releaseOrder = new ReleaseOrder();
        releaseOrder.setId(1L);
        releaseOrder.setEnvId(2L);
        releaseOrder.setServiceId(3L);
        releaseOrder.setAppId(5L);
        when(releaseOrderService.getById(1L)).thenReturn(releaseOrder);

        ReleaseVersion version = mock(ReleaseVersion.class);
        when(version.getId()).thenReturn(4L);
        when(version.getArtifactId()).thenReturn(6L);
        when(version.getIsDeleted()).thenReturn(false);
        // 与发布单 appId 一致，供 updateRollbackTarget 的版本归属校验通过
        when(version.getAppId()).thenReturn(5L);
        when(releaseVersionService.getById(4L)).thenReturn(version);

        ReleaseArtifact artifact = mock(ReleaseArtifact.class);
        when(artifact.getArtifactUri()).thenReturn(ARTIFACT_URI);
        when(releaseArtifactService.getById(6L)).thenReturn(artifact);

        RuntimeServiceInstance instance1 = new RuntimeServiceInstance();
        instance1.setId(2001L);
        instance1.setHostId(30L);
        RuntimeServiceInstance instance2 = new RuntimeServiceInstance();
        instance2.setId(2002L);
        instance2.setHostId(31L);
        when(runtimeServiceInstanceService.listByServiceAndEnv(3L, 2L)).thenReturn(List.of(instance1, instance2));

        when(deployConfigService.loadDeployConfig(5L, 2L)).thenReturn(Map.of("deploy.command", "echo ${serviceCode}"));
        when(assetHostService.getById(30L)).thenReturn(mock(AssetHost.class));
        when(assetHostService.getById(31L)).thenReturn(mock(AssetHost.class));
        when(assetHostSshConfigService.getEnabledByHostId(30L)).thenReturn(mock(AssetHostSshConfig.class));
        when(assetHostSshConfigService.getEnabledByHostId(31L)).thenReturn(mock(AssetHostSshConfig.class));
    }

    private ReleaseRollbackOrder rollbackOrder() {
        ReleaseRollbackOrder rollbackOrder = new ReleaseRollbackOrder();
        rollbackOrder.setId(10L);
        rollbackOrder.setStatus("CREATED");
        rollbackOrder.setReleaseOrderId(1L);
        rollbackOrder.setToVersionId(4L);
        return rollbackOrder;
    }

    private ReleasePrecheckResult okResult() {
        OffsetDateTime now = OffsetDateTime.now();
        return new ReleasePrecheckResult(true, "stdout-line-ok", "stderr-line-ok", "",
                SshExecutionResult.success("stdout-line-ok", "stderr-line-ok", now, now));
    }

    @Test
    void rejectsVersionBuiltForAnotherServiceInTheSameApplication() {
        BaseServiceEntity target = new BaseServiceEntity();
        target.setId(3L);
        target.setAppId(5L);
        when(baseServiceEntityService.getById(3L)).thenReturn(target);
        when(baseEnvService.getById(2L)).thenReturn(new BaseEnv());
        ReleaseVersion version = new ReleaseVersion();
        version.setAppId(5L);
        version.setBuildPipelineRunId(40L);
        when(releaseVersionService.getById(4L)).thenReturn(version);
        when(releaseVersionService.findBuildServiceId(40L)).thenReturn(7L);

        var request = new ApplicationRollbackRequest(5L, 2L, 3L, 4L, List.of(2001L));
        assertThrows(BizException.class, () -> service.rollbackApplication(request));
        verify(rollbackTargetCoordinator, never()).lockServiceForExecution(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void directRollbackUpdatesOnlyTheSelectedInstanceAndRecordsItsIdentity() {
        ReleaseRollbackOrder rollback = rollbackOrder();
        rollback.setReleaseOrderId(null);
        rollback.setServiceId(3L);
        rollback.setEnvId(2L);
        rollback.setTargetInstanceIds("2002");
        when(rollbackTargetCoordinator.lockForExecution(10L)).thenReturn(rollback);
        BaseServiceEntity target = new BaseServiceEntity();
        target.setId(3L);
        target.setAppId(5L);
        when(baseServiceEntityService.getById(3L)).thenReturn(target);
        when(baseEnvService.getById(2L)).thenReturn(new BaseEnv());
        LambdaQueryChainWrapper<ReleaseOrder> runningReleases = mock(LambdaQueryChainWrapper.class);
        when(runningReleases.eq(any(), any())).thenReturn(runningReleases);
        when(runningReleases.count()).thenReturn(0L);
        when(releaseOrderService.lambdaQuery()).thenReturn(runningReleases);
        when(releasePrecheckService.check(any(), any(), any(), any(), any())).thenReturn(okResult());
        when(sshExecutorService.execute(any())).thenReturn(okResult().executionResult());

        assertTrue(service.doExecuteRollbackOrder(10L));

        var record = ArgumentCaptor.forClass(RuntimeDeployRecord.class);
        verify(runtimeDeployRecordService).saveWithInstance(record.capture());
        assertEquals(2002L, record.getValue().getInstanceId());
        assertEquals(10L, record.getValue().getRollbackOrderId());
        assertNull(record.getValue().getReleaseOrderId());
        verify(sshExecutorService).execute(any());
        verify(runtimeServiceInstanceService).updateVersion(List.of(2002L), 4L);
        verify(releaseCurrentVersionService, never()).markRollbackVersion(any(), any(), any(), any());
    }

    @Test
    void directRollbackPrechecksOnlySelectedInstancesWithoutReleaseOrder() {
        ReleaseRollbackOrder rollback = rollbackOrder();
        rollback.setReleaseOrderId(null);
        rollback.setServiceId(3L);
        rollback.setEnvId(2L);
        rollback.setTargetInstanceIds("2002");
        when(rollbackOrderMapper.selectById(10L)).thenReturn(rollback);
        BaseServiceEntity target = new BaseServiceEntity();
        target.setId(3L);
        target.setAppId(5L);
        when(baseServiceEntityService.getById(3L)).thenReturn(target);
        when(releasePrecheckService.check(any(), any(), any(), any(), any())).thenReturn(okResult());

        var checks = service.precheckRollbackOrder(10L);

        assertEquals(List.of(2002L), checks.stream().map(check -> check.getInstanceId()).toList());
        verify(releaseOrderService, never()).getById(any());
        verify(assetHostService, never()).getById(30L);
        verify(releasePrecheckService, times(1)).check(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("回滚：首实例 SSH 异常、后续实例成功：响应包含两个实例，失败响应字段与脱敏错误信息正确")
    void sshExceptionOnFirstInstanceStillReturnsCompleteResponse() {
        when(releasePrecheckService.check(any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("模拟 SSH 连接失败"))
                .thenReturn(okResult());

        List<com.infrahub.release.dto.ReleasePrecheckInstanceResponse> responses =
                service.precheckRollbackOrder(10L);

        assertEquals(2, responses.size(), "异常实例也必须出现在响应列表中");

        com.infrahub.release.dto.ReleasePrecheckInstanceResponse first = responses.get(0);
        assertFalse(first.isSuccess());
        assertEquals(30L, first.getHostId());
        assertEquals(2001L, first.getInstanceId());
        assertEquals(ARTIFACT_URI, first.getArtifactUri());
        assertEquals("SSH 执行异常: IllegalStateException", first.getErrorMessage());
        assertFalse(first.getErrorMessage().contains("模拟"), "不得输出异常 message 原文");
        assertNull(first.getStdout());
        assertNull(first.getStderr());

        com.infrahub.release.dto.ReleasePrecheckInstanceResponse second = responses.get(1);
        assertTrue(second.isSuccess());
        assertEquals(31L, second.getHostId());
        assertEquals(2002L, second.getInstanceId());
        assertEquals(ARTIFACT_URI, second.getArtifactUri());
        assertEquals("stdout-line-ok", second.getStdout());
        assertEquals("stderr-line-ok", second.getStderr());

        verify(releasePrecheckService, times(2)).check(any(), any(), any(), any());
    }

    @Test
    @DisplayName("回滚异常路径：outcome 与 response 逐实例一一对应，聚合首项失败，记录写入每 API 调用恰好一次")
    void sshExceptionOutcomesMatchResponsesAndRecordedOnce() {
        when(releasePrecheckService.check(any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("模拟 SSH 连接失败"))
                .thenReturn(okResult());

        service.precheckRollbackOrder(10L);

        ArgumentCaptor<List<ReleasePrecheckRecordService.PrecheckInstanceOutcome>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(releasePrecheckRecordService, times(1)).recordPrecheck(
                eq("ROLLBACK_ORDER"), eq(10L), eq(2L), eq(3L), captor.capture());
        List<ReleasePrecheckRecordService.PrecheckInstanceOutcome> outcomes = captor.getValue();
        assertEquals(2, outcomes.size(), "每个被遍历实例必须恰好一个 outcome");
        assertFalse(outcomes.get(0).success(), "首实例为失败，聚合记录 success=false");
        assertEquals("SSH 执行异常: IllegalStateException", outcomes.get(0).failureReason());
        assertTrue(outcomes.get(1).success(), "后续实例成功");
    }

    @Test
    @DisplayName("回滚全部成功路径无回归：响应完整、outcome 全成功、写入一次")
    void allSuccessPathNoRegression() {
        when(releasePrecheckService.check(any(), any(), any(), any())).thenReturn(okResult());

        List<com.infrahub.release.dto.ReleasePrecheckInstanceResponse> responses =
                service.precheckRollbackOrder(10L);

        assertEquals(2, responses.size());
        assertTrue(responses.get(0).isSuccess());
        assertTrue(responses.get(1).isSuccess());
        assertEquals("", responses.get(0).getErrorMessage());
        assertEquals("stdout-line-ok", responses.get(1).getStdout());

        ArgumentCaptor<List<ReleasePrecheckRecordService.PrecheckInstanceOutcome>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(releasePrecheckRecordService, times(1)).recordPrecheck(
                eq("ROLLBACK_ORDER"), eq(10L), eq(2L), eq(3L), captor.capture());
        List<ReleasePrecheckRecordService.PrecheckInstanceOutcome> outcomes = captor.getValue();
        assertEquals(2, outcomes.size());
        assertTrue(outcomes.get(0).success());
        assertTrue(outcomes.get(1).success());
    }

    @Test
    @DisplayName("并发协调：预检入口必须先取回滚单行锁，再读取本次预检使用的目标版本")
    void precheckLocksRollbackOrderBeforeReadingTarget() {
        when(releasePrecheckService.check(any(), any(), any(), any())).thenReturn(okResult());

        service.precheckRollbackOrder(10L);

        InOrder inOrder = inOrder(rollbackTargetCoordinator, releaseVersionService, releasePrecheckRecordService);
        inOrder.verify(rollbackTargetCoordinator).lockRollbackOrder(10L);
        inOrder.verify(releaseVersionService).getById(4L);
        inOrder.verify(releasePrecheckRecordService).recordPrecheck(eq("ROLLBACK_ORDER"), eq(10L), eq(2L), eq(3L), any());
    }

    @Test
    @DisplayName("并发协调：目标修改入口取同一把锁，协调器判定已有预检证据时拒绝且不写入")
    void updateRollbackTargetRejectedWhenPrecheckEvidenceExists() {
        doThrow(new BizException(ErrorCode.VALIDATION_ERROR, "该回滚单已有预检记录，不能更换回滚目标，请新建回滚单"))
                .when(rollbackTargetCoordinator).assertNoPrecheckEvidence(10L);

        BizException ex = assertThrows(BizException.class, () -> service.updateRollbackTarget(10L, 4L));

        assertEquals("该回滚单已有预检记录，不能更换回滚目标，请新建回滚单", ex.getMessage());
        InOrder inOrder = inOrder(rollbackTargetCoordinator, rollbackOrderMapper);
        inOrder.verify(rollbackTargetCoordinator).lockRollbackOrder(10L);
        inOrder.verify(rollbackTargetCoordinator).assertNoPrecheckEvidence(10L);
        verify(rollbackOrderMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("并发协调：目标修改入口在协调器判定存在待处理审批时拒绝且不写入")
    void updateRollbackTargetRejectedWhenPendingApprovalExists() {
        doThrow(new BizException(ErrorCode.VALIDATION_ERROR, "该回滚单存在待处理审批，不能更换回滚目标"))
                .when(rollbackTargetCoordinator).assertNoPendingApproval(10L);

        BizException ex = assertThrows(BizException.class, () -> service.updateRollbackTarget(10L, 4L));

        assertEquals("该回滚单存在待处理审批，不能更换回滚目标", ex.getMessage());
        verify(rollbackTargetCoordinator).lockRollbackOrder(10L);
        verify(rollbackOrderMapper, never()).update(any(), any());
    }
}

package com.infrahub.release.service;

import com.infrahub.base.entity.BaseEnv;
import com.infrahub.base.entity.BaseServiceEntity;
import com.infrahub.base.service.BaseAppService;
import com.infrahub.base.service.BaseEnvService;
import com.infrahub.base.service.BaseServiceEntityService;
import com.infrahub.common.error.ErrorCode;
import com.infrahub.common.exception.BizException;
import com.infrahub.infra.ssh.SshExecutionResult;
import com.infrahub.pipeline.service.PipelineRunService;
import com.infrahub.release.entity.AssetHost;
import com.infrahub.release.entity.AssetHostSshConfig;
import com.infrahub.release.entity.ReleaseArtifact;
import com.infrahub.release.entity.ReleaseOrder;
import com.infrahub.release.entity.ReleaseVersion;
import com.infrahub.release.entity.RuntimeServiceInstance;
import com.infrahub.release.mapper.ReleaseOrderMapper;
import com.infrahub.release.mapper.ReleaseRollbackOrderMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ReleaseOrderService.precheckReleaseOrder 纯单元测试：SSH 异常分支必须同时产出
 * 失败 response 与失败 outcome（每个被遍历实例恰好一个 response 一个 outcome）。
 * 不连接真实 DB/Docker/SSH/Kubernetes；baseMapper 经最小反射注入。
 */
class ReleaseOrderServiceTest {

    private static final String ARTIFACT_URI = "ssh://repo/infrahub-demo-v1.0.0.tar.gz";

    private ReleaseVersionService releaseVersionService;
    private ReleaseArtifactService releaseArtifactService;
    private RuntimeServiceInstanceService runtimeServiceInstanceService;
    private AssetHostSshConfigService assetHostSshConfigService;
    private AssetHostService assetHostService;
    private DeployConfigService deployConfigService;
    private ReleasePrecheckService releasePrecheckService;
    private ReleasePrecheckRecordService releasePrecheckRecordService;
    private ReleaseDeployExecutionService releaseDeployExecutionService;
    private ReleaseExecutionClaimService releaseExecutionClaimService;

    private ReleaseOrderService service;

    @BeforeEach
    void setUp() {
        // 依赖按构造器顺序精确对齐（ReleaseOrderService 24 参构造器）
        BaseAppService baseAppService = mock(BaseAppService.class);
        BaseServiceEntityService baseServiceEntityService = mock(BaseServiceEntityService.class);
        BaseEnvService baseEnvService = mock(BaseEnvService.class);
        releaseVersionService = mock(ReleaseVersionService.class);
        releaseArtifactService = mock(ReleaseArtifactService.class);
        ReleaseCurrentVersionService releaseCurrentVersionService = mock(ReleaseCurrentVersionService.class);
        RuntimeDeployRecordService runtimeDeployRecordService = mock(RuntimeDeployRecordService.class);
        runtimeServiceInstanceService = mock(RuntimeServiceInstanceService.class);
        assetHostSshConfigService = mock(AssetHostSshConfigService.class);
        assetHostService = mock(AssetHostService.class);
        deployConfigService = mock(DeployConfigService.class);
        SecretValueResolver secretValueResolver = mock(SecretValueResolver.class);
        com.infrahub.infra.ssh.SshExecutorService sshExecutorService = mock(com.infrahub.infra.ssh.SshExecutorService.class);
        releasePrecheckService = mock(ReleasePrecheckService.class);
        releaseDeployExecutionService = mock(ReleaseDeployExecutionService.class);
        ReleaseRollbackOrderMapper releaseRollbackOrderMapper = mock(ReleaseRollbackOrderMapper.class);
        DeployFailureCategorizer deployFailureCategorizer = mock(DeployFailureCategorizer.class);
        PipelineRunService pipelineRunService = mock(PipelineRunService.class);
        ApprovalPolicyService approvalPolicyService = mock(ApprovalPolicyService.class);
        ApprovalRequestService approvalRequestService = mock(ApprovalRequestService.class);
        ReleaseAdmissionService admissionService = mock(ReleaseAdmissionService.class);
        ReleaseExecutorConfigService releaseExecutorConfigService = mock(ReleaseExecutorConfigService.class);
        releasePrecheckRecordService = mock(ReleasePrecheckRecordService.class);
        releaseExecutionClaimService = mock(ReleaseExecutionClaimService.class);

        service = new ReleaseOrderService(
                baseAppService, baseServiceEntityService, baseEnvService, releaseVersionService,
                releaseArtifactService, releaseCurrentVersionService, runtimeDeployRecordService,
                runtimeServiceInstanceService, assetHostSshConfigService, assetHostService,
                deployConfigService, secretValueResolver, sshExecutorService, releasePrecheckService,
                releaseDeployExecutionService, releaseRollbackOrderMapper, deployFailureCategorizer,
                pipelineRunService, approvalPolicyService, approvalRequestService, admissionService,
                releaseExecutorConfigService, releasePrecheckRecordService, releaseExecutionClaimService, org.mockito.Mockito.mock(ReleaseDeployCoverageService.class), org.mockito.Mockito.mock(VersionRegistrationConsistencyService.class));

        // ServiceImpl 数据访问经 baseMapper；用最小反射注入 mock（不启动 Spring 容器）
        ReleaseOrderMapper orderMapper = mock(ReleaseOrderMapper.class);
        ReflectionTestUtils.setField(service, "baseMapper", orderMapper);
        when(orderMapper.selectById(10L)).thenReturn(order());

        ReleaseVersion version = mock(ReleaseVersion.class);
        when(version.getId()).thenReturn(4L);
        when(version.getArtifactId()).thenReturn(6L);
        when(version.getIsDeleted()).thenReturn(false);
        when(releaseVersionService.getById(4L)).thenReturn(version);

        ReleaseArtifact artifact = mock(ReleaseArtifact.class);
        when(artifact.getArtifactUri()).thenReturn(ARTIFACT_URI);
        when(releaseArtifactService.getById(6L)).thenReturn(artifact);

        RuntimeServiceInstance instance1 = new RuntimeServiceInstance();
        instance1.setId(1001L);
        instance1.setHostId(20L);
        RuntimeServiceInstance instance2 = new RuntimeServiceInstance();
        instance2.setId(1002L);
        instance2.setHostId(21L);
        when(runtimeServiceInstanceService.listByServiceAndEnv(3L, 2L)).thenReturn(List.of(instance1, instance2));

        when(deployConfigService.loadDeployConfig(5L, 2L)).thenReturn(Map.of("deploy.command", "echo ${serviceCode}"));
        when(baseServiceEntityService.getById(3L)).thenReturn(mock(BaseServiceEntity.class));
        when(baseEnvService.getById(2L)).thenReturn(mock(BaseEnv.class));
        when(assetHostService.getById(20L)).thenReturn(mock(AssetHost.class));
        when(assetHostService.getById(21L)).thenReturn(mock(AssetHost.class));
        when(assetHostSshConfigService.getEnabledByHostId(20L)).thenReturn(mock(AssetHostSshConfig.class));
        when(assetHostSshConfigService.getEnabledByHostId(21L)).thenReturn(mock(AssetHostSshConfig.class));
    }

    private ReleaseOrder order() {
        ReleaseOrder order = new ReleaseOrder();
        order.setId(10L);
        order.setStatus("CREATED");
        order.setEnvId(2L);
        order.setServiceId(3L);
        order.setVersionId(4L);
        order.setAppId(5L);
        return order;
    }

    /** 认领结果只能由认领服务产出，内部执行入口只接受这一类型。 */
    private ReleaseExecutionClaimService.ClaimedExecution claimed(ReleaseOrder order) {
        return new ReleaseExecutionClaimService.ClaimedExecution(order);
    }

    private ReleasePrecheckResult okResult() {
        OffsetDateTime now = OffsetDateTime.now();
        return new ReleasePrecheckResult(true, "stdout-line-ok", "stderr-line-ok", "",
                SshExecutionResult.success("stdout-line-ok", "stderr-line-ok", now, now));
    }

    @Test
    @DisplayName("首实例 SSH 异常、后续实例成功：响应包含两个实例，失败响应字段与脱敏错误信息正确")
    void sshExceptionOnFirstInstanceStillReturnsCompleteResponse() {
        when(releasePrecheckService.check(any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("模拟 SSH 连接失败"))
                .thenReturn(okResult());

        List<com.infrahub.release.dto.ReleasePrecheckInstanceResponse> responses =
                service.precheckReleaseOrder(10L);

        assertEquals(2, responses.size(), "异常实例也必须出现在响应列表中");

        com.infrahub.release.dto.ReleasePrecheckInstanceResponse first = responses.get(0);
        assertFalse(first.isSuccess());
        assertEquals(20L, first.getHostId());
        assertEquals(1001L, first.getInstanceId());
        assertEquals(ARTIFACT_URI, first.getArtifactUri());
        assertEquals("SSH 执行异常: IllegalStateException", first.getErrorMessage());
        assertFalse(first.getErrorMessage().contains("模拟"), "不得输出异常 message 原文");
        assertNull(first.getStdout());
        assertNull(first.getStderr());

        com.infrahub.release.dto.ReleasePrecheckInstanceResponse second = responses.get(1);
        assertTrue(second.isSuccess());
        assertEquals(21L, second.getHostId());
        assertEquals(1002L, second.getInstanceId());
        assertEquals(ARTIFACT_URI, second.getArtifactUri());
        assertEquals("stdout-line-ok", second.getStdout());
        assertEquals("stderr-line-ok", second.getStderr());

        verify(releasePrecheckService, times(2)).check(any(), any(), any(), any());
    }

    @Test
    @DisplayName("SSH 异常路径：outcome 与 response 逐实例一一对应，聚合首项失败，记录写入每 API 调用恰好一次")
    void sshExceptionOutcomesMatchResponsesAndRecordedOnce() {
        when(releasePrecheckService.check(any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("模拟 SSH 连接失败"))
                .thenReturn(okResult());

        service.precheckReleaseOrder(10L);

        ArgumentCaptor<List<ReleasePrecheckRecordService.PrecheckInstanceOutcome>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(releasePrecheckRecordService, times(1)).recordPrecheck(
                eq("RELEASE_ORDER"), eq(10L), eq(2L), eq(3L), captor.capture());
        List<ReleasePrecheckRecordService.PrecheckInstanceOutcome> outcomes = captor.getValue();
        assertEquals(2, outcomes.size(), "每个被遍历实例必须恰好一个 outcome");
        assertFalse(outcomes.get(0).success(), "首实例为失败，聚合记录 success=false");
        assertEquals("SSH 执行异常: IllegalStateException", outcomes.get(0).failureReason());
        assertTrue(outcomes.get(1).success(), "后续实例成功");
    }

    @Test
    @DisplayName("全部成功路径无回归：响应完整、outcome 全成功、写入一次")
    void allSuccessPathNoRegression() {
        when(releasePrecheckService.check(any(), any(), any(), any())).thenReturn(okResult());

        List<com.infrahub.release.dto.ReleasePrecheckInstanceResponse> responses =
                service.precheckReleaseOrder(10L);

        assertEquals(2, responses.size());
        assertTrue(responses.get(0).isSuccess());
        assertTrue(responses.get(1).isSuccess());
        assertEquals("", responses.get(0).getErrorMessage());
        assertEquals("stdout-line-ok", responses.get(1).getStdout());

        ArgumentCaptor<List<ReleasePrecheckRecordService.PrecheckInstanceOutcome>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(releasePrecheckRecordService, times(1)).recordPrecheck(
                eq("RELEASE_ORDER"), eq(10L), eq(2L), eq(3L), captor.capture());
        List<ReleasePrecheckRecordService.PrecheckInstanceOutcome> outcomes = captor.getValue();
        assertEquals(2, outcomes.size());
        assertTrue(outcomes.get(0).success());
        assertTrue(outcomes.get(1).success());
    }

    // ---------- 发布执行三段式：短事务认领 → 事务外调用外部执行器 → 短事务终态 ----------

    @Test
    @DisplayName("执行成功：认领 → 事务外调用执行器 → markSucceeded，执行器调用期间无活动事务")
    void executeOrderClaimsBeforeCallingExecutorAndRecordsSuccessAfterwards() {
        ReleaseOrder claimed = order();
        claimed.setStatus("RUNNING");
        when(releaseExecutionClaimService.claimForExecution(10L)).thenReturn(claimed(claimed));

        AtomicBoolean transactionActiveDuringExecutorCall = new AtomicBoolean(true);
        when(releaseDeployExecutionService.executeReleaseDeploy(any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    transactionActiveDuringExecutorCall.set(
                            TransactionSynchronizationManager.isActualTransactionActive());
                    return new ReleaseDeployExecutionResult(true, List.of(1001L, 1002L), "部署成功");
                });

        assertTrue(service.doExecuteReleaseOrder(10L));

        assertFalse(transactionActiveDuringExecutorCall.get(),
                "服务自身不得在调用外部执行器时持有事务（容器级事务语义另由代理级测试覆盖）");

        InOrder sequence = inOrder(releaseExecutionClaimService, releaseDeployExecutionService);
        sequence.verify(releaseExecutionClaimService).claimForExecution(10L);
        sequence.verify(releaseDeployExecutionService)
                .executeReleaseDeploy(any(), any(), any(), any(), any(), any(), any());
        sequence.verify(releaseExecutionClaimService).markSucceeded(10L, 2L, 3L, List.of(1001L, 1002L), 4L);
        verify(releaseExecutionClaimService, never()).markFailed(any(), any());
    }

    @Test
    @DisplayName("执行器返回失败：记录 markFailed 并保留执行器给出的原因，不记录成功终态")
    void failedExecutionResultRecordsFailureEvidence() {
        when(releaseExecutionClaimService.claimForExecution(10L)).thenReturn(claimed(order()));
        when(releaseDeployExecutionService.executeReleaseDeploy(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new ReleaseDeployExecutionResult(false, List.of(), "kubectl apply 失败",
                        com.infrahub.release.support.DeployExecutionOutcome.FAILED, 1, 0, 1, 0, 0, "EXECUTION_FAILED", List.of()));

        assertFalse(service.doExecuteReleaseOrder(10L));

        verify(releaseExecutionClaimService).markFailed(10L, "kubectl apply 失败");
        verify(releaseExecutionClaimService, never()).markSucceeded(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("执行器抛异常：原始异常原样抛出，同时落非空失败原因（含原始异常类型与消息）")
    void executorExceptionRecordsNonBlankFailureReason() {
        when(releaseExecutionClaimService.claimForExecution(10L)).thenReturn(claimed(order()));
        when(releaseDeployExecutionService.executeReleaseDeploy(any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("kubectl 连接中断"));

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> service.doExecuteReleaseOrder(10L));

        assertEquals("kubectl 连接中断", thrown.getMessage(), "原始异常不得被替换或包装");

        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(releaseExecutionClaimService).markFailed(eq(10L), reason.capture());
        assertNotNull(reason.getValue());
        assertFalse(reason.getValue().isBlank(), "失败原因必须非空");
        assertTrue(reason.getValue().contains("PRE_EXECUTION_VALIDATION"), "返回稳定分类，不泄露原始执行异常");
        assertFalse(reason.getValue().contains("kubectl 连接中断"));
        verify(releaseExecutionClaimService, never()).markSucceeded(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("失败终态记录自身失败：原始异常仍然抛出，不被终态记录异常掩盖")
    void failureRecordingFailureDoesNotMaskOriginalException() {
        when(releaseExecutionClaimService.claimForExecution(10L)).thenReturn(claimed(order()));
        when(releaseDeployExecutionService.executeReleaseDeploy(any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("kubectl 连接中断"));
        doAnswer(invocation -> {
            throw new IllegalStateException("终态库写入失败");
        }).when(releaseExecutionClaimService).markFailed(any(), any());

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> service.doExecuteReleaseOrder(10L));

        assertEquals("kubectl 连接中断", thrown.getMessage());
    }

    @Test
    @DisplayName("认领被拒：不调用外部执行器，也不记录任何终态")
    void claimRejectionStopsBeforeExecutor() {
        when(releaseExecutionClaimService.claimForExecution(10L))
                .thenThrow(new BizException(ErrorCode.VALIDATION_ERROR, "当前发布单状态不允许执行"));

        BizException thrown = assertThrows(BizException.class, () -> service.doExecuteReleaseOrder(10L));

        assertEquals("当前发布单状态不允许执行", thrown.getMessage());
        verify(releaseDeployExecutionService, never())
                .executeReleaseDeploy(any(), any(), any(), any(), any(), any(), any());
        verify(releaseExecutionClaimService, never()).markSucceeded(any(), any(), any(), any(), any());
        verify(releaseExecutionClaimService, never()).markFailed(any(), any());
    }

    @Test
    @DisplayName("订单状态不可执行：既有门禁先于认领拒绝，消息与契约不变")
    void nonExecutableStatusRejectedBeforeClaim() {
        ReleaseOrder running = order();
        running.setStatus("RUNNING");
        ReleaseOrderMapper orderMapper = (ReleaseOrderMapper) ReflectionTestUtils.getField(service, "baseMapper");
        when(orderMapper.selectById(10L)).thenReturn(running);

        when(releaseExecutionClaimService.claimForExecution(10L))
                .thenThrow(new BizException(ErrorCode.VALIDATION_ERROR, "当前发布单状态不允许执行"));

        BizException thrown = assertThrows(BizException.class, () -> service.doExecuteReleaseOrder(10L));

        assertEquals("当前发布单状态不允许执行", thrown.getMessage());
        verify(releaseExecutionClaimService).claimForExecution(10L);
    }

    // ---------- 已认领执行入口（审批回调）：不再认领，只执行本次认领的结果 ----------

    @Test
    @DisplayName("已认领执行：不再认领第二次，直接用本次认领结果调用执行器并记录成功终态")
    void claimedEntryExecutesWithoutClaimingAgain() {
        ReleaseOrder running = order();
        running.setStatus("RUNNING");
        when(releaseDeployExecutionService.executeReleaseDeploy(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new ReleaseDeployExecutionResult(true, List.of(1001L, 1002L), "部署成功"));

        assertTrue(service.executeClaimedReleaseOrder(claimed(running)));

        verify(releaseExecutionClaimService, never()).claimForExecution(any());
        verify(releaseExecutionClaimService, never()).claimForApprovedExecution(any(), any(), any(), any());
        verify(releaseExecutionClaimService).markSucceeded(10L, 2L, 3L, List.of(1001L, 1002L), 4L);
        verify(releaseExecutionClaimService, never()).markFailed(any(), any());
    }

    @Test
    @DisplayName("已认领执行：缺少本次认领结果时失败关闭，不执行也不写终态")
    void claimedEntryRejectsMissingClaimResult() {
        BizException thrown = assertThrows(BizException.class,
                () -> service.executeClaimedReleaseOrder(null));

        assertTrue(thrown.getMessage().contains("缺少本次成功认领的结果"), "必须拒绝“仅凭订单状态就执行”");
        verify(releaseDeployExecutionService, never())
                .executeReleaseDeploy(any(), any(), any(), any(), any(), any(), any());
        verify(releaseExecutionClaimService, never()).markFailed(any(), any());
        verify(releaseExecutionClaimService, never()).markSucceeded(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("已认领执行：认领之后加载执行数据失败，订单必须收敛成 FAILED 并补齐失败证据")
    void claimedEntryRecordsFailureWhenInputLoadingFails() {
        ReleaseOrder running = order();
        running.setStatus("RUNNING");
        when(runtimeServiceInstanceService.listByServiceAndEnv(3L, 2L)).thenReturn(List.of());

        BizException thrown = assertThrows(BizException.class,
                () -> service.executeClaimedReleaseOrder(claimed(running)));

        assertEquals("当前服务在目标环境下没有可发布的实例", thrown.getMessage());

        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(releaseExecutionClaimService).markFailed(eq(10L), reason.capture());
        assertTrue(reason.getValue().contains("PRE_EXECUTION_VALIDATION"), "失败原因必须可排障");
        verify(releaseDeployExecutionService, never())
                .executeReleaseDeploy(any(), any(), any(), any(), any(), any(), any());
        verify(releaseExecutionClaimService, never()).markSucceeded(any(), any(), any(), any(), any());
    }
}
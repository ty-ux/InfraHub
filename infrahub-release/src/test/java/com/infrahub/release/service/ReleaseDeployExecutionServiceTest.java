package com.infrahub.release.service;

import com.infrahub.base.entity.BaseEnv;
import com.infrahub.base.entity.BaseServiceEntity;
import com.infrahub.common.error.ErrorCode;
import com.infrahub.common.exception.BizException;
import com.infrahub.infra.ssh.SshExecutionRequest;
import com.infrahub.infra.ssh.SshExecutionResult;
import com.infrahub.infra.ssh.SshExecutorService;
import com.infrahub.release.dto.DockerExecutionConfig;
import com.infrahub.release.entity.AssetHost;
import com.infrahub.release.entity.AssetHostSshConfig;
import com.infrahub.release.entity.ReleaseArtifact;
import com.infrahub.release.entity.ReleaseExecutorConfig;
import com.infrahub.release.entity.ReleaseOrder;
import com.infrahub.release.entity.ReleaseVersion;
import com.infrahub.release.entity.RuntimeDeployRecord;
import com.infrahub.release.entity.RuntimeServiceInstance;
import com.infrahub.release.service.executor.ReleaseExecutionContext;
import com.infrahub.release.service.executor.ReleaseExecutionResult;
import com.infrahub.release.service.executor.ReleaseExecutor;
import com.infrahub.release.service.executor.ReleaseExecutorRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ReleaseDeployExecutionService 纯单元测试：
 * D1 解析接入（parse 恰好在实例循环/RUNNING 记录创建之前调用一次、解析失败不建任何记录）、
 * D3 兜底（失败结果时间与 deployLog 落到 runtime_deploy_record）、SSH 默认路径不回归。
 */
class ReleaseDeployExecutionServiceTest {

    private RuntimeDeployRecordService runtimeDeployRecordService;
    private AssetHostSshConfigService assetHostSshConfigService;
    private AssetHostService assetHostService;
    private SecretValueResolver secretValueResolver;
    private SshExecutorService sshExecutorService;
    private ReleasePrecheckService releasePrecheckService;
    private ReleaseExecutorRegistry releaseExecutorRegistry;
    private ReleaseExecutorConfigService releaseExecutorConfigService;
    private ResourceGovernanceRecordService resourceGovernanceRecordService;
    private ReleaseExecutorConfigParser releaseExecutorConfigParser;
    private ReleaseDeployExecutionService service;

    @BeforeEach
    void setUp() {
        runtimeDeployRecordService = mock(RuntimeDeployRecordService.class);
        assetHostSshConfigService = mock(AssetHostSshConfigService.class);
        assetHostService = mock(AssetHostService.class);
        secretValueResolver = mock(SecretValueResolver.class);
        sshExecutorService = mock(SshExecutorService.class);
        releasePrecheckService = mock(ReleasePrecheckService.class);
        releaseExecutorRegistry = mock(ReleaseExecutorRegistry.class);
        releaseExecutorConfigService = mock(ReleaseExecutorConfigService.class);
        resourceGovernanceRecordService = mock(ResourceGovernanceRecordService.class);
        releaseExecutorConfigParser = mock(ReleaseExecutorConfigParser.class);
        service = new ReleaseDeployExecutionService(
                runtimeDeployRecordService,
                assetHostSshConfigService,
                assetHostService,
                secretValueResolver,
                sshExecutorService,
                releasePrecheckService,
                releaseExecutorRegistry,
                releaseExecutorConfigService,
                resourceGovernanceRecordService,
                releaseExecutorConfigParser);
    }

    private ReleaseOrder order(Long executorConfigId) {
        ReleaseOrder order = new ReleaseOrder();
        order.setId(10L);
        order.setExecutorConfigId(executorConfigId);
        order.setEnvId(1L);
        order.setServiceId(2L);
        order.setExecutorType("DOCKER");
        order.setCreatedBy(1L);
        return order;
    }

    private RuntimeServiceInstance instance(Long id, Long hostId) {
        RuntimeServiceInstance instance = new RuntimeServiceInstance();
        instance.setId(id);
        instance.setHostId(hostId);
        return instance;
    }

    private ReleaseExecutorConfig dockerConfig(Long id, Long envId) {
        ReleaseExecutorConfig config = new ReleaseExecutorConfig();
        config.setId(id);
        config.setExecutorType("DOCKER");
        config.setEnvId(envId);
        config.setConfigPayload("{\"image\":\"nginx:1.25\"}");
        return config;
    }

    private ReleaseVersion versionEntity() {
        ReleaseVersion version = mock(ReleaseVersion.class);
        when(version.getId()).thenReturn(1L);
        when(version.getVersionNo()).thenReturn("v1.0.0");
        return version;
    }

    private BaseServiceEntity serviceEntity() {
        return mock(BaseServiceEntity.class);
    }

    private BaseEnv envEntity() {
        return mock(BaseEnv.class);
    }

    // ---------- D1：执行器路径 ----------

    @Test
    @DisplayName("D1：DOCKER 路径 parse 恰好调用一次，类型化配置进入上下文")
    void dockerPathParsesExactlyOnceAndPassesTypedConfig() {
        ReleaseExecutorConfig config = dockerConfig(55L, 1L);
        DockerExecutionConfig typed = DockerExecutionConfig.builder()
                .image("nginx:1.25").containerName("app-1").build();
        ReleaseExecutor executor = mock(ReleaseExecutor.class);
        when(releaseExecutorConfigService.findEnabledById(55L)).thenReturn(config);
        when(releaseExecutorConfigParser.parse(config))
                .thenReturn(ReleaseExecutorConfigParser.ParseResult.success(typed));
        when(releaseExecutorRegistry.getExecutor("DOCKER")).thenReturn(executor);
        when(executor.execute(any())).thenReturn(ReleaseExecutionResult.success("部署成功"));
        when(assetHostService.getById(100L)).thenReturn(mock(AssetHost.class));

        ReleaseDeployExecutionResult result = service.executeReleaseDeploy(
                order(55L), serviceEntity(), envEntity(), versionEntity(),
                mock(ReleaseArtifact.class), List.of(instance(1L, 100L)), Map.of());

        assertTrue(result.success());
        verify(releaseExecutorConfigParser, times(1)).parse(config);

        ArgumentCaptor<ReleaseExecutionContext> contextCaptor = ArgumentCaptor.forClass(ReleaseExecutionContext.class);
        verify(executor, times(1)).execute(contextCaptor.capture());
        ReleaseExecutionContext context = contextCaptor.getValue();
        assertEquals(100L, context.getHostId());
        assertEquals(1L, context.getInstanceId());
        assertSame(typed, context.getExecutorConfig());
        assertInstanceOf(DockerExecutionConfig.class, context.getExecutorConfig());
    }

    @Test
    @DisplayName("D1：批次路径 parse 恰好调用一次，类型化配置进入上下文")
    void dockerBatchPathParsesOnceAndPassesTypedConfig() {
        ReleaseExecutorConfig config = dockerConfig(55L, 1L);
        DockerExecutionConfig typed = DockerExecutionConfig.builder().image("nginx:1.25").build();
        ReleaseExecutor executor = mock(ReleaseExecutor.class);
        when(releaseExecutorConfigService.findEnabledById(55L)).thenReturn(config);
        when(releaseExecutorConfigParser.parse(config))
                .thenReturn(ReleaseExecutorConfigParser.ParseResult.success(typed));
        when(releaseExecutorRegistry.getExecutor("DOCKER")).thenReturn(executor);
        when(executor.execute(any())).thenReturn(ReleaseExecutionResult.success("批次部署成功"));
        when(assetHostService.getById(100L)).thenReturn(mock(AssetHost.class));

        ReleaseDeployExecutionResult result = service.executeReleaseDeployForBatch(
                order(55L), serviceEntity(), envEntity(), versionEntity(),
                mock(ReleaseArtifact.class), List.of(instance(1L, 100L)), Map.of(), 2);

        assertTrue(result.success());
        verify(releaseExecutorConfigParser, times(1)).parse(config);

        ArgumentCaptor<ReleaseExecutionContext> contextCaptor = ArgumentCaptor.forClass(ReleaseExecutionContext.class);
        verify(executor, times(1)).execute(contextCaptor.capture());
        assertSame(typed, contextCaptor.getValue().getExecutorConfig());

        ArgumentCaptor<RuntimeDeployRecord> saved = ArgumentCaptor.forClass(RuntimeDeployRecord.class);
        verify(runtimeDeployRecordService, times(1)).startRunning(saved.capture());
        assertEquals(2, saved.getValue().getDeployBatch());
        assertEquals(10L, saved.getValue().getReleaseOrderId());
    }

    @Test
    @DisplayName("D1：解析失败 → BizException，不创建部署记录、不查询执行器")
    void parseFailureThrowsBeforeAnyRecord() {
        ReleaseExecutorConfig config = dockerConfig(55L, 1L);
        when(releaseExecutorConfigService.findEnabledById(55L)).thenReturn(config);
        when(releaseExecutorConfigParser.parse(config))
                .thenReturn(ReleaseExecutorConfigParser.ParseResult.failure("Docker 执行器配置 JSON 解析失败"));

        BizException e = assertThrows(BizException.class, () -> service.executeReleaseDeploy(
                order(55L), serviceEntity(), envEntity(), versionEntity(),
                mock(ReleaseArtifact.class), List.of(instance(1L, 100L)), Map.of()));

        assertEquals(ErrorCode.VALIDATION_ERROR, e.getErrorCode());
        assertEquals("Docker 执行器配置 JSON 解析失败", e.getMessage());
        verify(runtimeDeployRecordService, never()).startRunning(any());
        verify(releaseExecutorRegistry, never()).getExecutor(any());
    }

    @Test
    @DisplayName("D1：批次路径解析失败同样不创建任何部署记录")
    void parseFailureThrowsBeforeAnyRecordInBatch() {
        ReleaseExecutorConfig config = dockerConfig(55L, 1L);
        when(releaseExecutorConfigService.findEnabledById(55L)).thenReturn(config);
        when(releaseExecutorConfigParser.parse(config))
                .thenReturn(ReleaseExecutorConfigParser.ParseResult.failure("Docker 执行器配置 payload 为空"));

        BizException e = assertThrows(BizException.class, () -> service.executeReleaseDeployForBatch(
                order(55L), serviceEntity(), envEntity(), versionEntity(),
                mock(ReleaseArtifact.class), List.of(instance(1L, 100L)), Map.of(), 1));

        assertEquals(ErrorCode.VALIDATION_ERROR, e.getErrorCode());
        verify(runtimeDeployRecordService, never()).startRunning(any());
        verify(releaseExecutorRegistry, never()).getExecutor(any());
    }

    @Test
    @DisplayName("执行器配置环境不一致 → 环境一致性守卫先于解析与记录创建")
    void envMismatchGuardFiresBeforeParse() {
        ReleaseExecutorConfig config = dockerConfig(55L, 99L);
        when(releaseExecutorConfigService.findEnabledById(55L)).thenReturn(config);

        BizException e = assertThrows(BizException.class, () -> service.executeReleaseDeploy(
                order(55L), serviceEntity(), envEntity(), versionEntity(),
                mock(ReleaseArtifact.class), List.of(instance(1L, 100L)), Map.of()));

        assertEquals("执行器配置不属于发布目标环境", e.getMessage());
        assertEquals(ErrorCode.VALIDATION_ERROR, e.getErrorCode());
        verify(releaseExecutorConfigParser, never()).parse(any());
        verify(runtimeDeployRecordService, never()).startRunning(any());
        verify(releaseExecutorRegistry, never()).getExecutor(any());
    }

    // ---------- D3：失败结果落到部署记录的兜底 ----------

    @Test
    @DisplayName("D3：failure(message) → deployStatus=FAILED、deployLog=message、endedAt 非空")
    void failureWithMessageWritesLogAndTime() {
        ReleaseExecutorConfig config = dockerConfig(55L, 1L);
        ReleaseExecutor executor = mock(ReleaseExecutor.class);
        when(releaseExecutorConfigService.findEnabledById(55L)).thenReturn(config);
        when(releaseExecutorConfigParser.parse(config))
                .thenReturn(ReleaseExecutorConfigParser.ParseResult.success(
                        DockerExecutionConfig.builder().image("nginx:1.25").build()));
        when(releaseExecutorRegistry.getExecutor("DOCKER")).thenReturn(executor);
        when(executor.execute(any())).thenReturn(ReleaseExecutionResult.failure("镜像拉取失败"));
        when(assetHostService.getById(100L)).thenReturn(mock(AssetHost.class));

        ReleaseDeployExecutionResult result = service.executeReleaseDeploy(
                order(55L), serviceEntity(), envEntity(), versionEntity(),
                mock(ReleaseArtifact.class), List.of(instance(1L, 100L)), Map.of());

        assertFalse(result.success());

        ArgumentCaptor<RuntimeDeployRecord> updated = ArgumentCaptor.forClass(RuntimeDeployRecord.class);
        verify(runtimeDeployRecordService, times(1)).finish(updated.capture());
        RuntimeDeployRecord record = updated.getValue();
        assertEquals("FAILED", record.getDeployStatus());
        assertEquals("镜像拉取失败", record.getDeployLog());
        assertNotNull(record.getStartedAt());
        assertNotNull(record.getEndedAt());
        assertEquals(10L, record.getReleaseOrderId());
    }

    @Test
    @DisplayName("D3：结果无时间无日志 → endedAt 兜底为当前时间、deployLog 兜底固定文案、startedAt 保留 RUNNING 值")
    void bareFailureFallsBackTimeAndLog() {
        ReleaseExecutorConfig config = dockerConfig(55L, 1L);
        ReleaseExecutor executor = mock(ReleaseExecutor.class);
        when(releaseExecutorConfigService.findEnabledById(55L)).thenReturn(config);
        when(releaseExecutorConfigParser.parse(config))
                .thenReturn(ReleaseExecutorConfigParser.ParseResult.success(null));
        when(releaseExecutorRegistry.getExecutor("DOCKER")).thenReturn(executor);
        when(executor.execute(any()))
                .thenReturn(ReleaseExecutionResult.builder().success(false).outcome(com.infrahub.release.support.DeployExecutionOutcome.FAILED).build());
        when(assetHostService.getById(100L)).thenReturn(mock(AssetHost.class));

        service.executeReleaseDeploy(
                order(55L), serviceEntity(), envEntity(), versionEntity(),
                mock(ReleaseArtifact.class), List.of(instance(1L, 100L)), Map.of());

        ArgumentCaptor<RuntimeDeployRecord> saved = ArgumentCaptor.forClass(RuntimeDeployRecord.class);
        verify(runtimeDeployRecordService, times(1)).startRunning(saved.capture());
        OffsetDateTime runningStartedAt = saved.getValue().getStartedAt();

        ArgumentCaptor<RuntimeDeployRecord> updated = ArgumentCaptor.forClass(RuntimeDeployRecord.class);
        verify(runtimeDeployRecordService, times(1)).finish(updated.capture());
        RuntimeDeployRecord record = updated.getValue();
        assertEquals("FAILED", record.getDeployStatus());
        assertEquals("执行完成", record.getDeployLog());
        assertNotNull(record.getEndedAt());
        assertSame(runningStartedAt, record.getStartedAt());
    }

    @Test
    @DisplayName("D3：failure 带 stdout/stderr → deployLog 为 fullLog 聚合")
    void failureWithOutputWritesFullLog() {
        ReleaseExecutorConfig config = dockerConfig(55L, 1L);
        ReleaseExecutor executor = mock(ReleaseExecutor.class);
        when(releaseExecutorConfigService.findEnabledById(55L)).thenReturn(config);
        when(releaseExecutorConfigParser.parse(config))
                .thenReturn(ReleaseExecutorConfigParser.ParseResult.success(
                        DockerExecutionConfig.builder().image("nginx:1.25").build()));
        when(releaseExecutorRegistry.getExecutor("DOCKER")).thenReturn(executor);
        when(executor.execute(any())).thenReturn(
                ReleaseExecutionResult.failure("容器启动失败", "stdout-line", "stderr-line"));
        when(assetHostService.getById(100L)).thenReturn(mock(AssetHost.class));

        service.executeReleaseDeploy(
                order(55L), serviceEntity(), envEntity(), versionEntity(),
                mock(ReleaseArtifact.class), List.of(instance(1L, 100L)), Map.of());

        ArgumentCaptor<RuntimeDeployRecord> updated = ArgumentCaptor.forClass(RuntimeDeployRecord.class);
        verify(runtimeDeployRecordService, times(1)).finish(updated.capture());
        assertEquals("STDOUT:\nstdout-line\nSTDERR:\nstderr-line", updated.getValue().getDeployLog());
        assertEquals("FAILED", updated.getValue().getDeployStatus());
    }

    @Test
    @DisplayName("D3：成功结果无日志无消息 → deployLog 兜底『执行完成』")
    void bareSuccessFallsBackLog() {
        ReleaseExecutorConfig config = dockerConfig(55L, 1L);
        ReleaseExecutor executor = mock(ReleaseExecutor.class);
        when(releaseExecutorConfigService.findEnabledById(55L)).thenReturn(config);
        when(releaseExecutorConfigParser.parse(config))
                .thenReturn(ReleaseExecutorConfigParser.ParseResult.success(
                        DockerExecutionConfig.builder().image("nginx:1.25").build()));
        when(releaseExecutorRegistry.getExecutor("DOCKER")).thenReturn(executor);
        when(executor.execute(any()))
                .thenReturn(ReleaseExecutionResult.builder().success(true).build());
        when(assetHostService.getById(100L)).thenReturn(mock(AssetHost.class));

        ReleaseDeployExecutionResult result = service.executeReleaseDeploy(
                order(55L), serviceEntity(), envEntity(), versionEntity(),
                mock(ReleaseArtifact.class), List.of(instance(1L, 100L)), Map.of());

        assertTrue(result.success());
        ArgumentCaptor<RuntimeDeployRecord> updated = ArgumentCaptor.forClass(RuntimeDeployRecord.class);
        verify(runtimeDeployRecordService, times(1)).finish(updated.capture());
        assertEquals("SUCCESS", updated.getValue().getDeployStatus());
        assertEquals("执行完成", updated.getValue().getDeployLog());
    }

    @Test
    @DisplayName("D3：批次路径失败同样获得时间与日志兜底，deployBatch 保留")
    void batchFailureFallsBackTimeAndLog() {
        ReleaseExecutorConfig config = dockerConfig(55L, 1L);
        ReleaseExecutor executor = mock(ReleaseExecutor.class);
        when(releaseExecutorConfigService.findEnabledById(55L)).thenReturn(config);
        when(releaseExecutorConfigParser.parse(config))
                .thenReturn(ReleaseExecutorConfigParser.ParseResult.success(null));
        when(releaseExecutorRegistry.getExecutor("DOCKER")).thenReturn(executor);
        when(executor.execute(any()))
                .thenReturn(ReleaseExecutionResult.builder().success(false).outcome(com.infrahub.release.support.DeployExecutionOutcome.FAILED).build());
        when(assetHostService.getById(100L)).thenReturn(mock(AssetHost.class));

        ReleaseDeployExecutionResult result = service.executeReleaseDeployForBatch(
                order(55L), serviceEntity(), envEntity(), versionEntity(),
                mock(ReleaseArtifact.class), List.of(instance(1L, 100L)), Map.of(), 3);

        assertFalse(result.success());
        ArgumentCaptor<RuntimeDeployRecord> updated = ArgumentCaptor.forClass(RuntimeDeployRecord.class);
        verify(runtimeDeployRecordService, times(1)).finish(updated.capture());
        RuntimeDeployRecord record = updated.getValue();
        assertEquals("FAILED", record.getDeployStatus());
        assertEquals("执行完成", record.getDeployLog());
        assertNotNull(record.getEndedAt());
        assertEquals(3, record.getDeployBatch());
    }

    // ---------- 默认 SSH 路径不回归 ----------

    @Test
    @DisplayName("SSH 默认路径不回归：不走 parser，部署记录正常落库")
    void sshDefaultPathNeverTouchesParser() {
        ReleaseOrder order = order(null);
        AssetHost host = mock(AssetHost.class);
        AssetHostSshConfig sshConfig = mock(AssetHostSshConfig.class);
        when(assetHostService.getById(100L)).thenReturn(host);
        when(assetHostSshConfigService.getEnabledByHostId(100L)).thenReturn(sshConfig);
        when(releasePrecheckService.check(any(), any(), any(), any())).thenReturn(
                new ReleasePrecheckResult(true, "磁盘与端口检查通过", null, null,
                        SshExecutionResult.success("precheck-ok", null,
                                OffsetDateTime.now(), OffsetDateTime.now())));
        when(sshExecutorService.execute(any())).thenReturn(
                SshExecutionResult.success("deploy-stdout", null, OffsetDateTime.now(), OffsetDateTime.now()));

        ReleaseDeployExecutionResult result = service.executeReleaseDeploy(
                order, serviceEntity(), envEntity(), versionEntity(),
                mock(ReleaseArtifact.class), List.of(instance(1L, 100L)),
                Map.of("deploy.command", "echo ${serviceCode} ${versionNo} ${deployRoot}"));

        assertTrue(result.success());
        assertEquals(List.of(1L), result.successInstanceIds());
        verify(releaseExecutorConfigParser, never()).parse(any());
        verify(runtimeDeployRecordService, times(1)).startRunning(any());
        verify(runtimeDeployRecordService, times(1)).finish(any());
        verify(sshExecutorService, times(1)).execute(any(SshExecutionRequest.class));
    }

    @Test
    @DisplayName("SSH 默认路径缺少 deploy.command → BizException")
    void sshPathMissingCommandThrows() {
        ReleaseOrder order = order(null);
        BizException e = assertThrows(BizException.class, () -> service.executeReleaseDeploy(
                order, serviceEntity(), envEntity(), versionEntity(),
                mock(ReleaseArtifact.class), List.of(instance(1L, 100L)), Map.of()));
        assertEquals(ErrorCode.VALIDATION_ERROR, e.getErrorCode());
        assertEquals("缺少 deploy.command 部署命令配置", e.getMessage());
    }
}
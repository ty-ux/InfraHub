package com.infrahub.release.service;

import com.infrahub.common.error.ErrorCode;
import com.infrahub.common.exception.BizException;
import com.infrahub.infra.ssh.SshExecutionRequest;
import com.infrahub.infra.ssh.SshExecutionResult;
import com.infrahub.infra.ssh.SshExecutorService;
import com.infrahub.release.config.HostInstanceCollectionProperties;
import com.infrahub.release.dto.CollectionObservation;
import com.infrahub.release.dto.CollectionTargetRef;
import com.infrahub.release.dto.RuntimeServiceInstanceStatusResponse;
import com.infrahub.release.entity.AssetHost;
import com.infrahub.release.entity.AssetHostSshConfig;
import com.infrahub.release.support.CollectionAdmissionChangedException;
import com.infrahub.release.support.CollectionErrorCategory;
import com.infrahub.release.support.CollectionResult;
import com.infrahub.release.support.CollectionTargetType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SSH 只读观测器的单元测试。
 *
 * <p>这里钉住的是本功能**最重要的安全性质**：探测命令没有产出结果标记 = 本次探测失败，
 * 绝不能据此判"主机离线 / 进程不在"。既有部署诊断在 SSH 失败时同样会让
 * {@code running=false}、{@code healthStatus=DOWN}，直接采信就会把连不上的主机报成 STOPPED。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SshProcessCollectionObserverTest {

    private static final OffsetDateTime T0 = OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    private static final Clock FIXED_CLOCK = Clock.fixed(T0.toInstant(), ZoneOffset.UTC);
    private static final String RUNNING_MARKER = "__INFRAHUB_RUNNING__=";
    private static final String REACHABLE_MARKER = "__INFRAHUB_HOST_REACHABLE__=";
    private static final String PROCESS = "PROCESS";
    private static final long TIMEOUT_MS = 3_000L;
    private static final int DEFAULT_PORT = 22;
    private static final long SERVICE_ID = 9L;
    private static final long ENV_ID = 3L;

    @Mock
    private AssetHostService assetHostService;
    @Mock
    private AssetHostSshConfigService assetHostSshConfigService;
    @Mock
    private SecretValueResolver secretValueResolver;
    @Mock
    private SshExecutorService sshExecutorService;
    @Mock
    private RuntimeServiceDiagnosticsService runtimeServiceDiagnosticsService;

    private SshProcessCollectionObserver observer;

    @BeforeEach
    void setUp() {
        HostInstanceCollectionProperties properties = new HostInstanceCollectionProperties();
        properties.setSingleTargetTimeoutMs(TIMEOUT_MS);
        // 拨号请求的构造者是**真的**：本测试要钉住的正是"请求里的端点到底是什么"，
        // 把它换成 mock 就等于把被测逻辑换掉了。
        CollectionDialResolver dialResolver = new CollectionDialResolver(
                assetHostService, assetHostSshConfigService, secretValueResolver);
        observer = new SshProcessCollectionObserver(dialResolver,
                sshExecutorService, runtimeServiceDiagnosticsService, properties, FIXED_CLOCK);
    }

    // ---------- 支持判定：失败关闭 ----------

    @Test
    @DisplayName("主机目标一律支持")
    void hostTargetIsAlwaysSupported() {
        assertThat(observer.supports(hostRef(1L, 5L))).isTrue();
        assertThat(observer.supports(new CollectionTargetRef(
                CollectionTargetType.HOST, 1L, 1L, null, null, null, null, null, null, null))).isTrue();
    }

    @Test
    @DisplayName("进程型实例支持，大小写不敏感")
    void processLikeInstancesAreSupported() {
        assertThat(observer.supports(instanceRef(200L, 1L, "SPRING_BOOT"))).isTrue();
        assertThat(observer.supports(instanceRef(200L, 1L, PROCESS))).isTrue();
        assertThat(observer.supports(instanceRef(200L, 1L, "process"))).isTrue();
    }

    @Test
    @DisplayName("Docker / K8s / 未知 / 空类型一律不支持，绝不用 PID 文件方式去猜")
    void nonProcessInstancesAreUnsupported() {
        assertThat(observer.supports(instanceRef(200L, 1L, "DOCKER"))).isFalse();
        assertThat(observer.supports(instanceRef(200L, 1L, "KUBERNETES"))).isFalse();
        assertThat(observer.supports(instanceRef(200L, 1L, "JAVA_AGENT"))).isFalse();
        assertThat(observer.supports(instanceRef(200L, 1L, null))).isFalse();
        assertThat(observer.supports(instanceRef(200L, 1L, ""))).isFalse();
    }

    @Test
    @DisplayName("未知目标类型不支持")
    void unknownTargetTypeIsUnsupported() {
        assertThat(observer.supports(new CollectionTargetRef(
                "SOMETHING_ELSE", 1L, null, null, null, null, null, null, null, null))).isFalse();
    }

    // ---------- 主机探测 ----------

    @Test
    @DisplayName("主机探测拿到可达标记：结论 ONLINE，不写健康维度")
    void hostProbeWithMarkerIsOnline() {
        giveHost(1L);
        giveSshConfig(5L, 1L);
        when(sshExecutorService.execute(any())).thenReturn(
                SshExecutionResult.success(REACHABLE_MARKER + "true\n", "", T0, T0));

        CollectionObservation observation = observer.observe(hostRef(1L, 5L));

        assertThat(observation.result()).isEqualTo(CollectionResult.SUCCESS);
        assertThat(observation.observedStatus()).isEqualTo("ONLINE");
        assertThat(observation.observedHealth()).as("主机没有健康维度").isNull();
        assertThat(observation.errorCategory()).isNull();
        assertThat(observation.configPresent()).isTrue();
        assertThat(observation.observedAt()).as("采样时刻取自采集专用时钟").isEqualTo(T0);
    }

    @Test
    @DisplayName("主机探测没有拿到标记：判失败，绝不判 OFFLINE")
    void hostProbeWithoutMarkerIsFailureNotOffline() {
        giveHost(1L);
        giveSshConfig(5L, 1L);
        when(sshExecutorService.execute(any())).thenReturn(
                SshExecutionResult.failure(255, "", "Permission denied (publickey).", T0, T0));

        CollectionObservation observation = observer.observe(hostRef(1L, 5L));

        assertThat(observation.result()).isEqualTo(CollectionResult.FAILED);
        assertThat(observation.observedStatus())
                .as("命令没跑成不等于主机离线，这不是一次有效观测").isNull();
        assertThat(observation.errorSummary()).contains("未返回结果标记").contains("Permission denied");
    }

    @Test
    @DisplayName("主机探测 stderr 报超时：归类为 NETWORK_TIMEOUT")
    void hostProbeTimeoutStderrIsClassified() {
        giveHost(1L);
        giveSshConfig(5L, 1L);
        when(sshExecutorService.execute(any())).thenReturn(
                SshExecutionResult.failure(255, "", "connect timed out after 3000ms", T0, T0));

        CollectionObservation observation = observer.observe(hostRef(1L, 5L));

        assertThat(observation.result()).isEqualTo(CollectionResult.FAILED);
        assertThat(observation.errorCategory()).isEqualTo(CollectionErrorCategory.NETWORK_TIMEOUT);
    }

    @Test
    @DisplayName("探测请求按主机的 IP、端口与配置里的凭据引用构造，命令是只读回声")
    void hostProbeRequestCarriesConfiguredTarget() {
        AssetHost host = giveHost(1L);
        host.setIp("192.0.2.21");
        host.setHostname("host-1");
        AssetHostSshConfig config = giveSshConfig(5L, 1L);
        config.setSshPort(2222);
        config.setUsername("deploy");
        config.setAuthType("KEY");
        config.setPrivateKeySecretRefId(77L);
        when(secretValueResolver.resolve(77L)).thenReturn("/etc/infrahub/id_rsa");
        when(sshExecutorService.execute(any())).thenReturn(
                SshExecutionResult.success(REACHABLE_MARKER + "true\n", "", T0, T0));

        observer.observe(hostRef(1L, host.getIp(), 5L, 2222));

        ArgumentCaptor<SshExecutionRequest> captor = ArgumentCaptor.forClass(SshExecutionRequest.class);
        verify(sshExecutorService).execute(captor.capture());
        SshExecutionRequest request = captor.getValue();
        assertThat(request.getHost()).as("有 IP 就用 IP").isEqualTo("192.0.2.21");
        assertThat(request.getPort()).isEqualTo(2222);
        assertThat(request.getUsername()).isEqualTo("deploy");
        assertThat(request.getAuthType()).isEqualTo("KEY");
        assertThat(request.getPrivateKeyPath()).isEqualTo("/etc/infrahub/id_rsa");
        assertThat(request.getTimeoutMillis())
                .as("采集预算必须下推到底层：调用方不再等之后，得由执行器自己到点收摊")
                .isEqualTo(TIMEOUT_MS);
        assertThat(request.getCommand())
                .as("主机探测只做一次回声，不读也不写远端任何东西")
                .contains(REACHABLE_MARKER)
                .contains("exit 0");
    }

    @Test
    @DisplayName("采样时主机没有 IP、落到主机名上：拨号就按快照里那个主机名拨，不拿空地址出去")
    void hostSampledByHostnameIsDialedByThatHostname() {
        AssetHost host = giveHost(1L);
        host.setIp("   ");
        host.setHostname("host-1.internal");
        giveSshConfig(5L, 1L);
        when(sshExecutorService.execute(any())).thenReturn(
                SshExecutionResult.success(REACHABLE_MARKER + "true\n", "", T0, T0));

        // 枚举侧（CollectionAddress）在采样时已经把"无 IP 退回主机名"做完，快照里存的就是主机名
        observer.observe(hostRef(1L, "host-1.internal", 5L, DEFAULT_PORT));

        ArgumentCaptor<SshExecutionRequest> captor = ArgumentCaptor.forClass(SshExecutionRequest.class);
        verify(sshExecutorService).execute(captor.capture());
        assertThat(captor.getValue().getHost())
                .as("拨号端点逐字取自快照：采样说是主机名，拨号就是主机名")
                .isEqualTo("host-1.internal");
    }

    @Test
    @DisplayName("主机在采样后被删除：取消本轮（跳过），既不发起 SSH 也不判失败")
    void hostDeletedAfterSamplingCancelsInsteadOfFailing() {
        when(assetHostService.getById(1L)).thenReturn(null);

        CollectionObservation observation = observer.observe(hostRef(1L, 5L));

        assertThat(observation.result())
                .as("目标被删不是'目标故障'、也不是'探测失败'：本轮根本没有拨号")
                .isEqualTo(CollectionResult.SKIPPED_ADMISSION_CHANGED);
        assertThat(observation.result().isFailure()).isFalse();
        assertThat(observation.errorCategory()).isEqualTo(CollectionErrorCategory.ADMISSION_CHANGED);
        assertThat(observation.errorCategory().isFailureCategory())
                .as("必须在失败白名单之外，否则会顺着失败阈值把目标状态复位")
                .isFalse();
        assertThat(observation.errorSummary()).contains("已被删除");
        assertThat(observation.observedStatus()).as("没有拨号就没有观测结论").isNull();
        verify(sshExecutorService, never()).execute(any());
    }

    @Test
    @DisplayName("采样时那条连接配置此后被删除或停用：取消本轮（跳过），不发起 SSH")
    void configRemovedAfterSamplingCancelsInsteadOfFailing() {
        giveHost(1L);
        when(assetHostSshConfigService.getById(5L)).thenReturn(null);

        CollectionObservation observation = observer.observe(hostRef(1L, 5L));

        assertThat(observation.result()).isEqualTo(CollectionResult.SKIPPED_ADMISSION_CHANGED);
        assertThat(observation.errorCategory()).isEqualTo(CollectionErrorCategory.ADMISSION_CHANGED);
        assertThat(observation.configPresent())
                .as("采样时配置确实在，不能把'采样后被改掉'说成'从来没配好'")
                .isTrue();
        verify(sshExecutorService, never()).execute(any());
    }

    @Test
    @DisplayName("目标压根没有连接配置：不探测就直接判未配置")
    void targetWithoutConfigReferenceIsUnconfigured() {
        CollectionObservation observation = observer.observe(
                new CollectionTargetRef(CollectionTargetType.HOST, 1L, 1L, null, null, null, null, null, null, null));

        assertThat(observation.result()).isEqualTo(CollectionResult.SKIPPED_UNCONFIGURED);
        assertThat(observation.configPresent()).isFalse();
        verify(sshExecutorService, never()).execute(any());
        verify(assetHostService, never()).getById(any());
    }

    // ---------- 实例探测 ----------

    @Test
    @DisplayName("实例探测没有运行标记：判失败，绝不判 STOPPED")
    void instanceProbeWithoutMarkerIsFailureNotStopped() {
        RuntimeServiceInstanceStatusResponse response = new RuntimeServiceInstanceStatusResponse();
        response.setRunning(false);
        response.setHealthStatus("DOWN");
        response.setStderr("ssh: connect to host 192.0.2.21 port 22: Connection refused");
        when(runtimeServiceDiagnosticsService.getStatusForCollection(any(), eq(TIMEOUT_MS))).thenReturn(response);

        CollectionObservation observation = observer.observe(instanceRef(200L, 1L, PROCESS));

        assertThat(observation.result()).isEqualTo(CollectionResult.FAILED);
        assertThat(observation.observedStatus())
                .as("诊断说 running=false 是它连不上时的默认值，不能当成'进程不在'").isNull();
        assertThat(observation.observedHealth()).as("失败的观测不留健康值").isNull();
        assertThat(observation.errorCategory()).isEqualTo(CollectionErrorCategory.PROBE_ERROR);
    }

    @Test
    @DisplayName("实例诊断走采集专用入口：快照与逐目标超时一起压到底层，不走只认 id 的手工接口")
    void instanceProbePassesSnapshotAndTimeoutToDiagnostics() {
        when(runtimeServiceDiagnosticsService.getStatusForCollection(any(), eq(TIMEOUT_MS)))
                .thenReturn(instanceStatus(RUNNING_MARKER + "true\n", true, "UP"));

        observer.observe(instanceRef(200L, 1L, PROCESS));

        verify(runtimeServiceDiagnosticsService)
                .getStatusForCollection(instanceRef(200L, 1L, PROCESS), TIMEOUT_MS);
        verify(runtimeServiceDiagnosticsService, never()).getStatus(any(), any());
    }

    @Test
    @DisplayName("实例探测拿到运行标记且进程确实不在：成功观测，结论 STOPPED 并记 PROCESS_ABSENT")
    void absentProcessIsSuccessStopped() {
        when(runtimeServiceDiagnosticsService.getStatusForCollection(any(), eq(TIMEOUT_MS)))
                .thenReturn(instanceStatus(RUNNING_MARKER + "false\n", false, "UNKNOWN"));

        CollectionObservation observation = observer.observe(instanceRef(200L, 1L, PROCESS));

        assertThat(observation.result()).isEqualTo(CollectionResult.SUCCESS);
        assertThat(observation.observedStatus()).isEqualTo("STOPPED");
        assertThat(observation.observedHealth()).isEqualTo("UNKNOWN");
        assertThat(observation.errorCategory()).isEqualTo(CollectionErrorCategory.PROCESS_ABSENT);
    }

    @Test
    @DisplayName("进程在跑但健康检查失败：观测本身成功，同时标记健康端点异常")
    void runningProcessWithFailingHealthIsSuccessWithHealthError() {
        when(runtimeServiceDiagnosticsService.getStatusForCollection(any(), eq(TIMEOUT_MS)))
                .thenReturn(instanceStatus(RUNNING_MARKER + "true\n", true, "DOWN"));

        CollectionObservation observation = observer.observe(instanceRef(200L, 1L, PROCESS));

        assertThat(observation.result())
                .as("健康接口报错不影响'进程在跑'这个事实").isEqualTo(CollectionResult.SUCCESS);
        assertThat(observation.observedStatus()).isEqualTo("RUNNING");
        assertThat(observation.observedHealth()).isEqualTo("DOWN");
        assertThat(observation.errorCategory()).isEqualTo(CollectionErrorCategory.HEALTH_ENDPOINT_ERROR);
        assertThat(observation.errorSummary())
                .as("只记判定原因，不把健康接口的响应体落库").isNull();
    }

    @Test
    @DisplayName("进程在跑且健康正常：成功观测且不带失败类别")
    void runningProcessWithUpHealthHasNoError() {
        when(runtimeServiceDiagnosticsService.getStatusForCollection(any(), eq(TIMEOUT_MS)))
                .thenReturn(instanceStatus(RUNNING_MARKER + "true\n", true, "UP"));

        CollectionObservation observation = observer.observe(instanceRef(200L, 1L, PROCESS));

        assertThat(observation.result()).isEqualTo(CollectionResult.SUCCESS);
        assertThat(observation.observedStatus()).isEqualTo("RUNNING");
        assertThat(observation.observedHealth()).isEqualTo("UP");
        assertThat(observation.errorCategory()).isNull();
    }

    @Test
    @DisplayName("进程在跑但没给出健康结论：健康维度记 UNKNOWN，不猜")
    void runningProcessWithBlankHealthIsUnknown() {
        when(runtimeServiceDiagnosticsService.getStatusForCollection(any(), eq(TIMEOUT_MS)))
                .thenReturn(instanceStatus(RUNNING_MARKER + "true\n", true, "   "));

        CollectionObservation observation = observer.observe(instanceRef(200L, 1L, PROCESS));

        assertThat(observation.observedHealth()).isEqualTo("UNKNOWN");
        assertThat(observation.errorCategory()).isEqualTo(CollectionErrorCategory.HEALTH_ENDPOINT_ERROR);
    }

    // ---------- 异常归类：绝不上抛 ----------

    @Test
    @DisplayName("底层抛 BizException：归类后返回失败观测，不把它抛给编排层")
    void bizExceptionIsClassifiedNotThrown() {
        giveHost(1L);
        giveSshConfig(5L, 1L);
        when(sshExecutorService.execute(any()))
                .thenThrow(new BizException(ErrorCode.INTERNAL_ERROR, "Auth fail for user deploy"));

        CollectionObservation observation = observer.observe(hostRef(1L, 5L));

        assertThat(observation.result()).isEqualTo(CollectionResult.FAILED);
        assertThat(observation.errorCategory()).isEqualTo(CollectionErrorCategory.AUTH_FAILED);
        assertThat(observation.errorSummary()).isEqualTo("Auth fail for user deploy");
    }

    @Test
    @DisplayName("底层抛非受检异常：归为 PROBE_ERROR，不外抛")
    void runtimeExceptionBecomesProbeError() {
        giveHost(1L);
        giveSshConfig(5L, 1L);
        when(sshExecutorService.execute(any())).thenThrow(new IllegalStateException("channel closed"));

        CollectionObservation observation = observer.observe(hostRef(1L, 5L));

        assertThat(observation.result()).isEqualTo(CollectionResult.FAILED);
        assertThat(observation.errorCategory()).isEqualTo(CollectionErrorCategory.PROBE_ERROR);
        assertThat(observation.errorSummary()).isEqualTo("channel closed");
    }

    @Test
    @DisplayName("实例诊断抛'未配置部署诊断参数'：判未配置跳过，绝不产生 FAILED、不推进失败计数")
    void diagnosticsConfigMissingIsSkipNotFailure() {
        when(runtimeServiceDiagnosticsService.getStatusForCollection(any(), eq(TIMEOUT_MS)))
                .thenThrow(new BizException(ErrorCode.VALIDATION_ERROR,
                        "服务(srm-backend)未配置部署诊断参数，请在环境配置中添加 deploy.root、deploy.port 等参数"));

        CollectionObservation observation = observer.observe(instanceRef(200L, 1L, PROCESS));

        assertThat(observation.result())
                .as("诊断参数没配齐属于'没配'，不是'探测失败'：判失败会顺着失败阈值把目标状态/健康复位")
                .isEqualTo(CollectionResult.SKIPPED_UNCONFIGURED);
        assertThat(observation.result().isFailure()).isFalse();
        assertThat(observation.result().isSkip()).isTrue();
        assertThat(observation.errorCategory()).isEqualTo(CollectionErrorCategory.DIAGNOSTIC_CONFIG_MISSING);
        assertThat(observation.errorCategory().isFailureCategory())
                .as("必须落在失败白名单之外，写回层据此决定不推进连续失败计数")
                .isFalse();
        assertThat(observation.configPresent())
                .as("SSH 连接配置确实在，不能因为另一个东西没配就把'SSH 确实存在'说成'没有连接配置'")
                .isTrue();
        assertThat(observation.errorSummary())
                .as("保留可定位的未配置原因")
                .contains("未配置部署诊断参数");
        assertThat(observation.observedStatus()).as("没有真实探测就没有观测结论").isNull();
        assertThat(observation.observedHealth()).isNull();
        verify(sshExecutorService, never()).execute(any());
    }

    @Test
    @DisplayName("实例诊断报出'准入已变更'：观测器翻成取消，既不判失败也不写观测结论")
    void instanceAdmissionChangedCancelsInsteadOfFailing() {
        when(runtimeServiceDiagnosticsService.getStatusForCollection(any(), eq(TIMEOUT_MS)))
                .thenThrow(new CollectionAdmissionChangedException(
                        "采样后运行实例的所属服务已变更(id=200)，本轮取消"));

        CollectionObservation observation = observer.observe(instanceRef(200L, 1L, PROCESS));

        assertThat(observation.result())
                .as("实例这条路径同样要在拨号前取消，不能把'采样后被改掉'说成'探测失败'")
                .isEqualTo(CollectionResult.SKIPPED_ADMISSION_CHANGED);
        assertThat(observation.result().isSkip()).isTrue();
        assertThat(observation.result().isFailure()).isFalse();
        assertThat(observation.errorCategory()).isEqualTo(CollectionErrorCategory.ADMISSION_CHANGED);
        assertThat(observation.errorCategory().isFailureCategory()).isFalse();
        assertThat(observation.errorSummary()).contains("所属服务已变更");
        assertThat(observation.observedStatus()).as("没有拨号就没有观测结论").isNull();
        assertThat(observation.observedHealth()).isNull();
        verify(sshExecutorService, never()).execute(any());
    }

    // ---------- 摘要卫生 ----------

    @Test
    @DisplayName("异常摘要只留首行并截到 200 字符，多行内容不会整段落库")
    void summaryKeepsFirstLineOnlyAndCapsLength() {
        String firstLine = "A".repeat(300);
        String message = firstLine + "\nBEGIN RSA PRIVATE KEY-----\nSECOND_LINE_SECRET";

        String summary = SshProcessCollectionObserver.summarize(new IllegalStateException(message));

        assertThat(summary).hasSize(200);
        assertThat(summary).doesNotContain("SECOND_LINE_SECRET");
        assertThat(summary).doesNotContain("PRIVATE KEY");
    }

    @Test
    @DisplayName("异常没有消息时退化成异常类名，不写出 null")
    void summaryWithoutMessageFallsBackToClassName() {
        assertThat(SshProcessCollectionObserver.summarize(new IllegalStateException()))
                .isEqualTo("IllegalStateException");
    }

    @Test
    @DisplayName("远端 stderr 也只取首行并截断，不整段写入")
    void stderrSummaryIsCappedAndSingleLine() {
        giveHost(1L);
        giveSshConfig(5L, 1L);
        String stderr = "connection reset by peer\n" + "B".repeat(500);
        when(sshExecutorService.execute(any()))
                .thenReturn(SshExecutionResult.failure(255, "", stderr, T0, T0));

        CollectionObservation observation = observer.observe(hostRef(1L, 5L));

        assertThat(observation.errorSummary()).contains("connection reset by peer");
        assertThat(observation.errorSummary()).doesNotContain("BBBB");
        assertThat(observation.errorSummary().length()).isLessThanOrEqualTo(200);
    }

    @Test
    @DisplayName("失败文案归类：错误类型 -> 采集失败类别")
    void classifyMapsMessagesToCategories() {
        assertThat(SshProcessCollectionObserver.classify(
                new BizException(ErrorCode.VALIDATION_ERROR, "未配置 ssh 连接信息")))
                .isEqualTo(CollectionErrorCategory.CONFIG_MISSING);
        assertThat(SshProcessCollectionObserver.classify(
                new BizException(ErrorCode.VALIDATION_ERROR, "未配置部署诊断参数")))
                .as("两类缺配置必须分开：处置建议不同，且只有一类能说'目标没有连接配置'")
                .isEqualTo(CollectionErrorCategory.DIAGNOSTIC_CONFIG_MISSING);
        assertThat(SshProcessCollectionObserver.classify(new BizException(ErrorCode.VALIDATION_ERROR,
                "未配置部署诊断参数，请在环境配置中添加 deploy.root、deploy.port 等参数")))
                .as("缺配置的判定必须排在通用失败关键词之前，否则会被误判成探测故障")
                .isEqualTo(CollectionErrorCategory.DIAGNOSTIC_CONFIG_MISSING);
        assertThat(SshProcessCollectionObserver.classify(new IllegalStateException("Invalid password for user")))
                .isEqualTo(CollectionErrorCategory.AUTH_FAILED);
        assertThat(SshProcessCollectionObserver.classify(new IllegalStateException("私钥读取失败")))
                .isEqualTo(CollectionErrorCategory.AUTH_FAILED);
        assertThat(SshProcessCollectionObserver.classify(new IllegalStateException("Read timed out")))
                .isEqualTo(CollectionErrorCategory.NETWORK_TIMEOUT);
        assertThat(SshProcessCollectionObserver.classify(new IllegalStateException("Connection refused")))
                .isEqualTo(CollectionErrorCategory.TARGET_UNREACHABLE);
        assertThat(SshProcessCollectionObserver.classify(new IllegalStateException("java.net.ConnectException")))
                .isEqualTo(CollectionErrorCategory.TARGET_UNREACHABLE);
        assertThat(SshProcessCollectionObserver.classify(new IllegalStateException("something else entirely")))
                .isEqualTo(CollectionErrorCategory.PROBE_ERROR);
    }

    // ---------- fixtures ----------

    private static CollectionTargetRef hostRef(Long hostId, Long configId) {
        return hostRef(hostId, configId, DEFAULT_PORT);
    }

    private static CollectionTargetRef hostRef(Long hostId, Long configId, Integer sshPort) {
        return hostRef(hostId, "10.9.9." + hostId, configId, sshPort);
    }

    private static CollectionTargetRef hostRef(Long hostId, String address, Long configId, Integer sshPort) {
        return new CollectionTargetRef(CollectionTargetType.HOST, hostId, hostId, null, configId, T0,
                address, sshPort, null, null);
    }

    private static CollectionTargetRef instanceRef(Long instanceId, Long hostId, String instanceType) {
        return new CollectionTargetRef(CollectionTargetType.SERVICE_INSTANCE, instanceId, hostId,
                instanceType, 5L, T0, "10.9.9." + hostId, DEFAULT_PORT, SERVICE_ID, ENV_ID);
    }

    private static RuntimeServiceInstanceStatusResponse instanceStatus(String stdout, boolean running,
                                                                      String healthStatus) {
        RuntimeServiceInstanceStatusResponse response = new RuntimeServiceInstanceStatusResponse();
        response.setStdout(stdout);
        response.setRunning(running);
        response.setHealthStatus(healthStatus);
        return response;
    }

    private AssetHost giveHost(Long id) {
        AssetHost host = new AssetHost();
        host.setId(id);
        host.setHostname("host-" + id);
        host.setIp("10.9.9." + id);
        when(assetHostService.getById(id)).thenReturn(host);
        return host;
    }

    private AssetHostSshConfig giveSshConfig(Long id, Long hostId) {
        AssetHostSshConfig config = new AssetHostSshConfig();
        config.setId(id);
        config.setHostId(hostId);
        config.setEnabled(Boolean.TRUE);
        config.setUpdatedAt(T0);
        config.setSshPort(DEFAULT_PORT);
        config.setUsername("deploy");
        config.setAuthType("KEY");
        // 拨号侧只按**快照里捕获的 id** 取配置，不再按 hostId 取"当前启用的那一条"
        when(assetHostSshConfigService.getById(id)).thenReturn(config);
        return config;
    }
}

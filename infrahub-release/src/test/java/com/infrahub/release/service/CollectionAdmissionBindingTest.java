package com.infrahub.release.service;

import com.infrahub.base.entity.BaseServiceEntity;
import com.infrahub.base.service.BaseServiceEntityService;
import com.infrahub.infra.ssh.SshExecutionRequest;
import com.infrahub.infra.ssh.SshExecutionResult;
import com.infrahub.infra.ssh.SshExecutorService;
import com.infrahub.release.config.HostInstanceCollectionProperties;
import com.infrahub.release.dto.CollectionObservation;
import com.infrahub.release.dto.CollectionTargetRef;
import com.infrahub.release.entity.AssetHost;
import com.infrahub.release.entity.AssetHostSshConfig;
import com.infrahub.release.entity.ReleaseArtifact;
import com.infrahub.release.entity.ReleaseVersion;
import com.infrahub.release.entity.RuntimeServiceInstance;
import com.infrahub.release.support.CollectionAddress;
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
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 准入快照与**实际拨号**的绑定测试。
 *
 * <p>钉住的命题只有一条：**送到执行器手里的那个 {@link SshExecutionRequest}，其端点、端口与超时
 * 只能来自采样时刻的快照**，库里的行只被用来判定"是否还能按旧样本拨"。因此本测试：
 * <ul>
 *   <li>用**真的** {@link SshProcessCollectionObserver}、**真的** {@link RuntimeServiceDiagnosticsService}、
 *       **真的** {@link CollectionDialResolver}，只把 mapper/底层执行器/密钥解析换成替身 ——
 *       把观测器或诊断逻辑 mock 掉，被测对象就没了；</li>
 *   <li>HOST 与 SERVICE_INSTANCE 两条路径都**捕获真实的请求对象**逐字段断言，而不是只看观测结论；</li>
 *   <li>快照身份/配置的每一维变动都给一个用例，断言"拨号前取消"，并且
 *       {@code verify(executor, never()).execute(...)} —— 取消必须是没拨出去，不是拨了再判失败；</li>
 *   <li>用一个**确定性钩子**（配置台账读取的 answer）在"校验已全部完成、请求尚未交给执行器"处改库，
 *       证明端点/诊断参数不会被更深一层的重读改写，且每个台账只被读一次。</li>
 * </ul>
 *
 * <p>不连任何真实主机：{@link SshExecutorService} 是替身，{@link CollectionTargetRef} 是手写的采样结果。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CollectionAdmissionBindingTest {

    private static final OffsetDateTime T0 = OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    private static final Clock FIXED_CLOCK = Clock.fixed(T0.toInstant(), ZoneOffset.UTC);
    private static final long TIMEOUT_MS = 3_000L;

    private static final long HOST_ID = 1L;
    private static final long OTHER_HOST_ID = 2L;
    private static final long CONFIG_ID = 5L;
    private static final long OTHER_CONFIG_ID = 6L;
    private static final long INSTANCE_ID = 200L;
    private static final long SERVICE_ID = 9L;
    private static final long OTHER_SERVICE_ID = 8L;
    private static final long ENV_ID = 3L;
    private static final long OTHER_ENV_ID = 4L;
    private static final long APP_ID = 11L;

    private static final int PORT = 22;
    private static final int OTHER_PORT = 2222;
    private static final String INSTANCE_TYPE = "PROCESS";
    private static final String OTHER_INSTANCE_TYPE = "JAVA_AGENT";
    private static final String HOST_IP = "192.0.2.21";
    private static final String HOST_HOSTNAME = "host-1.internal";
    private static final String USERNAME = "deploy";
    private static final String OTHER_USERNAME = "somebody-else";
    private static final String AUTH_TYPE = "KEY";
    private static final long PRIVATE_KEY_REF = 77L;
    private static final String PRIVATE_KEY_PATH = "/etc/infrahub/id_rsa";

    private static final String REACHABLE_MARKER = "__INFRAHUB_HOST_REACHABLE__=";
    private static final String RUNNING_MARKER = "__INFRAHUB_RUNNING__=";
    private static final String PROCESS_STDOUT = "__INFRAHUB_PID__=4242\n"
            + "__INFRAHUB_RUNNING__=true\n"
            + "__INFRAHUB_PORT_LISTENING__=true\n"
            + "__INFRAHUB_HEALTH_BEGIN__\n"
            + "{\"status\":\"UP\"}\n"
            + "__INFRAHUB_HEALTH_END__\n"
            + "__INFRAHUB_HEALTH_EXIT__=0\n";

    @Mock
    private AssetHostService assetHostService;
    @Mock
    private AssetHostSshConfigService assetHostSshConfigService;
    @Mock
    private SecretValueResolver secretValueResolver;
    @Mock
    private SshExecutorService sshExecutorService;
    @Mock
    private RuntimeServiceInstanceService runtimeServiceInstanceService;
    @Mock
    private BaseServiceEntityService baseServiceEntityService;
    @Mock
    private DeployConfigService deployConfigService;
    @Mock
    private ReleaseVersionService releaseVersionService;
    @Mock
    private ReleaseArtifactService releaseArtifactService;

    private SshProcessCollectionObserver observer;

    @BeforeEach
    void setUp() {
        HostInstanceCollectionProperties properties = new HostInstanceCollectionProperties();
        properties.setSingleTargetTimeoutMs(TIMEOUT_MS);

        CollectionDialResolver dialResolver = new CollectionDialResolver(
                assetHostService, assetHostSshConfigService, secretValueResolver);
        RuntimeServiceDiagnosticsService diagnosticsService = new RuntimeServiceDiagnosticsService(
                runtimeServiceInstanceService, assetHostService, assetHostSshConfigService,
                baseServiceEntityService, deployConfigService, secretValueResolver,
                sshExecutorService, dialResolver, releaseVersionService, releaseArtifactService);
        observer = new SshProcessCollectionObserver(
                dialResolver, sshExecutorService, diagnosticsService, properties, FIXED_CLOCK);

        when(secretValueResolver.resolve(PRIVATE_KEY_REF)).thenReturn(PRIVATE_KEY_PATH);
        when(sshExecutorService.execute(any())).thenReturn(
                SshExecutionResult.success(REACHABLE_MARKER + "true\n" + PROCESS_STDOUT, "", T0, T0));
    }

    // ---------- 1. 两条 happy path：真实请求对象逐字段 ----------

    @Test
    @DisplayName("主机拨号：请求的地址/端口/认证材料/超时全部来自快照，观测结论 ONLINE")
    void hostDialRequestComesEntirelyFromTheSampledSnapshot() {
        giveHost(HOST_IP, HOST_HOSTNAME);
        giveSshConfig(CONFIG_ID, HOST_ID, PORT, T0);

        CollectionObservation observation = observer.observe(hostRef(HOST_ID, HOST_IP, CONFIG_ID, PORT));

        SshExecutionRequest request = captureSingleRequest();
        assertThat(request.getHost()).as("拨号地址取自快照").isEqualTo(HOST_IP);
        assertThat(request.getPort()).as("拨号端口取自快照").isEqualTo(PORT);
        assertThat(request.getUsername()).isEqualTo(USERNAME);
        assertThat(request.getAuthType()).isEqualTo(AUTH_TYPE);
        assertThat(request.getPrivateKeyPath()).isEqualTo(PRIVATE_KEY_PATH);
        assertThat(request.getTimeoutMillis()).as("逐目标超时下推到执行器").isEqualTo(TIMEOUT_MS);
        assertThat(request.getCommand()).contains(REACHABLE_MARKER);

        assertThat(observation.result()).isEqualTo(CollectionResult.SUCCESS);
        assertThat(observation.observedStatus()).isEqualTo("ONLINE");
    }

    @Test
    @DisplayName("主机拨号：即使库里另有一条'当前启用'的配置，也只按快照里那条拨，不走按 hostId 取启用配置的老路")
    void hostDialNeverTakesTheCurrentlyEnabledConfigInsteadOfTheSampledOne() {
        giveHost(HOST_IP, HOST_HOSTNAME);
        giveSshConfig(CONFIG_ID, HOST_ID, PORT, T0);
        AssetHostSshConfig decoy = giveSshConfig(OTHER_CONFIG_ID, HOST_ID, OTHER_PORT, T0);
        decoy.setUsername(OTHER_USERNAME);
        // 老路径（手工诊断）会按 hostId 取"当前启用的那一条"——这里把它指向诱饵配置
        when(assetHostSshConfigService.getEnabledByHostId(HOST_ID)).thenReturn(decoy);

        observer.observe(hostRef(HOST_ID, HOST_IP, CONFIG_ID, PORT));

        SshExecutionRequest request = captureSingleRequest();
        assertThat(request.getPort()).as("端口只能来自快照，不能是'当前启用的另一条'").isEqualTo(PORT);
        assertThat(request.getUsername()).isEqualTo(USERNAME);
        verify(assetHostSshConfigService, never()).getEnabledByHostId(anyLong());
        verify(assetHostSshConfigService, times(1)).getById(CONFIG_ID);
    }

    @Test
    @DisplayName("实例拨号：端点来自快照，诊断参数按快照里的服务/环境解析，观测结论 RUNNING/UP")
    void instanceDialRequestComesEntirelyFromTheSampledSnapshot() {
        giveHost(HOST_IP, HOST_HOSTNAME);
        giveSshConfig(CONFIG_ID, HOST_ID, PORT, T0);
        giveInstance(HOST_ID, SERVICE_ID, ENV_ID, INSTANCE_TYPE);
        giveService(SERVICE_ID, APP_ID, ENV_ID);

        CollectionObservation observation = observer.observe(
                instanceRef(INSTANCE_ID, HOST_ID, INSTANCE_TYPE, HOST_IP, CONFIG_ID, PORT, T0, SERVICE_ID, ENV_ID));

        SshExecutionRequest request = captureSingleRequest();
        assertThat(request.getHost()).isEqualTo(HOST_IP);
        assertThat(request.getPort()).isEqualTo(PORT);
        assertThat(request.getTimeoutMillis()).isEqualTo(TIMEOUT_MS);
        assertThat(request.getCommand())
                .as("诊断命令里的 PID/日志路径必须是快照那对 service/env 解析出来的")
                .contains(deployRoot(APP_ID, ENV_ID) + "/run/app.pid");

        assertThat(observation.result()).isEqualTo(CollectionResult.SUCCESS);
        assertThat(observation.observedStatus()).isEqualTo("RUNNING");
        assertThat(observation.observedHealth()).isEqualTo("UP");
        verify(deployConfigService).loadDeployConfig(APP_ID, ENV_ID);
        verify(assetHostSshConfigService, never()).getEnabledByHostId(anyLong());
    }

    @Test
    void composeVersionUsesProjectLabelsEvenForAnExistingProcessRegistration() {
        giveHost(HOST_IP, HOST_HOSTNAME);
        giveSshConfig(CONFIG_ID, HOST_ID, PORT, T0);
        RuntimeServiceInstance instance = giveInstance(HOST_ID, SERVICE_ID, ENV_ID, "SPRING_BOOT");
        instance.setInstanceName("test-compose");
        instance.setVersionId(13L);
        giveService(SERVICE_ID, APP_ID, ENV_ID);
        ReleaseVersion version = new ReleaseVersion();
        version.setArtifactId(14L);
        when(releaseVersionService.getById(13L)).thenReturn(version);
        ReleaseArtifact artifact = new ReleaseArtifact();
        artifact.setArtifactType("DOCKER_COMPOSE");
        when(releaseArtifactService.getById(14L)).thenReturn(artifact);
        when(sshExecutorService.execute(any())).thenReturn(SshExecutionResult.success(
                PROCESS_STDOUT.replace("__INFRAHUB_PID__=4242", "__INFRAHUB_COMPOSE_HEALTH__=DOWN"), "", T0, T0));

        CollectionObservation observation = observer.observe(
                instanceRef(INSTANCE_ID, HOST_ID, "SPRING_BOOT", HOST_IP, CONFIG_ID, PORT, T0, SERVICE_ID, ENV_ID));

        assertThat(captureSingleRequest().getCommand())
                .contains("COMPOSE_PROJECT='test-compose'", "com.docker.compose.project=$COMPOSE_PROJECT")
                .doesNotContain("PID_FILE");
        assertThat(observation.observedStatus()).isEqualTo("RUNNING");
        assertThat(observation.observedHealth()).isEqualTo("DOWN");
    }

    // ---------- 2. 主机侧：采样后改址 / 删主机 ----------

    @Test
    @DisplayName("采样后主机改了 IP：拨号前取消，且一次都没拨出去")
    void hostAddressChangedAfterSamplingCancels() {
        giveHost("192.0.2.24", HOST_HOSTNAME);
        giveSshConfig(CONFIG_ID, HOST_ID, PORT, T0);

        assertCancelledWithoutDialing(observer.observe(hostRef(HOST_ID, HOST_IP, CONFIG_ID, PORT)));
    }

    @Test
    @DisplayName("采样没有 IP、落在主机名上：采样后主机名被改，同样按址变更取消")
    void hostHostnameChangedAfterSamplingCancels() {
        giveHost("   ", "renamed.internal");
        giveSshConfig(CONFIG_ID, HOST_ID, PORT, T0);

        assertCancelledWithoutDialing(observer.observe(hostRef(HOST_ID, HOST_HOSTNAME, CONFIG_ID, PORT)));
    }

    @Test
    @DisplayName("采样后主机被删除：拨号前取消")
    void hostDeletedAfterSamplingCancels() {
        when(assetHostService.getById(HOST_ID)).thenReturn(null);
        giveSshConfig(CONFIG_ID, HOST_ID, PORT, T0);

        assertCancelledWithoutDialing(observer.observe(hostRef(HOST_ID, HOST_IP, CONFIG_ID, PORT)));
    }

    // ---------- 3. 主机侧：采样后连接配置被删/停用/改绑/改端口/改版本/换一条 ----------

    @Test
    @DisplayName("采样后 SSH 连接配置被删除：取消，不悄悄改用别的启用配置")
    void configDeletedAfterSamplingCancels() {
        giveHost(HOST_IP, HOST_HOSTNAME);
        when(assetHostSshConfigService.getById(CONFIG_ID)).thenReturn(null);

        assertCancelledWithoutDialing(observer.observe(hostRef(HOST_ID, HOST_IP, CONFIG_ID, PORT)));
        verify(assetHostSshConfigService, never()).getEnabledByHostId(anyLong());
    }

    @Test
    @DisplayName("采样后 SSH 连接配置被停用：取消")
    void configDisabledAfterSamplingCancels() {
        giveHost(HOST_IP, HOST_HOSTNAME);
        giveSshConfig(CONFIG_ID, HOST_ID, PORT, T0).setEnabled(Boolean.FALSE);

        assertCancelledWithoutDialing(observer.observe(hostRef(HOST_ID, HOST_IP, CONFIG_ID, PORT)));
    }

    @Test
    @DisplayName("采样后 SSH 连接配置被改绑到别的主机：取消")
    void configReboundToAnotherHostCancels() {
        giveHost(HOST_IP, HOST_HOSTNAME);
        giveSshConfig(CONFIG_ID, OTHER_HOST_ID, PORT, T0);

        assertCancelledWithoutDialing(observer.observe(hostRef(HOST_ID, HOST_IP, CONFIG_ID, PORT)));
    }

    @Test
    @DisplayName("采样后 SSH 生效端口被单独改写（配置版本时刻没动）：仍按址/端口变更取消")
    void configPortChangedAfterSamplingCancels() {
        giveHost(HOST_IP, HOST_HOSTNAME);
        giveSshConfig(CONFIG_ID, HOST_ID, OTHER_PORT, T0);

        assertCancelledWithoutDialing(observer.observe(hostRef(HOST_ID, HOST_IP, CONFIG_ID, PORT)));
    }

    @Test
    @DisplayName("采样后 SSH 配置被修改（版本时刻变了）：取消")
    void configVersionChangedAfterSamplingCancels() {
        giveHost(HOST_IP, HOST_HOSTNAME);
        giveSshConfig(CONFIG_ID, HOST_ID, PORT, T0.plusSeconds(1));

        assertCancelledWithoutDialing(observer.observe(hostRef(HOST_ID, HOST_IP, CONFIG_ID, PORT)));
    }

    @Test
    @DisplayName("地址与端口都没变、同一条配置但版本不同：旧样本必须被拒收")
    void sameAddressSamePortButDifferentConfigVersionCancels() {
        giveHost(HOST_IP, HOST_HOSTNAME);
        giveSshConfig(CONFIG_ID, HOST_ID, PORT, T0.plusHours(1));

        assertCancelledWithoutDialing(observer.observe(hostRef(HOST_ID, HOST_IP, CONFIG_ID, PORT)));
    }

    // ---------- 4. 实例侧：采样后实例身份任一维变动 / 实例被删 ----------

    @Test
    @DisplayName("采样后运行实例被删除：拨号前取消")
    void instanceDeletedAfterSamplingCancels() {
        giveHost(HOST_IP, HOST_HOSTNAME);
        giveSshConfig(CONFIG_ID, HOST_ID, PORT, T0);
        when(runtimeServiceInstanceService.getById(INSTANCE_ID)).thenReturn(null);

        assertCancelledWithoutDialing(observer.observe(
                instanceRef(INSTANCE_ID, HOST_ID, INSTANCE_TYPE, HOST_IP, CONFIG_ID, PORT, T0, SERVICE_ID, ENV_ID)));
    }

    @Test
    @DisplayName("采样后实例改绑到别的主机：绝不把旧实例拨到新主机上")
    void instanceHostChangedAfterSamplingCancels() {
        giveHost(HOST_IP, HOST_HOSTNAME);
        giveSshConfig(CONFIG_ID, HOST_ID, PORT, T0);
        giveInstance(OTHER_HOST_ID, SERVICE_ID, ENV_ID, INSTANCE_TYPE);
        giveService(SERVICE_ID, APP_ID, ENV_ID);

        assertCancelledWithoutDialing(observer.observe(
                instanceRef(INSTANCE_ID, HOST_ID, INSTANCE_TYPE, HOST_IP, CONFIG_ID, PORT, T0, SERVICE_ID, ENV_ID)));
    }

    @Test
    @DisplayName("采样后实例换了所属服务：取消，不把新服务的诊断参数套到旧实例上")
    void instanceServiceChangedAfterSamplingCancels() {
        giveHost(HOST_IP, HOST_HOSTNAME);
        giveSshConfig(CONFIG_ID, HOST_ID, PORT, T0);
        giveInstance(HOST_ID, OTHER_SERVICE_ID, ENV_ID, INSTANCE_TYPE);

        assertCancelledWithoutDialing(observer.observe(
                instanceRef(INSTANCE_ID, HOST_ID, INSTANCE_TYPE, HOST_IP, CONFIG_ID, PORT, T0, SERVICE_ID, ENV_ID)));
        verify(deployConfigService, never()).loadDeployConfig(anyLong(), anyLong());
    }

    @Test
    @DisplayName("采样后实例换了所属环境：取消，不把新环境的诊断参数套到旧实例上")
    void instanceEnvChangedAfterSamplingCancels() {
        giveHost(HOST_IP, HOST_HOSTNAME);
        giveSshConfig(CONFIG_ID, HOST_ID, PORT, T0);
        giveInstance(HOST_ID, SERVICE_ID, OTHER_ENV_ID, INSTANCE_TYPE);
        giveService(SERVICE_ID, APP_ID, ENV_ID);

        assertCancelledWithoutDialing(observer.observe(
                instanceRef(INSTANCE_ID, HOST_ID, INSTANCE_TYPE, HOST_IP, CONFIG_ID, PORT, T0, SERVICE_ID, ENV_ID)));
        verify(deployConfigService, never()).loadDeployConfig(anyLong(), anyLong());
    }

    @Test
    @DisplayName("采样后实例改了类型：取消，不用另一种类型的观测方式去猜")
    void instanceTypeChangedAfterSamplingCancels() {
        giveHost(HOST_IP, HOST_HOSTNAME);
        giveSshConfig(CONFIG_ID, HOST_ID, PORT, T0);
        giveInstance(HOST_ID, SERVICE_ID, ENV_ID, OTHER_INSTANCE_TYPE);
        giveService(SERVICE_ID, APP_ID, ENV_ID);

        assertCancelledWithoutDialing(observer.observe(
                instanceRef(INSTANCE_ID, HOST_ID, INSTANCE_TYPE, HOST_IP, CONFIG_ID, PORT, T0, SERVICE_ID, ENV_ID)));
    }

    // ---------- 5. 版本比对按时间语义：同 Instant 不同偏移不算变更 ----------

    @Test
    @DisplayName("配置版本时刻同一 Instant、只是偏移不同：不算变更，照常拨号")
    void sameInstantWithDifferentOffsetIsNotAChange() {
        giveHost(HOST_IP, HOST_HOSTNAME);
        giveSshConfig(CONFIG_ID, HOST_ID, PORT, T0.withOffsetSameInstant(ZoneOffset.ofHours(8)));

        CollectionObservation observation = observer.observe(hostRef(HOST_ID, HOST_IP, CONFIG_ID, PORT));

        assertThat(observation.result()).isEqualTo(CollectionResult.SUCCESS);
        SshExecutionRequest request = captureSingleRequest();
        assertThat(request.getHost()).isEqualTo(HOST_IP);
        assertThat(request.getPort()).isEqualTo(PORT);
    }

    // ---------- 6. 校验完成之后、执行器收到请求之前改库：请求不得被改写 ----------

    @Test
    @DisplayName("全部校验完成、请求尚未交给执行器时库行被改：捕获到的主机拨号请求仍是快照值，台账只读一次")
    void endpointIsNotSwappedWhenTheRowsChangeAfterValidationCompletes() {
        AssetHost host = giveHost(HOST_IP, HOST_HOSTNAME);
        AssetHostSshConfig row = sshConfigRow(CONFIG_ID, HOST_ID, PORT, T0);
        // 钩子：配置台账被读取的那一刻（校验已全部完成、请求尚未交给执行器）把库里两边都改掉，
        // 但**返回读取发生时的值**——这正是"读到的就是当时那条配置"的语义。
        // 这是可控回调而不是 sleep 竞态：读几次就变几次，顺序完全确定。
        when(assetHostSshConfigService.getById(CONFIG_ID)).thenAnswer(invocation -> {
            AssetHostSshConfig readAt = copyOf(row);
            host.setIp("192.0.2.24");
            host.setHostname("swapped.internal");
            row.setSshPort(OTHER_PORT);
            row.setUsername(OTHER_USERNAME);
            row.setUpdatedAt(T0.plusHours(1));
            row.setEnabled(Boolean.FALSE);
            return readAt;
        });

        CollectionObservation observation = observer.observe(hostRef(HOST_ID, HOST_IP, CONFIG_ID, PORT));

        assertThat(observation.result()).isEqualTo(CollectionResult.SUCCESS);
        SshExecutionRequest request = captureSingleRequest();
        assertThat(request.getHost()).as("端点只能来自快照").isEqualTo(HOST_IP);
        assertThat(request.getPort()).as("端口只能来自快照").isEqualTo(PORT);
        assertThat(request.getUsername()).as("认证材料取自校验通过的那一刻那条配置").isEqualTo(USERNAME);
        verify(assetHostService, times(1)).getById(HOST_ID);
        verify(assetHostSshConfigService, times(1)).getById(CONFIG_ID);
    }

    @Test
    @DisplayName("实例路径同理：校验后库行被改，端点与诊断参数仍是快照那对，台账各只读一次")
    void instanceEndpointAndDiagnosticParamsAreNotSwappedAfterValidationCompletes() {
        AssetHost host = giveHost(HOST_IP, HOST_HOSTNAME);
        AssetHostSshConfig row = sshConfigRow(CONFIG_ID, HOST_ID, PORT, T0);
        RuntimeServiceInstance instance = giveInstance(HOST_ID, SERVICE_ID, ENV_ID, INSTANCE_TYPE);
        giveService(SERVICE_ID, APP_ID, ENV_ID);
        when(assetHostSshConfigService.getById(CONFIG_ID)).thenAnswer(invocation -> {
            AssetHostSshConfig readAt = copyOf(row);
            host.setIp("192.0.2.24");
            row.setSshPort(OTHER_PORT);
            instance.setHostId(OTHER_HOST_ID);
            instance.setServiceId(OTHER_SERVICE_ID);
            instance.setEnvId(OTHER_ENV_ID);
            instance.setInstanceType(OTHER_INSTANCE_TYPE);
            return readAt;
        });

        CollectionObservation observation = observer.observe(
                instanceRef(INSTANCE_ID, HOST_ID, INSTANCE_TYPE, HOST_IP, CONFIG_ID, PORT, T0, SERVICE_ID, ENV_ID));

        assertThat(observation.result()).isEqualTo(CollectionResult.SUCCESS);
        SshExecutionRequest request = captureSingleRequest();
        assertThat(request.getHost()).isEqualTo(HOST_IP);
        assertThat(request.getPort()).isEqualTo(PORT);
        assertThat(request.getCommand())
                .contains(deployRoot(APP_ID, ENV_ID) + "/run/app.pid")
                .as("诊断参数来自快照里那对服务/环境")
                .doesNotContain(deployRoot(APP_ID + 1, ENV_ID));
        verify(runtimeServiceInstanceService, times(1)).getById(INSTANCE_ID);
        verify(assetHostService, times(1)).getById(HOST_ID);
        verify(assetHostSshConfigService, times(1)).getById(CONFIG_ID);
        verify(baseServiceEntityService, times(1)).getById(SERVICE_ID);
        verify(deployConfigService, times(1)).loadDeployConfig(APP_ID, ENV_ID);
    }

    // ---------- 断言与夹具 ----------

    /** 取消的共同语义：跳过、非失败类别、没有观测结论、**一次都没拨出去**。 */
    private void assertCancelledWithoutDialing(CollectionObservation observation) {
        assertThat(observation.result()).isEqualTo(CollectionResult.SKIPPED_ADMISSION_CHANGED);
        assertThat(observation.result().isSkip()).isTrue();
        assertThat(observation.result().isFailure()).as("取消不是失败").isFalse();
        assertThat(observation.errorCategory()).isEqualTo(CollectionErrorCategory.ADMISSION_CHANGED);
        assertThat(observation.errorCategory().isFailureCategory())
                .as("准入变更不进失败白名单，因此不推进连续失败计数").isFalse();
        assertThat(observation.observedStatus()).as("没拨号就不许给出目标状态").isNull();
        assertThat(observation.observedHealth()).isNull();
        assertThat(observation.observedAt()).isEqualTo(T0);
        assertThat(observation.errorSummary()).isNotBlank();
        verify(sshExecutorService, never()).execute(any());
    }

    private SshExecutionRequest captureSingleRequest() {
        ArgumentCaptor<SshExecutionRequest> captor = ArgumentCaptor.forClass(SshExecutionRequest.class);
        verify(sshExecutorService).execute(captor.capture());
        return captor.getValue();
    }

    private static String deployRoot(long appId, long envId) {
        return "/opt/app-" + appId + "/env-" + envId;
    }

    private static CollectionTargetRef hostRef(long hostId, String address, Long configId, Integer sshPort) {
        return new CollectionTargetRef(CollectionTargetType.HOST, hostId, hostId, null,
                configId, configId == null ? null : T0, address, sshPort, null, null);
    }

    private static CollectionTargetRef instanceRef(long instanceId, long hostId, String instanceType,
                                                   String address, Long configId, Integer sshPort,
                                                   OffsetDateTime configVersion, long serviceId, long envId) {
        return new CollectionTargetRef(CollectionTargetType.SERVICE_INSTANCE, instanceId, hostId, instanceType,
                configId, configVersion, address, sshPort, serviceId, envId);
    }

    private AssetHost giveHost(String ip, String hostname) {
        AssetHost host = new AssetHost();
        host.setId(HOST_ID);
        host.setIp(ip);
        host.setHostname(hostname);
        when(assetHostService.getById(HOST_ID)).thenReturn(host);
        assertThat(CollectionAddress.of(host)).as("夹具自查：库里的拨号地址").isNotNull();
        return host;
    }

    private AssetHostSshConfig giveSshConfig(Long id, Long hostId, Integer sshPort, OffsetDateTime updatedAt) {
        AssetHostSshConfig config = sshConfigRow(id, hostId, sshPort, updatedAt);
        when(assetHostSshConfigService.getById(id)).thenReturn(config);
        return config;
    }

    /** 只造一条"库里的行"，不注册桩；需要自己控制读取时机（钩子）时用这个。 */
    private static AssetHostSshConfig sshConfigRow(Long id, Long hostId, Integer sshPort, OffsetDateTime updatedAt) {
        AssetHostSshConfig config = new AssetHostSshConfig();
        config.setId(id);
        config.setHostId(hostId);
        config.setEnabled(Boolean.TRUE);
        config.setUpdatedAt(updatedAt);
        config.setSshPort(sshPort);
        config.setUsername(USERNAME);
        config.setAuthType(AUTH_TYPE);
        config.setPrivateKeySecretRefId(PRIVATE_KEY_REF);
        return config;
    }

    /** 读取那一刻的值：钩子里要返回它，才符合"读到的是当时那条配置"的语义。 */
    private static AssetHostSshConfig copyOf(AssetHostSshConfig source) {
        AssetHostSshConfig copy = new AssetHostSshConfig();
        copy.setId(source.getId());
        copy.setHostId(source.getHostId());
        copy.setEnabled(source.getEnabled());
        copy.setUpdatedAt(source.getUpdatedAt());
        copy.setSshPort(source.getSshPort());
        copy.setUsername(source.getUsername());
        copy.setAuthType(source.getAuthType());
        copy.setPasswordSecretRefId(source.getPasswordSecretRefId());
        copy.setPrivateKeySecretRefId(source.getPrivateKeySecretRefId());
        copy.setPassphraseSecretRefId(source.getPassphraseSecretRefId());
        return copy;
    }

    private RuntimeServiceInstance giveInstance(Long hostId, Long serviceId, Long envId, String instanceType) {
        RuntimeServiceInstance instance = new RuntimeServiceInstance();
        instance.setId(INSTANCE_ID);
        instance.setHostId(hostId);
        instance.setServiceId(serviceId);
        instance.setEnvId(envId);
        instance.setInstanceType(instanceType);
        when(runtimeServiceInstanceService.getById(INSTANCE_ID)).thenReturn(instance);
        return instance;
    }

    /** 服务台账 + 该服务在该环境下的部署诊断参数；{@code deploy.root} 把 (appId, envId) 编进路径，便于断言参数来源。 */
    private void giveService(Long serviceId, Long appId, Long envId) {
        BaseServiceEntity service = new BaseServiceEntity();
        service.setId(serviceId);
        service.setAppId(appId);
        service.setServiceCode("demo-service");
        when(baseServiceEntityService.getById(serviceId)).thenReturn(service);

        Map<String, String> deployConfig = new HashMap<>();
        deployConfig.put("deploy.root", deployRoot(appId, envId));
        when(deployConfigService.loadDeployConfig(appId, envId)).thenReturn(deployConfig);
    }
}

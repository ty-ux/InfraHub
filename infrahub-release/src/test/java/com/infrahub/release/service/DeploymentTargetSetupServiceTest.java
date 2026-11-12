package com.infrahub.release.service;

import com.infrahub.base.entity.*;
import com.infrahub.base.mapper.*;
import com.infrahub.common.exception.BizException;
import com.infrahub.release.dto.*;
import com.infrahub.release.entity.*;
import com.infrahub.release.mapper.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.nio.file.*;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DeploymentTargetSetupServiceTest {
    @TempDir Path directory;
    AssetHostMapper hosts;
    AssetHostSshConfigMapper ssh;
    ConfigSecretRefMapper secrets;
    BaseServiceMapper services;
    BaseEnvMapper envs;
    RuntimeServiceInstanceMapper instances;
    ReleaseOrderMapper releases;
    ReleaseRollbackOrderMapper rollbacks;
    RuntimeExecutionUnknownService unknown;
    SysAuditLogMapper audits;
    DeploymentTargetSetupService setup;
    final HostSshSetupRequest sshRequest = new HostSshSetupRequest("root", 22, 11L, true);
    final RegisterRuntimeInstanceRequest instanceRequest = new RegisterRuntimeInstanceRequest(15L, 30L, 34L, " infrahub-example ");

    @BeforeEach void before() {
        hosts = mock(AssetHostMapper.class); ssh = mock(AssetHostSshConfigMapper.class);
        secrets = mock(ConfigSecretRefMapper.class); services = mock(BaseServiceMapper.class);
        envs = mock(BaseEnvMapper.class); instances = mock(RuntimeServiceInstanceMapper.class);
        releases = mock(ReleaseOrderMapper.class); rollbacks = mock(ReleaseRollbackOrderMapper.class);
        unknown = mock(RuntimeExecutionUnknownService.class); audits = mock(SysAuditLogMapper.class);
        setup = new DeploymentTargetSetupService(hosts, ssh, secrets, services, envs, instances, releases, rollbacks, unknown, audits);
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }
    @AfterEach void after() { TransactionSynchronizationManager.clear(); }

    AssetHost host() {
        AssetHost host = new AssetHost(); host.setId(34L); host.setEnvId(30L); return host;
    }
    ConfigSecretRef keyReference(String provider, String name) throws Exception {
        Path key = Files.writeString(directory.resolve(name), "fixture-only");
        ConfigSecretRef secret = new ConfigSecretRef(); secret.setId(11L);
        secret.setSecretProvider(provider); secret.setSecretPath(key.toString());
        when(hosts.selectFullForSetup(34L)).thenReturn(host());
        when(secrets.selectById(11L)).thenReturn(secret);
        when(ssh.selectList(any())).thenReturn(List.of());
        return secret;
    }
    void validRegistration() {
        when(services.lockById(15L)).thenReturn(15L);
        BaseServiceEntity service = new BaseServiceEntity(); service.setStatus("ENABLED");
        when(services.selectById(15L)).thenReturn(service);
        BaseEnv env = new BaseEnv(); env.setStatus("ENABLED"); when(envs.selectById(30L)).thenReturn(env);
        when(hosts.selectFullForSetup(34L)).thenReturn(host());
        when(instances.insert(any(RuntimeServiceInstance.class))).thenAnswer(call -> {
            call.getArgument(0, RuntimeServiceInstance.class).setId(90L); return 1;
        });
    }
    @Test void requiresActiveTransactionBeforeTakingLocks() {
        TransactionSynchronizationManager.clear();
        assertThatThrownBy(() -> setup.saveSsh(34L, sshRequest)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> setup.registerInstance(instanceRequest)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(hosts, services, instances, ssh);
    }
    @Test void savesOnlyThePrivateKeyReferenceWithoutInventingHostStatus() throws Exception {
        keyReference("LOCAL_FILE", "deploy-key");
        when(ssh.insert(any(AssetHostSshConfig.class))).thenReturn(1);
        AssetHostSshConfig result = setup.saveSsh(34L, sshRequest);
        assertThat(result.getAuthType()).isEqualTo("PRIVATE_KEY");
        assertThat(result.getPrivateKeySecretRefId()).isEqualTo(11L);
        assertThat(result.getPasswordSecretRefId()).isNull();
        assertThat(result.getPassphraseSecretRefId()).isNull();
        verify(hosts, never()).updateHostStatus(anyLong(), anyString());
        verify(audits).insertAuditLog(anyLong(), eq("HOST"), eq(34L), eq("CONFIGURE_SSH"), isNull(),
                eq("{\"authType\":\"PRIVATE_KEY\",\"credentialType\":\"LOCAL_FILE\"}"), eq("SUCCESS"), isNull());
    }
    @Test void rejectsPlaintextProvidersAndPublicKeyFiles() throws Exception {
        keyReference("INLINE", "deploy-key");
        assertThatThrownBy(() -> setup.saveSsh(34L, sshRequest)).isInstanceOf(BizException.class).hasMessageContaining("LOCAL_FILE");
        keyReference("LOCAL_FILE", "deploy-key.pub");
        assertThatThrownBy(() -> setup.saveSsh(34L, sshRequest)).isInstanceOf(BizException.class).hasMessageContaining("私钥文件");
        verify(ssh, never()).insert(any(AssetHostSshConfig.class));
    }
    @Test void canDisableAConnectionAfterTheKeyFileHasBeenRemoved() throws Exception {
        ConfigSecretRef secret = keyReference("LOCAL_FILE", "removed-key");
        Files.delete(Path.of(secret.getSecretPath()));
        when(ssh.insert(any(AssetHostSshConfig.class))).thenReturn(1);
        AssetHostSshConfig result = setup.saveSsh(34L, new HostSshSetupRequest("root", 22, 11L, false));
        assertThat(result.getEnabled()).isFalse();
    }

    @Test void authChangeExplicitlyClearsLegacyPasswordReferences() throws Exception {
        keyReference("LOCAL_FILE", "deploy-key");
        AssetHostSshConfig previous = new AssetHostSshConfig(); previous.setId(5L);
        previous.setPasswordSecretRefId(70L); previous.setPassphraseSecretRefId(71L);
        when(ssh.selectList(any())).thenReturn(List.of(previous));
        when(ssh.update(isNull(), any())).thenAnswer(call -> {
            var wrapper = call.getArgument(1, com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper.class);
            assertThat(wrapper.getSqlSet()).contains("password_secret_ref_id=", "passphrase_secret_ref_id=");
            assertThat(wrapper.getParamNameValuePairs()).containsValue(null);
            return 1;
        });
        AssetHostSshConfig result = setup.saveSsh(34L, sshRequest);
        assertThat(result.getPasswordSecretRefId()).isNull();
        assertThat(result.getPassphraseSecretRefId()).isNull();
        verify(ssh).update(isNull(), any());
    }
    @Test void rejectsAmbiguousHostConnections() throws Exception {
        keyReference("LOCAL_FILE", "deploy-key");
        when(ssh.selectList(any())).thenReturn(List.of(new AssetHostSshConfig(), new AssetHostSshConfig()));
        assertThatThrownBy(() -> setup.saveSsh(34L, sshRequest)).hasMessageContaining("多条 SSH");
        verify(ssh, never()).insert(any(AssetHostSshConfig.class));
    }
    @Test void registrationIsUnknownAndDoesNotForgeVersionOrHeartbeat() {
        validRegistration();
        RuntimeServiceInstance result = setup.registerInstance(instanceRequest);
        assertThat(result.getInstanceName()).isEqualTo("infrahub-example");
        assertThat(result.getInstanceType()).isEqualTo("SPRING_BOOT");
        assertThat(result.getInstanceStatus()).isEqualTo("UNKNOWN");
        assertThat(result.getHealthStatus()).isEqualTo("UNKNOWN");
        assertThat(result.getVersionId()).isNull(); assertThat(result.getStartTime()).isNull();
        assertThat(result.getLastHeartbeat()).isNull();
        var order = inOrder(services, hosts, instances);
        order.verify(services).lockById(15L); order.verify(services).selectById(15L);
        order.verify(hosts).selectFullForSetup(34L); order.verify(instances, times(2)).selectCount(any());
        order.verify(instances).insert(any(RuntimeServiceInstance.class));
    }
    @Test void rejectsHostFromDifferentEnvironment() {
        validRegistration(); AssetHost host = host(); host.setEnvId(99L);
        when(hosts.selectFullForSetup(34L)).thenReturn(host);
        assertThatThrownBy(() -> setup.registerInstance(instanceRequest)).hasMessageContaining("当前环境");
        verify(instances, never()).insert(any(RuntimeServiceInstance.class));
    }
    @Test void blocksRegistrationDuringReleaseOrRollback() {
        validRegistration(); when(releases.selectCount(any())).thenReturn(1L);
        assertThatThrownBy(() -> setup.registerInstance(instanceRequest)).hasMessageContaining("执行中的");
        when(releases.selectCount(any())).thenReturn(0L);
        when(rollbacks.countRunningByServiceAndEnv(15L, 30L)).thenReturn(1L);
        assertThatThrownBy(() -> setup.registerInstance(instanceRequest)).hasMessageContaining("执行中的");
        verify(instances, never()).insert(any(RuntimeServiceInstance.class));
    }
    @Test void refusesUnresolvedUnknownExecutionsAndDuplicates() {
        validRegistration(); doThrow(new BizException(com.infrahub.common.error.ErrorCode.VALIDATION_ERROR,"存在未知执行"))
                .when(unknown).assertNoUnresolvedUnknown(15L, 30L);
        assertThatThrownBy(() -> setup.registerInstance(instanceRequest)).hasMessageContaining("未知执行");
        reset(unknown); when(instances.selectCount(any())).thenReturn(1L);
        assertThatThrownBy(() -> setup.registerInstance(instanceRequest)).hasMessageContaining("同名实例");
        verify(instances, never()).insert(any(RuntimeServiceInstance.class));
    }
    @Test void handlesConcurrentNameCollisionAsValidationFailure() {
        validRegistration(); when(instances.insert(any(RuntimeServiceInstance.class))).thenThrow(new DuplicateKeyException("fixture"));
        assertThatThrownBy(() -> setup.registerInstance(instanceRequest)).isInstanceOf(BizException.class).hasMessageContaining("同名实例");
        verifyNoInteractions(audits);
    }
}

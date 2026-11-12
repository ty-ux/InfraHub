package com.infrahub.release.service;

import com.infrahub.common.exception.BizException;
import com.infrahub.release.dto.CreateReleaseExecutorConfigRequest;
import com.infrahub.release.entity.ReleaseExecutorConfig;
import com.infrahub.release.mapper.ReleaseExecutorConfigMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Saving, updating and toggling executor configurations.
 *
 * <p>The mapper is mocked, so nothing here touches a database: what is asserted is that an accepted
 * configuration reaches the mapper exactly once and a rejected one never reaches it at all, and that
 * a rejection is reported without repeating anything the payload contained.</p>
 */
class ReleaseExecutorConfigServiceTest {

    private ReleaseExecutorConfigMapper mapper;
    private ReleaseExecutorConfigService service;

    @BeforeEach
    void setUp() {
        mapper = mock(ReleaseExecutorConfigMapper.class);
        service = new ReleaseExecutorConfigService();
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
    }

    /** An absolute path as this host recognizes one, without requiring it to exist. */
    private static String hostAbsolute(String relative) {
        return Path.of(System.getProperty("user.dir")).resolve(relative).toString();
    }

    private static String validKubernetesPayload() {
        return """
                {"kubeconfigPath": "%s", "kubeContext": "bound-context", "namespace": "prod",
                 "manifestPath": "%s", "workloadName": "web",
                 "rolloutTimeoutSeconds": 300, "logTailLines": 100}
                """.formatted(
                hostAbsolute("configs/target.kubeconfig").replace("\\", "\\\\"),
                hostAbsolute("manifests/app.yaml").replace("\\", "\\\\"));
    }

    private static CreateReleaseExecutorConfigRequest request(String type, Long envId, String name,
                                                              String payload, Boolean enabled) {
        CreateReleaseExecutorConfigRequest request = new CreateReleaseExecutorConfigRequest();
        request.setExecutorType(type);
        request.setExecutorName(name);
        request.setEnvId(envId);
        request.setConfigPayload(payload);
        request.setEnabled(enabled);
        return request;
    }

    private static ReleaseExecutorConfig stored(Long id, String type, Long envId, String name,
                                                String payload, boolean enabled) {
        ReleaseExecutorConfig config = new ReleaseExecutorConfig();
        config.setId(id);
        config.setExecutorType(type);
        config.setEnvId(envId);
        config.setExecutorName(name);
        config.setConfigPayload(payload);
        config.setEnabled(enabled);
        return config;
    }

    private static String messageOf(BizException e) {
        return e.getMessage() == null ? "" : e.getMessage();
    }

    // ----- create -----

    @Test
    @DisplayName("合法的 Kubernetes 配置 → 写入一次，payload 原样保存，类型规范化")
    void createValidKubernetesConfigSavesOnce() {
        when(mapper.findByEnvIdAndName(1L, "k8s-prod")).thenReturn(null);
        when(mapper.insert(any(ReleaseExecutorConfig.class))).thenReturn(1);
        String payload = validKubernetesPayload();

        ReleaseExecutorConfig created = service.createConfig(
                request("kubernetes", 1L, "k8s-prod", payload, null));

        ArgumentCaptor<ReleaseExecutorConfig> captor = ArgumentCaptor.forClass(ReleaseExecutorConfig.class);
        verify(mapper, times(1)).insert(captor.capture());
        ReleaseExecutorConfig saved = captor.getValue();
        assertEquals("KUBERNETES", saved.getExecutorType());
        assertEquals(payload, saved.getConfigPayload());
        assertEquals(Boolean.TRUE, saved.getEnabled());
        assertEquals("KUBERNETES", created.getExecutorType());
    }

    @Test
    @DisplayName("非法 JSON → 拒绝且零持久化，不触碰 mapper")
    void createMalformedJsonIsRejectedBeforePersistence() {
        BizException e = assertThrows(BizException.class, () -> service.createConfig(
                request("KUBERNETES", 1L, "k8s-prod", "{\"kubeconfigPath\": ", true)));

        assertTrue(messageOf(e).contains("不是有效的 JSON"), () -> "message=" + messageOf(e));
        verifyNoInteractions(mapper);
    }

    @Test
    @DisplayName("非对象 JSON（数组）→ 拒绝且零持久化")
    void createNonObjectJsonIsRejectedBeforePersistence() {
        BizException e = assertThrows(BizException.class, () -> service.createConfig(
                request("KUBERNETES", 1L, "k8s-prod", "[{\"namespace\":\"prod\"}]", true)));

        assertTrue(messageOf(e).contains("JSON 对象"), () -> "message=" + messageOf(e));
        verifyNoInteractions(mapper);
    }

    @Test
    @DisplayName("字段类型错误（namespace 为数字）→ 拒绝且零持久化，不回显数值")
    void createWithWrongFieldTypeIsRejectedBeforePersistence() {
        BizException e = assertThrows(BizException.class, () -> service.createConfig(
                request("KUBERNETES", 1L, "k8s-prod", """
                        {"kubeconfigPath": "%s", "kubeContext": "c", "namespace": 123,
                         "manifestContent": "apiVersion: v1"}
                        """.formatted(hostAbsolute("cfg/kubeconfig").replace("\\", "\\\\")), true)));

        assertTrue(messageOf(e).contains("namespace"), () -> "message=" + messageOf(e));
        verifyNoInteractions(mapper);
    }

    @Test
    @DisplayName("缺项 → 一次列出全部缺项（可定位），可停用字段仍按缺项报告")
    void createWithMissingFieldsListsEveryField() {
        BizException e = assertThrows(BizException.class, () -> service.createConfig(
                request("KUBERNETES", 1L, "k8s-prod", "{\"namespace\": \"prod\"}", true)));

        String message = messageOf(e);
        assertTrue(message.contains("kubeconfigPath"), () -> "message=" + message);
        assertTrue(message.contains("kubeContext"), () -> "message=" + message);
        assertTrue(message.contains("manifest"), () -> "message=" + message);
        verifyNoInteractions(mapper);
    }

    @Test
    @DisplayName("错误信息不含 payload 中的任何值（canary）")
    void createRejectionNeverEchoesPayloadValues() {
        String payload = """
                {"kubeconfigPath": "CANARY-KUBECONFIG-DIR/kubeconfig", "kubeContext": "CANARY-CONTEXT",
                 "manifestContent": "CANARY-MANIFEST-CONTENT"}
                """;

        BizException relativePath = assertThrows(BizException.class, () -> service.createConfig(
                request("KUBERNETES", 1L, "k8s-prod", payload, true)));
        assertTrue(messageOf(relativePath).contains("kubeconfigPath"), () -> "message=" + messageOf(relativePath));
        assertTrue(messageOf(relativePath).contains("绝对路径"), () -> "message=" + messageOf(relativePath));
        assertFalse(messageOf(relativePath).contains("CANARY"), () -> "message=" + messageOf(relativePath));

        BizException missing = assertThrows(BizException.class, () -> service.createConfig(
                request("KUBERNETES", 1L, "k8s-prod", """
                        {"kubeconfigPath": "%s", "kubeContext": "c",
                         "manifestContent": "CANARY-MANIFEST-CONTENT"}
                        """.formatted(hostAbsolute("cfg/kubeconfig").replace("\\", "\\\\")), true)));
        assertTrue(messageOf(missing).contains("namespace"), () -> "message=" + messageOf(missing));
        assertFalse(messageOf(missing).contains("CANARY"), () -> "message=" + messageOf(missing));
    }

    @Test
    @DisplayName("同环境同名 → 拒绝创建，零持久化")
    void createDuplicateNameIsRejected() {
        when(mapper.findByEnvIdAndName(1L, "k8s-prod"))
                .thenReturn(stored(3L, "KUBERNETES", 1L, "k8s-prod", validKubernetesPayload(), true));

        BizException e = assertThrows(BizException.class, () -> service.createConfig(
                request("KUBERNETES", 1L, "k8s-prod", validKubernetesPayload(), true)));

        assertTrue(messageOf(e).contains("名称已存在"), () -> "message=" + messageOf(e));
        verify(mapper, never()).insert(any(ReleaseExecutorConfig.class));
    }

    @Test
    @DisplayName("SSH / DOCKER 的 payload 原样保存，不套用 Kubernetes 校验")
    void createNonKubernetesPayloadIsStoredVerbatim() {
        when(mapper.findByEnvIdAndName(2L, "ssh-a")).thenReturn(null);
        when(mapper.findByEnvIdAndName(2L, "docker-a")).thenReturn(null);
        when(mapper.insert(any(ReleaseExecutorConfig.class))).thenReturn(1);

        service.createConfig(request("SSH", 2L, "ssh-a", "host=192.0.2.12\nuser=deploy", true));
        service.createConfig(request("DOCKER", 2L, "docker-a", "[1, 2, 3]", true));

        ArgumentCaptor<ReleaseExecutorConfig> captor = ArgumentCaptor.forClass(ReleaseExecutorConfig.class);
        verify(mapper, times(2)).insert(captor.capture());
        assertEquals("host=192.0.2.12\nuser=deploy", captor.getAllValues().get(0).getConfigPayload());
        assertEquals("SSH", captor.getAllValues().get(0).getExecutorType());
        assertEquals("[1, 2, 3]", captor.getAllValues().get(1).getConfigPayload());
    }

    // ----- update -----

    @Test
    @DisplayName("更新自身（环境与名字均未变）→ 允许，写入一次")
    void updateItselfIsAllowed() {
        ReleaseExecutorConfig existing = stored(7L, "KUBERNETES", 1L, "k8s-prod",
                validKubernetesPayload(), true);
        when(mapper.selectById(7L)).thenReturn(existing);
        when(mapper.findByEnvIdAndName(1L, "k8s-prod")).thenReturn(existing);
        when(mapper.updateById(any(ReleaseExecutorConfig.class))).thenReturn(1);

        String payload = validKubernetesPayload().replace("\"web\"", "\"web-v2\"");
        ReleaseExecutorConfig updated = service.updateConfig(7L, request("KUBERNETES", 1L, "k8s-prod", payload, true));

        ArgumentCaptor<ReleaseExecutorConfig> captor = ArgumentCaptor.forClass(ReleaseExecutorConfig.class);
        verify(mapper, times(1)).updateById(captor.capture());
        assertEquals(payload, captor.getValue().getConfigPayload());
        assertEquals("k8s-prod", updated.getExecutorName());
    }

    @Test
    @DisplayName("只改环境不改名字 → 仍按 (envId, executorName) 检查并拦截")
    void updateWithOnlyEnvironmentChangedIsChecked() {
        when(mapper.selectById(7L)).thenReturn(
                stored(7L, "KUBERNETES", 1L, "k8s-prod", validKubernetesPayload(), true));
        when(mapper.findByEnvIdAndName(2L, "k8s-prod")).thenReturn(
                stored(12L, "KUBERNETES", 2L, "k8s-prod", validKubernetesPayload(), true));

        BizException e = assertThrows(BizException.class, () -> service.updateConfig(
                7L, request("KUBERNETES", 2L, "k8s-prod", validKubernetesPayload(), true)));

        assertTrue(messageOf(e).contains("名称已存在"), () -> "message=" + messageOf(e));
        verify(mapper, times(1)).findByEnvIdAndName(2L, "k8s-prod");
        verify(mapper, never()).updateById(any(ReleaseExecutorConfig.class));
    }

    @Test
    @DisplayName("改名为同环境其他配置已用的名字 → 拦截，零持久化")
    void updateRejectsNameUsedByAnotherConfiguration() {
        when(mapper.selectById(7L)).thenReturn(
                stored(7L, "KUBERNETES", 1L, "k8s-old", validKubernetesPayload(), true));
        when(mapper.findByEnvIdAndName(1L, "k8s-prod")).thenReturn(
                stored(9L, "KUBERNETES", 1L, "k8s-prod", validKubernetesPayload(), true));

        assertThrows(BizException.class, () -> service.updateConfig(
                7L, request("KUBERNETES", 1L, "k8s-prod", validKubernetesPayload(), true)));

        verify(mapper, never()).updateById(any(ReleaseExecutorConfig.class));
    }

    @Test
    @DisplayName("更新为非法 payload → 拒绝，零持久化（含跨类型改为 KUBERNETES）")
    void updateRejectsInvalidPayload() {
        when(mapper.selectById(7L)).thenReturn(
                stored(7L, "KUBERNETES", 1L, "k8s-prod", validKubernetesPayload(), true));

        BizException e = assertThrows(BizException.class, () -> service.updateConfig(
                7L, request("KUBERNETES", 1L, "k8s-prod", "{\"namespace\": \"prod\"}", true)));

        assertTrue(messageOf(e).contains("kubeconfigPath"), () -> "message=" + messageOf(e));
        verify(mapper, never()).updateById(any(ReleaseExecutorConfig.class));

        when(mapper.selectById(8L)).thenReturn(stored(8L, "SSH", 1L, "ssh-a", "host=x", true));
        assertThrows(BizException.class, () -> service.updateConfig(
                8L, request("KUBERNETES", 1L, "ssh-a", "not-json", true)));
        verify(mapper, never()).updateById(any(ReleaseExecutorConfig.class));
    }

    @Test
    @DisplayName("更新 SSH 配置 → 不套用 Kubernetes 校验，payload 原样写入")
    void updateNonKubernetesPayloadIsStoredVerbatim() {
        ReleaseExecutorConfig existing = stored(4L, "SSH", 1L, "ssh-a", "host=old", true);
        when(mapper.selectById(4L)).thenReturn(existing);
        when(mapper.findByEnvIdAndName(1L, "ssh-a")).thenReturn(existing);
        when(mapper.updateById(any(ReleaseExecutorConfig.class))).thenReturn(1);

        service.updateConfig(4L, request("SSH", 1L, "ssh-a", "not json either", false));

        ArgumentCaptor<ReleaseExecutorConfig> captor = ArgumentCaptor.forClass(ReleaseExecutorConfig.class);
        verify(mapper, times(1)).updateById(captor.capture());
        assertEquals("not json either", captor.getValue().getConfigPayload());
        assertEquals(Boolean.FALSE, captor.getValue().getEnabled());
    }

    // ----- enable / disable -----

    @Test
    @DisplayName("旧配置缺新字段 → 拒绝启用，enabled 不变，且缺项可定位")
    void enableRejectsLegacyConfigurationMissingFields() {
        ReleaseExecutorConfig legacy = stored(5L, "KUBERNETES", 1L, "legacy", "{\"namespace\": \"prod\"}", false);
        when(mapper.selectById(5L)).thenReturn(legacy);

        BizException e = assertThrows(BizException.class, () -> service.toggleEnabled(5L, true));

        String message = messageOf(e);
        assertTrue(message.contains("kubeconfigPath"), () -> "message=" + message);
        assertTrue(message.contains("kubeContext"), () -> "message=" + message);
        assertTrue(message.contains("manifest"), () -> "message=" + message);
        assertEquals(Boolean.FALSE, legacy.getEnabled(), "enabled 状态必须保持不变");
        verify(mapper, never()).updateById(any(ReleaseExecutorConfig.class));
    }

    @Test
    @DisplayName("旧配置 payload 不是 JSON → 拒绝启用，不静默补默认值")
    void enableRejectsLegacyConfigurationWithMalformedJson() {
        ReleaseExecutorConfig legacy = stored(6L, "KUBERNETES", 1L, "legacy", "kubeconfigPath: /x", false);
        when(mapper.selectById(6L)).thenReturn(legacy);

        BizException e = assertThrows(BizException.class, () -> service.toggleEnabled(6L, true));

        assertTrue(messageOf(e).contains("不是有效的 JSON"), () -> "message=" + messageOf(e));
        assertEquals(Boolean.FALSE, legacy.getEnabled(), "enabled 状态必须保持不变");
        verify(mapper, never()).updateById(any(ReleaseExecutorConfig.class));
    }

    @Test
    @DisplayName("合法配置 → 可以启用")
    void enableValidConfigurationIsAllowed() {
        when(mapper.selectById(7L)).thenReturn(
                stored(7L, "KUBERNETES", 1L, "k8s-prod", validKubernetesPayload(), false));
        when(mapper.updateById(any(ReleaseExecutorConfig.class))).thenReturn(1);

        service.toggleEnabled(7L, true);

        ArgumentCaptor<ReleaseExecutorConfig> captor = ArgumentCaptor.forClass(ReleaseExecutorConfig.class);
        verify(mapper, times(1)).updateById(captor.capture());
        assertEquals(Boolean.TRUE, captor.getValue().getEnabled());
    }

    @Test
    @DisplayName("非法旧配置仍可停用，且不受门禁影响")
    void disableIsNeverGated() {
        ReleaseExecutorConfig legacy = stored(5L, "KUBERNETES", 1L, "legacy", "{\"namespace\": \"prod\"}", true);
        when(mapper.selectById(5L)).thenReturn(legacy);
        when(mapper.updateById(any(ReleaseExecutorConfig.class))).thenReturn(1);

        service.toggleEnabled(5L, false);

        ArgumentCaptor<ReleaseExecutorConfig> captor = ArgumentCaptor.forClass(ReleaseExecutorConfig.class);
        verify(mapper, times(1)).updateById(captor.capture());
        assertEquals(Boolean.FALSE, captor.getValue().getEnabled());
    }

    @Test
    @DisplayName("SSH / DOCKER 的启用不受 Kubernetes 门禁影响")
    void enableNonKubernetesIsNotGated() {
        when(mapper.selectById(4L)).thenReturn(stored(4L, "SSH", 1L, "ssh-a", "not json at all", false));
        when(mapper.updateById(any(ReleaseExecutorConfig.class))).thenReturn(1);

        service.toggleEnabled(4L, true);

        ArgumentCaptor<ReleaseExecutorConfig> captor = ArgumentCaptor.forClass(ReleaseExecutorConfig.class);
        verify(mapper, times(1)).updateById(captor.capture());
        assertEquals(Boolean.TRUE, captor.getValue().getEnabled());
    }

    @Test
    @DisplayName("配置不存在 → 抛出且零持久化")
    void toggleMissingConfigurationThrows() {
        when(mapper.selectById(anyLong())).thenReturn(null);

        assertThrows(BizException.class, () -> service.toggleEnabled(99L, true));
        assertThrows(BizException.class, () -> service.toggleEnabled(99L, false));

        verify(mapper, never()).updateById(any(ReleaseExecutorConfig.class));
    }
}
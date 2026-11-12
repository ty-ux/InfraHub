package com.infrahub.release.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.infrahub.release.dto.DockerExecutionConfig;
import com.infrahub.release.dto.KubernetesExecutionConfig;
import com.infrahub.release.entity.ReleaseExecutorConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReleaseExecutorConfigParserTest {

    private ReleaseExecutorConfigParser parser;

    @BeforeEach
    void setUp() {
        parser = new ReleaseExecutorConfigParser(new ObjectMapper());
    }

    private ReleaseExecutorConfig config(String executorType, String payload) {
        ReleaseExecutorConfig entity = new ReleaseExecutorConfig();
        entity.setId(1L);
        entity.setExecutorType(executorType);
        entity.setConfigPayload(payload);
        return entity;
    }

    @Test
    @DisplayName("null 实体返回失败且错误信息固定")
    void parseNullEntityReturnsFailure() {
        ReleaseExecutorConfigParser.ParseResult result = parser.parse(null);
        assertFalse(result.success());
        assertNull(result.config());
        assertEquals("执行器配置不能为空", result.errorMessage());
    }

    @Test
    @DisplayName("executorType 为 null 时返回不支持的执行器类型")
    void parseNullExecutorTypeReturnsUnsupported() {
        ReleaseExecutorConfig entity = config(null, "{}");
        ReleaseExecutorConfigParser.ParseResult result = parser.parse(entity);
        assertFalse(result.success());
        assertEquals("不支持的执行器类型: ", result.errorMessage());
    }

    @Test
    @DisplayName("DOCKER 合法 payload 解析为类型化配置")
    void parseDockerValidPayload() {
        String payload = "{\"image\":\"nginx:1.25\",\"containerName\":\"app-1\","
                + "\"ports\":[\"18080:80\"],\"env\":{\"K\":\"V\"}}";
        ReleaseExecutorConfigParser.ParseResult result = parser.parse(config("DOCKER", payload));
        assertTrue(result.success());
        assertNull(result.errorMessage());
        DockerExecutionConfig docker = assertInstanceOf(DockerExecutionConfig.class, result.config());
        assertEquals("nginx:1.25", docker.getImage());
        assertEquals("app-1", docker.getContainerName());
        assertEquals(List.of("18080:80"), docker.getPorts());
        assertEquals("V", docker.getEnv().get("K"));
    }

    @Test
    @DisplayName("executorType 大小写不敏感（docker → 成功）")
    void parseDockerCaseInsensitiveType() {
        String payload = "{\"image\":\"nginx:1.25\"}";
        ReleaseExecutorConfigParser.ParseResult result = parser.parse(config("docker", payload));
        assertTrue(result.success());
        assertInstanceOf(DockerExecutionConfig.class, result.config());
    }

    @Test
    @DisplayName("DOCKER 空 payload 返回固定错误信息")
    void parseDockerEmptyPayload() {
        ReleaseExecutorConfigParser.ParseResult result = parser.parse(config("DOCKER", null));
        assertFalse(result.success());
        assertEquals("Docker 执行器配置 payload 为空", result.errorMessage());

        ReleaseExecutorConfigParser.ParseResult blank = parser.parse(config("DOCKER", "   "));
        assertEquals("Docker 执行器配置 payload 为空", blank.errorMessage());
    }

    @Test
    @DisplayName("DOCKER 非法 JSON 返回固定错误信息且不泄露 payload/Jackson 信息")
    void parseDockerInvalidJsonDoesNotLeakPayload() {
        String payload = "{broken json, \"x-secret-marker\": \"SENSITIVE-DOCKER-SECRET-7788\"}";
        ReleaseExecutorConfigParser.ParseResult result = parser.parse(config("DOCKER", payload));
        assertFalse(result.success());
        assertEquals("Docker 执行器配置 JSON 解析失败", result.errorMessage());
        assertFalse(result.errorMessage().contains("SENSITIVE-DOCKER-SECRET-7788"));
        assertFalse(result.errorMessage().contains("broken"));
    }

    @Test
    @DisplayName("DOCKER 未知字段被局部忽略而非拒绝")
    void parseDockerIgnoresUnknownProperties() {
        String payload = "{\"image\":\"nginx:1.25\",\"futureField\":\"to-be-ignored\"}";
        ReleaseExecutorConfigParser.ParseResult result = parser.parse(config("DOCKER", payload));
        assertTrue(result.success());
        assertEquals("nginx:1.25", ((DockerExecutionConfig) result.config()).getImage());
    }

    @Test
    @DisplayName("KUBERNETES 合法 payload 解析为类型化配置")
    void parseKubernetesValidPayload() {
        String payload = "{\"namespace\":\"prod\",\"manifestPath\":\"/tmp/app.yaml\",\"kubeContext\":\"ctx-1\"}";
        ReleaseExecutorConfigParser.ParseResult result = parser.parse(config("KUBERNETES", payload));
        assertTrue(result.success());
        KubernetesExecutionConfig k8s = assertInstanceOf(KubernetesExecutionConfig.class, result.config());
        assertEquals("prod", k8s.getNamespace());
        assertEquals("/tmp/app.yaml", k8s.getManifestPath());
        assertEquals("ctx-1", k8s.getKubeContext());
    }

    @Test
    @DisplayName("KUBERNETES 空 payload 返回固定错误信息")
    void parseKubernetesEmptyPayload() {
        ReleaseExecutorConfigParser.ParseResult result = parser.parse(config("KUBERNETES", null));
        assertFalse(result.success());
        assertEquals("Kubernetes 执行器配置 payload 为空", result.errorMessage());
    }

    @Test
    @DisplayName("KUBERNETES 非法 JSON 返回固定错误信息且不泄露 payload")
    void parseKubernetesInvalidJsonDoesNotLeakPayload() {
        String payload = "not-json-with-SENSITIVE-K8S-SECRET-3355";
        ReleaseExecutorConfigParser.ParseResult result = parser.parse(config("KUBERNETES", payload));
        assertFalse(result.success());
        assertEquals("Kubernetes 执行器配置 JSON 解析失败", result.errorMessage());
        assertFalse(result.errorMessage().contains("SENSITIVE-K8S-SECRET-3355"));
    }

    @Test
    @DisplayName("SSH 类型返回 success=true 且 config=null")
    void parseSshReturnsSuccessWithNullConfig() {
        ReleaseExecutorConfigParser.ParseResult result = parser.parse(config("SSH", "anything"));
        assertTrue(result.success());
        assertNull(result.config());
        assertNull(result.errorMessage());
    }

    @Test
    @DisplayName("未知类型返回错误信息且包含规范化类型名、不含 payload")
    void parseUnknownTypeContainsNormalizedType() {
        String payload = "{\"secret\":\"SENSITIVE-PAYLOAD-9900\"}";
        ReleaseExecutorConfigParser.ParseResult result = parser.parse(config("podman", payload));
        assertFalse(result.success());
        assertEquals("不支持的执行器类型: PODMAN", result.errorMessage());
        assertFalse(result.errorMessage().contains("SENSITIVE-PAYLOAD-9900"));
    }

    @Test
    @DisplayName("未知类型不区分大小写规范化后进入错误信息")
    void parseUnknownTypeLowercaseNormalized() {
        ReleaseExecutorConfigParser.ParseResult result = parser.parse(config("k8s-custom", "{}"));
        assertFalse(result.success());
        assertEquals("不支持的执行器类型: K8S-CUSTOM", result.errorMessage());
    }

    @Test
    @DisplayName("JSON 解析失败时敏感字段原文不出现在错误信息中")
    void parseFailureNeverContainsSensitivePayloadFragment() {
        String payload = "{\"password\": \"SUPER-SECRET-PASSWORD-VALUE\", \"image\": ";
        ReleaseExecutorConfigParser.ParseResult result = parser.parse(config("DOCKER", payload));
        assertFalse(result.success());
        assertFalse(result.errorMessage().toLowerCase().contains("password"));
        assertFalse(result.errorMessage().contains("SUPER-SECRET-PASSWORD-VALUE"));
    }

    @Test
    @DisplayName("成功解析时不报错且 errorMessage 为 null")
    void parseSuccessReturnsCleanError() {
        ReleaseExecutorConfigParser.ParseResult result = parser.parse(config("DOCKER", "{\"image\":\"x\"}"));
        assertNotNull(result.config());
        assertNull(result.errorMessage());
    }
}
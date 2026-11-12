package com.infrahub.release.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.infrahub.release.dto.DockerExecutionConfig;
import com.infrahub.release.dto.KubernetesExecutionConfig;
import com.infrahub.release.entity.ReleaseExecutorConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 将 release_executor_config.config_payload JSON 解析为类型化执行器配置 DTO。
 *
 * <p>仅负责 JSON 结构与类型分派，不复制执行器自身的校验逻辑。解析失败不抛出业务异常，
 * 而是返回带脱敏错误信息的 ParseResult，由调用方决定如何处理。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReleaseExecutorConfigParser {

    private static final String FAIL_EMPTY_ENTITY = "执行器配置不能为空";
    private static final String FAIL_DOCKER_PAYLOAD_EMPTY = "Docker 执行器配置 payload 为空";
    private static final String FAIL_DOCKER_JSON = "Docker 执行器配置 JSON 解析失败";
    private static final String FAIL_KUBERNETES_PAYLOAD_EMPTY = "Kubernetes 执行器配置 payload 为空";
    private static final String FAIL_KUBERNETES_JSON = "Kubernetes 执行器配置 JSON 解析失败";
    private static final String FAIL_UNSUPPORTED_TYPE = "不支持的执行器类型: ";

    private final ObjectMapper objectMapper;

    public ParseResult parse(ReleaseExecutorConfig entity) {
        if (entity == null) {
            return ParseResult.failure(FAIL_EMPTY_ENTITY);
        }
        String normalizedType = normalizeType(entity.getExecutorType());
        if (normalizedType.isEmpty()) {
            return ParseResult.failure(FAIL_UNSUPPORTED_TYPE + normalizedType);
        }
        switch (normalizedType) {
            case "DOCKER":
                return parseDocker(entity);
            case "KUBERNETES":
                return parseKubernetes(entity);
            case "SSH":
                return ParseResult.success(null);
            default:
                return ParseResult.failure(FAIL_UNSUPPORTED_TYPE + normalizedType);
        }
    }

    private ParseResult parseDocker(ReleaseExecutorConfig entity) {
        String payload = entity.getConfigPayload();
        if (!StringUtils.hasText(payload)) {
            return ParseResult.failure(FAIL_DOCKER_PAYLOAD_EMPTY);
        }
        try {
            DockerExecutionConfig config = objectMapper.readerFor(DockerExecutionConfig.class)
                    .without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue(payload);
            return ParseResult.success(config);
        } catch (Exception e) {
            logConfigFailure(entity, "DOCKER", e);
            return ParseResult.failure(FAIL_DOCKER_JSON);
        }
    }

    private ParseResult parseKubernetes(ReleaseExecutorConfig entity) {
        String payload = entity.getConfigPayload();
        if (!StringUtils.hasText(payload)) {
            return ParseResult.failure(FAIL_KUBERNETES_PAYLOAD_EMPTY);
        }
        try {
            KubernetesExecutionConfig config = objectMapper.readerFor(KubernetesExecutionConfig.class)
                    .without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue(payload);
            return ParseResult.success(config);
        } catch (Exception e) {
            logConfigFailure(entity, "KUBERNETES", e);
            return ParseResult.failure(FAIL_KUBERNETES_JSON);
        }
    }

    private String normalizeType(String executorType) {
        return executorType == null ? "" : executorType.trim().toUpperCase();
    }

    private void logConfigFailure(ReleaseExecutorConfig entity, String executorType, Exception e) {
        log.warn("执行器配置 JSON 解析失败: configId={}, executorType={}, cause={}",
                entity.getId(), executorType, e.getClass().getSimpleName());
    }

    public record ParseResult(boolean success, Object config, String errorMessage) {

        public static ParseResult success(Object config) {
            return new ParseResult(true, config, null);
        }

        public static ParseResult failure(String errorMessage) {
            return new ParseResult(false, null, errorMessage);
        }
    }
}
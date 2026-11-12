package com.infrahub.release.service.executor.docker;

import com.infrahub.release.dto.DockerExecutionConfig;
import com.infrahub.release.entity.ReleaseExecutorConfig;
import com.infrahub.release.service.executor.ReleaseExecutionContext;
import com.infrahub.release.service.executor.ReleaseExecutor.ValidationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * P1-R D1 收紧验证：DockerReleaseExecutor 只接受类型化 DockerExecutionConfig，
 * 不再用 ObjectMapper.convertValue 把执行器配置实体“硬转换”为 DTO。
 */
class DockerReleaseExecutorTest {

    private DockerReleaseExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new DockerReleaseExecutor(mock(DockerCommandBuilder.class));
    }

    private ReleaseExecutionContext contextWith(Object executorConfig) {
        return ReleaseExecutionContext.builder()
                .order(null)
                .service(null)
                .env(null)
                .version(null)
                .artifact(null)
                .deployConfig(null)
                .executorConfig(executorConfig)
                .hostId(1L)
                .instanceId(2L)
                .build();
    }

    @Test
    @DisplayName("null 上下文 → invalid")
    void validateNullContextIsInvalid() {
        ValidationResult result = executor.validate(null);
        assertFalse(result.isValid());
        assertTrue(result.errorMessage().contains("执行上下文不能为空"));
    }

    @Test
    @DisplayName("config 为 null → invalid")
    void validateNullConfigIsInvalid() {
        ValidationResult result = executor.validate(contextWith(null));
        assertFalse(result.isValid());
        assertTrue(result.errorMessage().contains("Docker 执行器配置不能为空"));
    }

    @Test
    @DisplayName("类型化 DockerExecutionConfig → valid")
    void validateTypedConfigIsValid() {
        DockerExecutionConfig config = DockerExecutionConfig.builder()
                .image("nginx:1.25")
                .containerName("app-1")
                .build();
        ValidationResult result = executor.validate(contextWith(config));
        assertTrue(result.isValid(), () -> "expected valid but was: " + result.errorMessage());
    }

    @Test
    @DisplayName("镜像缺失 → invalid")
    void validateMissingImageIsInvalid() {
        DockerExecutionConfig config = DockerExecutionConfig.builder().containerName("app-1").build();
        ValidationResult result = executor.validate(contextWith(config));
        assertFalse(result.isValid());
        assertTrue(result.errorMessage().contains("Docker 镜像地址不能为空"));
    }

    @Test
    @DisplayName("执行器配置实体（非 DTO）→ invalid，不再走 convertValue 硬转换")
    void validateEntityConfigIsInvalidWithoutConvertValue() {
        ReleaseExecutorConfig entity = new ReleaseExecutorConfig();
        entity.setExecutorType("DOCKER");
        entity.setConfigPayload("{\"image\":\"nginx:1.25\"}");
        entity.setEnvId(1L);

        ValidationResult result = executor.validate(contextWith(entity));
        assertFalse(result.isValid());
        assertTrue(result.errorMessage().contains("无法解析 Docker 执行器配置"));
    }

    @Test
    @DisplayName("config 为其他错误类型对象 → invalid")
    void validateWrongConfigTypeIsInvalid() {
        ValidationResult result = executor.validate(contextWith("not-a-config"));
        assertFalse(result.isValid());
        assertTrue(result.errorMessage().contains("无法解析 Docker 执行器配置"));
    }
}
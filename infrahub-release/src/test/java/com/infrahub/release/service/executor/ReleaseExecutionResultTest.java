package com.infrahub.release.service.executor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReleaseExecutionResultTest {

    @Test
    @DisplayName("failure(String)：startedAt/endedAt 均被设置且为同一时刻")
    void failureSingleArgSetsSameTimestamp() {
        ReleaseExecutionResult result = ReleaseExecutionResult.failure("容器启动失败");
        assertFalse(result.isSuccess());
        assertEquals("容器启动失败", result.getMessage());
        assertNotNull(result.getStartedAt());
        assertNotNull(result.getEndedAt());
        assertSame(result.getStartedAt(), result.getEndedAt());
        assertNull(result.getStdout());
        assertNull(result.getStderr());
    }

    @Test
    @DisplayName("failure(String, stdout, stderr)：时间同时设置且输出保留")
    void failureThreeArgSetsTimestampAndKeepsOutput() {
        ReleaseExecutionResult result = ReleaseExecutionResult.failure("健康检查未通过", "stdout-line", "stderr-line");
        assertFalse(result.isSuccess());
        assertEquals("健康检查未通过", result.getMessage());
        assertNotNull(result.getStartedAt());
        assertNotNull(result.getEndedAt());
        assertSame(result.getStartedAt(), result.getEndedAt());
        assertEquals("stdout-line", result.getStdout());
        assertEquals("stderr-line", result.getStderr());
    }

    @Test
    @DisplayName("success 工厂时间字段保持为 null（不变式不回退）")
    void successFactoryKeepsTimestampsNull() {
        ReleaseExecutionResult result = ReleaseExecutionResult.success("部署成功");
        assertTrue(result.isSuccess());
        assertEquals("部署成功", result.getMessage());
        assertNull(result.getStartedAt());
        assertNull(result.getEndedAt());
    }

    @Test
    @DisplayName("success(String, deployRecordId)：deployRecordId 保留")
    void successWithRecordIdKeepsId() {
        ReleaseExecutionResult result = ReleaseExecutionResult.success("部署成功", 42L);
        assertTrue(result.isSuccess());
        assertEquals(42L, result.getDeployRecordId());
    }

    @Test
    @DisplayName("fullLog：仅 stdout 时只聚合 stdout")
    void fullLogWithStdoutOnly() {
        ReleaseExecutionResult result = ReleaseExecutionResult.builder()
                .success(false).stdout("output-1").build();
        assertEquals("STDOUT:\noutput-1", result.fullLog());
    }

    @Test
    @DisplayName("fullLog：仅 stderr 时只聚合 stderr")
    void fullLogWithStderrOnly() {
        ReleaseExecutionResult result = ReleaseExecutionResult.builder()
                .success(false).stderr("error-1").build();
        assertEquals("STDERR:\nerror-1", result.fullLog());
    }

    @Test
    @DisplayName("fullLog：stdout 与 stderr 都有时按序聚合")
    void fullLogWithBoth() {
        ReleaseExecutionResult result = ReleaseExecutionResult.builder()
                .success(false).stdout("out").stderr("err").build();
        assertEquals("STDOUT:\nout\nSTDERR:\nerr", result.fullLog());
    }

    @Test
    @DisplayName("fullLog：均为空时返回空字符串")
    void fullLogEmptyWhenBothBlank() {
        ReleaseExecutionResult result = ReleaseExecutionResult.builder()
                .success(false).stdout(null).stderr(null).build();
        assertEquals("", result.fullLog());

        ReleaseExecutionResult blank = ReleaseExecutionResult.builder()
                .success(false).stdout("   ").stderr("").build();
        assertEquals("", blank.fullLog());
    }
}
package com.infrahub.release.service;

import com.infrahub.common.error.ErrorCode;
import com.infrahub.common.exception.BizException;
import com.infrahub.release.entity.ReleasePrecheckRecord;
import com.infrahub.release.mapper.ReleasePrecheckRecordMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReleasePrecheckRecordServiceTest {

    private ReleasePrecheckRecordMapper mapper;
    private ReleasePrecheckRecordService service;

    @BeforeEach
    void setUp() {
        mapper = mock(ReleasePrecheckRecordMapper.class);
        service = new ReleasePrecheckRecordService(mapper);
    }

    private ReleasePrecheckRecordService.PrecheckInstanceOutcome ok() {
        return new ReleasePrecheckRecordService.PrecheckInstanceOutcome(true, null);
    }

    private ReleasePrecheckRecordService.PrecheckInstanceOutcome fail(String reason) {
        return new ReleasePrecheckRecordService.PrecheckInstanceOutcome(false, reason);
    }

    private ReleasePrecheckRecord captureInserted(int times) {
        ArgumentCaptor<ReleasePrecheckRecord> captor = ArgumentCaptor.forClass(ReleasePrecheckRecord.class);
        verify(mapper, Mockito.times(times)).insert(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("全部成功 → success=true 且 summary 计数正确，仅插入一次")
    void allSuccessProducesSuccessRecord() {
        service.recordPrecheck("RELEASE_ORDER", 1L, 2L, 3L, List.of(ok(), ok()));

        ReleasePrecheckRecord record = captureInserted(1);
        assertEquals("RELEASE_ORDER", record.getBizType());
        assertEquals(1L, record.getBizId());
        assertEquals(2L, record.getEnvId());
        assertEquals(3L, record.getServiceId());
        assertTrue(record.getSuccess());
        String summary = record.getSummary();
        assertNotNull(summary);
        assertTrue(summary.contains("2/2 通过"), () -> "expected counts in summary but was: " + summary);
        assertTrue(summary.contains("失败 0"));
    }

    @Test
    @DisplayName("部分失败 → success=false 且 summary 含首条失败原因")
    void partialFailureProducesFailureRecord() {
        service.recordPrecheck("RELEASE_ORDER", 1L, 2L, 3L,
                List.of(ok(), fail("端口 8080 被占用"), ok()));

        ReleasePrecheckRecord record = captureInserted(1);
        assertFalse(record.getSuccess());
        String summary = record.getSummary();
        assertTrue(summary.contains("2/3 通过"));
        assertTrue(summary.contains("端口 8080 被占用"));
    }

    @Test
    @DisplayName("ROLLBACK_ORDER 业务类型被接受")
    void rollbackOrderBizTypeAccepted() {
        service.recordPrecheck("ROLLBACK_ORDER", 9L, 4L, 5L, List.of(fail("OOM")));
        ReleasePrecheckRecord record = captureInserted(1);
        assertEquals("ROLLBACK_ORDER", record.getBizType());
        assertFalse(record.getSuccess());
        assertTrue(record.getSummary().contains("OOM"));
    }

    @Test
    @DisplayName("空结果列表 → BizException 且不插入")
    void emptyResultsRejected() {
        BizException e = assertThrows(BizException.class,
                () -> service.recordPrecheck("RELEASE_ORDER", 1L, 2L, 3L, Collections.emptyList()));
        assertEquals(ErrorCode.VALIDATION_ERROR, e.getErrorCode());
        verify(mapper, never()).insert(Mockito.any(ReleasePrecheckRecord.class));
    }

    @Test
    @DisplayName("null 结果列表 → BizException 且不插入")
    void nullResultsRejected() {
        assertThrows(BizException.class,
                () -> service.recordPrecheck("RELEASE_ORDER", 1L, 2L, 3L, null));
        verify(mapper, never()).insert(Mockito.any(ReleasePrecheckRecord.class));
    }

    @Test
    @DisplayName("不支持的业务类型 → BizException 且不插入")
    void unsupportedBizTypeRejected() {
        BizException e = assertThrows(BizException.class,
                () -> service.recordPrecheck("CANARY_ORDER", 1L, 2L, 3L, List.of(ok())));
        assertEquals(ErrorCode.VALIDATION_ERROR, e.getErrorCode());
        verify(mapper, never()).insert(Mockito.any(ReleasePrecheckRecord.class));
    }

    @Test
    @DisplayName("失败原因中的敏感键值被脱敏，仅遮蔽值保留结构")
    void failureReasonSensitiveValuesMasked() {
        service.recordPrecheck("RELEASE_ORDER", 1L, 2L, 3L, List.of(fail(
                "认证失败Authorization: Bearer real-token-abc123 password=real-pass-456 secret=priv-789")));

        ReleasePrecheckRecord record = captureInserted(1);
        String summary = record.getSummary();
        assertFalse(summary.contains("real-token-abc123"));
        assertFalse(summary.contains("real-pass-456"));
        assertFalse(summary.contains("priv-789"));
        assertTrue(summary.contains("***"));
    }

    @Test
    @DisplayName("多键值敏感形式（token/privateKey/private_key/passwd）全部脱敏")
    void multipleSensitiveKeyFormsMasked() {
        service.recordPrecheck("RELEASE_ORDER", 1L, 2L, 3L, List.of(fail(
                "token=TKN-SECRET-1 privateKey=KEY-SECRET-2 private_key=PK-SECRET-3 passwd=PW-SECRET-4")));

        String summary = captureInserted(1).getSummary();
        assertFalse(summary.contains("TKN-SECRET-1"));
        assertFalse(summary.contains("KEY-SECRET-2"));
        assertFalse(summary.contains("PK-SECRET-3"));
        assertFalse(summary.contains("PW-SECRET-4"));
        assertTrue(summary.toLowerCase().contains("token=***"));
    }

    @Test
    @DisplayName("summary 压缩空行为单行且不超过 500 字符")
    void summaryCollapsedToSingleLineUnder500() {
        String longReason = "实例启动失败\n  原因：端口冲突\n"
                + IntStream.range(0, 30).mapToObj(i -> "重复片段-" + i).collect(Collectors.joining(","));
        service.recordPrecheck("RELEASE_ORDER", 1L, 2L, 3L, List.of(fail(longReason), fail("另一台失败")));

        String summary = captureInserted(1).getSummary();
        assertNotNull(summary);
        assertTrue(summary.length() <= 500, () -> "summary length = " + summary.length());
        assertFalse(summary.contains("\n"));
        assertFalse(summary.contains("\r"));
        assertFalse(summary.contains("\t"));
    }

    @Test
    @DisplayName("failureReason 为 null 的失败项也能汇总，不产生异常")
    void nullFailureReasonHandled() {
        service.recordPrecheck("ROLLBACK_ORDER", 5L, 6L, 7L, List.of(fail(null), ok()));
        ReleasePrecheckRecord record = captureInserted(1);
        assertFalse(record.getSuccess());
        String summary = record.getSummary();
        assertTrue(summary.contains("1/2 通过"));
        assertTrue(summary.contains("失败原因: 无"));
    }

    @Test
    @DisplayName("成功后置于 30 分钟准入窗口：bizId/envId/success=true 均已写入")
    void successRecordCarriesAdmissionQueryFields() {
        service.recordPrecheck("RELEASE_ORDER", 101L, 202L, 303L, List.of(ok()));
        ReleasePrecheckRecord record = captureInserted(1);
        assertEquals("RELEASE_ORDER", record.getBizType());
        assertEquals(101L, record.getBizId());
        assertEquals(202L, record.getEnvId());
        assertEquals(Boolean.TRUE, record.getSuccess());
        assertTrue(record.getSummary().contains("1/1 通过"));
    }
}
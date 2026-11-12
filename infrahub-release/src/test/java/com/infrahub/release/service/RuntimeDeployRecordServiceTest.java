package com.infrahub.release.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.infrahub.release.entity.RuntimeDeployRecord;
import com.infrahub.release.mapper.RuntimeDeployRecordMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 部署记录证据纯单元测试：不连接真实 DB / SSH / Docker / Kubernetes。
 *
 * <p>覆盖两点：
 * <ol>
 *   <li>执行前的 RUNNING 落库与终态落库必须是 REQUIRES_NEW 独立短事务——否则外部动作已经发生时，
 *       证据会跟着外围长事务一起回滚消失（结构性守卫，避免有人把注解删掉）。</li>
 *   <li>执行异常兜底：仍停在 RUNNING 的记录必须变成 FAILED，且 endedAt 非空、日志非空，
 *       原始失败原因只追加不覆盖。</li>
 * </ol>
 */
class RuntimeDeployRecordServiceTest {

    private static final Long RELEASE_ORDER_ID = 10L;

    private RuntimeDeployRecordMapper mapper;
    private RuntimeDeployRecordService service;

    @BeforeEach
    void setUp() {
        // 注册实体 TableInfo，使真实条件链在无 MyBatis 容器时也能把 lambda 解析成列名
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                RuntimeDeployRecord.class);

        mapper = mock(RuntimeDeployRecordMapper.class);
        // 真实 lambdaQuery() 会经 getEntityClass() 从 mapper 代理上推断实体类型，而纯单测里的
        // baseMapper 是 mock、不是 MyBatis 代理。这里只固定“实体类型”这一个接缝：
        // 查询链构造、条件拼装、失败记录收敛仍全部跑真实实现，只有最终 SQL 落到被 stub 的 mapper 上。
        service = new RuntimeDeployRecordService(org.mockito.Mockito.mock(RuntimeExecutionUnknownService.class), org.mockito.Mockito.mock(RuntimeDeployRecordInstanceService.class)) {
            @Override
            public Class<RuntimeDeployRecord> getEntityClass() {
                return RuntimeDeployRecord.class;
            }
        };
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
    }

    private RuntimeDeployRecord runningRecord(String deployLog) {
        RuntimeDeployRecord record = new RuntimeDeployRecord();
        record.setId(1L);
        record.setReleaseOrderId(RELEASE_ORDER_ID);
        record.setDeployStatus("RUNNING");
        record.setDeployLog(deployLog);
        record.setStartedAt(OffsetDateTime.now().minusMinutes(3));
        return record;
    }

    @Test
    @DisplayName("结构性守卫：startRunning 与 finish 必须是 REQUIRES_NEW，避免 RUNNING/终态随外围事务回滚消失")
    void runningAndFinalWritesMustUseRequiresNew() throws Exception {
        Method startRunning = RuntimeDeployRecordService.class
                .getMethod("startRunning", RuntimeDeployRecord.class);
        Transactional startRunningTx = startRunning.getAnnotation(Transactional.class);
        assertNotNull(startRunningTx, "startRunning 必须带 @Transactional");
        assertEquals(Propagation.REQUIRES_NEW, startRunningTx.propagation(),
                "RUNNING 记录必须在调用外部执行器之前独立提交");

        Method finish = RuntimeDeployRecordService.class.getMethod("finish", RuntimeDeployRecord.class);
        Transactional finishTx = finish.getAnnotation(Transactional.class);
        assertNotNull(finishTx, "finish 必须带 @Transactional");
        assertEquals(Propagation.REQUIRES_NEW, finishTx.propagation(),
                "终态证据必须在外部动作发生后独立提交");
    }

    @Test
    @DisplayName("startRunning：记录经 insert 落库，状态为 RUNNING")
    void startRunningInsertsRunningRecord() {
        RuntimeDeployRecord record = runningRecord(null);
        when(mapper.insert(any(RuntimeDeployRecord.class))).thenReturn(1);

        service.startRunning(record);

        verify(mapper).insert(record);
        assertEquals("RUNNING", record.getDeployStatus());
    }

    @Test
    @DisplayName("finish：记录经 updateById 落库")
    void finishUpdatesRecord() {
        RuntimeDeployRecord record = runningRecord("done");
        when(mapper.update(any(RuntimeDeployRecord.class), any())).thenReturn(1);

        service.finish(record);

        verify(mapper).update(eq(record), any());
    }

    @Test
    @DisplayName("失败兜底：RUNNING 记录收敛为 FAILED，补齐 endedAt 与日志（原因为空时用兜底文案）")
    void failRunningRecordsFillsStatusTimeAndLog() {
        RuntimeDeployRecord record = runningRecord(null);
        when(mapper.selectList(any())).thenReturn(List.of(record));
        when(mapper.markFailedIfRunning(any(), any())).thenReturn(1);

        int patched = service.failRunningRecordsByReleaseOrderId(RELEASE_ORDER_ID, null);

        assertEquals(1, patched);
        assertEquals("FAILED", record.getDeployStatus());
        assertNotNull(record.getStartedAt(), "startedAt 必须保留");
        assertNotNull(record.getEndedAt(), "endedAt 必须补齐");
        assertEquals("发布执行中断，未返回详细日志", record.getDeployLog(), "日志必须非空");
        verify(mapper, times(1)).markFailedIfRunning(eq(record.getId()), any());
    }

    @Test
    @DisplayName("失败兜底：已有原始失败原因只追加不覆盖")
    void failRunningRecordsAppendsWithoutOverwritingOriginalLog() {
        RuntimeDeployRecord record = runningRecord("kubectl apply 超时");
        when(mapper.selectList(any())).thenReturn(List.of(record));
        when(mapper.markFailedIfRunning(any(), any())).thenReturn(1);

        service.failRunningRecordsByReleaseOrderId(RELEASE_ORDER_ID, "发布执行异常: IllegalStateException - 连接中断");

        String log = record.getDeployLog();
        assertTrue(log.contains("kubectl apply 超时"), "原始失败原因必须保留");
        assertTrue(log.contains("连接中断"), "外围异常原因必须追加");
        assertNotNull(record.getEndedAt());
        assertFalse(log.isBlank());
    }

    @Test
    @DisplayName("失败兜底：已有原因的记录不被重复追加同一原因")
    void failRunningRecordsDoesNotDuplicateSameReason() {
        RuntimeDeployRecord record = runningRecord("连接中断");
        when(mapper.selectList(any())).thenReturn(List.of(record));
        when(mapper.markFailedIfRunning(any(), any())).thenReturn(1);

        service.failRunningRecordsByReleaseOrderId(RELEASE_ORDER_ID, "连接中断");

        assertEquals("连接中断", record.getDeployLog());
    }

    @Test
    @DisplayName("失败兜底：已有 endedAt 不被改写")
    void failRunningRecordsKeepsExistingEndedAt() {
        OffsetDateTime originalEndedAt = OffsetDateTime.now().minusMinutes(1);
        RuntimeDeployRecord record = runningRecord("原始日志");
        record.setEndedAt(originalEndedAt);
        when(mapper.selectList(any())).thenReturn(List.of(record));
        when(mapper.markFailedIfRunning(any(), any())).thenReturn(1);

        service.failRunningRecordsByReleaseOrderId(RELEASE_ORDER_ID, "原因");

        assertSame(originalEndedAt, record.getEndedAt());
    }

    @Test
    @DisplayName("失败兜底：没有 RUNNING 记录时不写库，返回 0")
    void failRunningRecordsNoOpWhenNothingRunning() {
        when(mapper.selectList(any())).thenReturn(List.of());

        assertEquals(0, service.failRunningRecordsByReleaseOrderId(RELEASE_ORDER_ID, "原因"));

        verify(mapper, never()).updateById(any(RuntimeDeployRecord.class));
    }
}
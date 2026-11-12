package com.infrahub.release.service;

import com.infrahub.release.config.HostInstanceCollectionProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 采集调度器。
 *
 * <p>钉住四件事：不重入、不因单轮异常而停摆、停机有界、首跑不在启动瞬间。
 * 第三点尤其重要——{@code ScheduledExecutorService} 的任务一旦抛出异常就会被静默取消，
 * 如果没有兜住，采集会悄悄停掉而没有任何提示。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class HostInstanceCollectionSchedulerTest {

    @Mock
    private HostInstanceCollectionService collectionService;

    private HostInstanceCollectionScheduler scheduler;

    @AfterEach
    void tearDown() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }

    @Test
    @DisplayName("上一轮还没结束：本轮直接跳过，不并发、不堆积")
    void reentryIsPreventedWhileARoundIsRunning() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            release.await();
            return null;
        }).when(collectionService).collectOnce();
        scheduler = newScheduler();

        Thread first = new Thread(scheduler::runOnce, "test-round-1");
        first.start();
        assertThat(entered.await(5, TimeUnit.SECONDS)).as("第一轮应已进入采集").isTrue();

        scheduler.runOnce();

        release.countDown();
        first.join(TimeUnit.SECONDS.toMillis(5));
        verify(collectionService, times(1)).collectOnce();
    }

    @Test
    @DisplayName("单轮抛异常被兜住：不外抛，且下一轮照常执行")
    void exceptionInOneRoundDoesNotStopTheScheduler() {
        doThrow(new IllegalStateException("boom")).when(collectionService).collectOnce();
        scheduler = newScheduler();

        assertThatCode(scheduler::runOnce)
                .as("异常必须在本方法内兜住，否则调度任务会被静默取消，采集无声停掉")
                .doesNotThrowAnyException();
        verify(collectionService, times(1)).collectOnce();

        scheduler.runOnce();

        verify(collectionService, times(2)).collectOnce();
    }

    @Test
    @DisplayName("停机之后再触发：不再执行任何一轮")
    void roundIsSkippedAfterShutdown() {
        scheduler = newScheduler();
        scheduler.shutdown();

        scheduler.runOnce();

        verify(collectionService, never()).collectOnce();
    }

    @Test
    @DisplayName("停机是有界的：即使一轮卡住也按时返回，不会永久阻塞关机")
    void shutdownIsBoundedEvenIfARoundIsStuck() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            release.await();
            return null;
        }).when(collectionService).collectOnce();
        HostInstanceCollectionProperties properties = new HostInstanceCollectionProperties();
        properties.setSingleTargetTimeoutMs(50L);
        scheduler = new HostInstanceCollectionScheduler(collectionService, properties, enabledControl(properties));

        Thread stuck = new Thread(scheduler::runOnce, "test-round-stuck");
        stuck.start();
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

        long startedAt = System.nanoTime();
        scheduler.shutdown();
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;

        assertThat(elapsedMs)
                .as("有界等待时间取自配置，实测 %dms", elapsedMs)
                .isLessThan(3_000L);

        release.countDown();
        stuck.join(TimeUnit.SECONDS.toMillis(5));
    }

    @Test
    @DisplayName("首跑不在启动瞬间，而是等满一个周期；执行线程是指名道姓的守护线程")
    void firstRoundRunsAfterOnePeriodOnDedicatedDaemonThread() throws Exception {
        AtomicReference<Thread> executingThread = new AtomicReference<>();
        doAnswer(invocation -> {
            executingThread.set(Thread.currentThread());
            return null;
        }).when(collectionService).collectOnce();
        scheduler = newScheduler(200L);
        scheduler.start();

        assertThat(executingThread.get())
                .as("应用刚起来就发起探测会拖慢启动，也可能在配置还没就绪时误判")
                .isNull();

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (executingThread.get() == null && System.nanoTime() < deadline) {
            Thread.sleep(20L);
        }

        assertThat(executingThread.get()).as("一个周期后应已执行首轮").isNotNull();
        assertThat(executingThread.get().getName()).isEqualTo("host-instance-collection");
        assertThat(executingThread.get().isDaemon())
                .as("非守护线程会让应用关不掉").isTrue();
    }

    private HostInstanceCollectionControlService enabledControl(HostInstanceCollectionProperties properties) {
        HostInstanceCollectionControlService control = new HostInstanceCollectionControlService(properties, false);
        control.setEnabled(true);
        return control;
    }

    @Test
    void disabledSkipsAndRuntimeToggleTakesEffectWithoutRestart() {
        HostInstanceCollectionProperties properties = new HostInstanceCollectionProperties();
        HostInstanceCollectionControlService control = new HostInstanceCollectionControlService(properties, false);
        scheduler = new HostInstanceCollectionScheduler(collectionService, properties, control);
        scheduler.runOnce();
        verify(collectionService, never()).collectOnce();
        control.setEnabled(true);
        scheduler.runOnce();
        verify(collectionService, times(1)).collectOnce();
        control.setEnabled(false);
        scheduler.runOnce();
        verify(collectionService, times(1)).collectOnce();
    }

    private HostInstanceCollectionScheduler newScheduler() {
        return newScheduler(1000L);
    }

    private HostInstanceCollectionScheduler newScheduler(long periodMs) {
        HostInstanceCollectionProperties properties = new HostInstanceCollectionProperties();
        properties.setPeriodMs(periodMs);
        properties.setSingleTargetTimeoutMs(Math.max(1L, periodMs / 2));
        properties.setStaleThresholdMs(periodMs);
        return new HostInstanceCollectionScheduler(collectionService, properties, enabledControl(properties));
    }
}
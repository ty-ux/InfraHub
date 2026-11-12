package com.infrahub.release.service.executor.kubernetes;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-process behaviour of {@link KubectlProcessRunner} against synthetic child processes.
 * No kubectl binary is involved.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class KubectlProcessRunnerTest {

    /** Reclamation is allowed on top of the execution deadline; see {@link KubectlProcessRunner#CLEANUP_GRACE}. */
    private static final long CLEANUP_GRACE_MILLIS = KubectlProcessRunner.CLEANUP_GRACE.toMillis();

    /** Scheduling slack only; must stay small compared to the bounds it qualifies. */
    private static final long SCHEDULING_TOLERANCE_MILLIS = 1500L;

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("无输出挂起的子进程在超时后于有界时间内结束且被回收")
    void hangingCommandTimesOutWithinBound() throws Exception {
        Path pidFile = tempDir.resolve("hang-pid.txt");

        long start = System.nanoTime();
        KubectlProcessRunner.CommandResult result = KubectlProcessRunner.run(
                SyntheticProcessFixture.command("hang-pid:" + pidFile), null, Duration.ofSeconds(1));
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        long pid = SyntheticProcessFixture.awaitPidFile(pidFile, 30_000);

        assertFalse(result.processExited());
        assertTrue(result.timedOut());
        assertFalse(result.success());
        assertTrue(result.stdout().isEmpty(), () -> "unexpected stdout: " + result.stdout());
        assertTrue(elapsedMillis < 1_000 + CLEANUP_GRACE_MILLIS + SCHEDULING_TOLERANCE_MILLIS,
                () -> "timeout was not bounded, elapsed=" + elapsedMillis + "ms");
        SyntheticProcessFixture.awaitProcessGone(pid, 10_000);
        assertFalse(result.notes().stream().anyMatch(note -> note.contains("still alive")),
                () -> "reclamation was reported as incomplete: " + result.notes());
    }

    @Test
    @DisplayName("超时后仍保留超时前已取得的 stdout/stderr")
    void timeoutKeepsPartialOutput() throws Exception {
        KubectlProcessRunner.CommandResult result = KubectlProcessRunner.run(
                SyntheticProcessFixture.command("print-then-hang"), null, Duration.ofSeconds(3));

        assertTrue(result.timedOut());
        assertTrue(result.stdout().contains("EARLY-OUT"), () -> "stdout=" + result.stdout());
        assertTrue(result.stderr().contains("EARLY-ERR"), () -> "stderr=" + result.stderr());
    }

    @Test
    @DisplayName("stdout/stderr 同时大量输出时不死锁")
    void concurrentStreamsDoNotDeadlock() throws Exception {
        KubectlProcessRunner.CommandResult result =
                KubectlProcessRunner.run(SyntheticProcessFixture.command("burst"), null, Duration.ofSeconds(60));

        assertFalse(result.timedOut());
        assertTrue(result.success(), () -> "stderr=" + result.stderr());
        assertTrue(result.stdout().endsWith(SyntheticProcessFixture.BURST_DONE), "child did not finish");
        assertEquals(SyntheticProcessFixture.BURST_BYTES,
                result.stdout().length() - SyntheticProcessFixture.BURST_DONE.length());
        assertEquals(SyntheticProcessFixture.BURST_BYTES, result.stderr().length());
    }

    @Test
    @DisplayName("无输入时关闭子进程 stdin，等待 EOF 的子进程可正常退出")
    void stdinIsClosedWithoutInput() throws Exception {
        KubectlProcessRunner.CommandResult result =
                KubectlProcessRunner.run(SyntheticProcessFixture.command("stdin-eof"), null, Duration.ofSeconds(30));

        assertFalse(result.timedOut(), "child kept waiting for stdin EOF");
        assertTrue(result.success(), () -> "stderr=" + result.stderr());
        assertTrue(result.stdout().contains("STDIN_BYTES=0"), () -> "stdout=" + result.stdout());
    }

    @Test
    @DisplayName("有输入时以 UTF-8 编码写入")
    void stdinContentIsWrittenAsUtf8() throws Exception {
        String content = "kind: ConfigMap\ndata:\n  value: 中文值\n";

        KubectlProcessRunner.CommandResult result = KubectlProcessRunner.run(
                SyntheticProcessFixture.command("echo-stdin"), content, Duration.ofSeconds(30));

        assertTrue(result.success(), () -> "stderr=" + result.stderr());
        assertEquals("STDIN:" + content, result.stdout());
    }

    @Test
    @DisplayName("非零退出保留 stdout/stderr 与 exit code")
    void nonZeroExitKeepsStreams() throws Exception {
        KubectlProcessRunner.CommandResult result =
                KubectlProcessRunner.run(SyntheticProcessFixture.command("fail:3:ERR-OK"), null, Duration.ofSeconds(30));

        assertTrue(result.processExited());
        assertFalse(result.timedOut());
        assertFalse(result.success());
        assertEquals(3, result.exitCode());
        assertTrue(result.stdout().contains("OUT-OK"), () -> "stdout=" + result.stdout());
        assertTrue(result.stderr().contains("ERR-OK"), () -> "stderr=" + result.stderr());
    }

    @Test
    @DisplayName("线程中断：终止子进程并抛出 InterruptedException")
    void interruptionTerminatesProcessAndRethrows() throws Exception {
        Path pidFile = tempDir.resolve("pid.txt");
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        Thread runnerThread = new Thread(() -> {
            try {
                KubectlProcessRunner.run(SyntheticProcessFixture.command("hang-pid:" + pidFile), null,
                        Duration.ofSeconds(120));
            } catch (Throwable t) {
                thrown.set(t);
            }
        }, "runner-interrupt-test");
        runnerThread.setDaemon(true);
        runnerThread.start();

        long pid = SyntheticProcessFixture.awaitPidFile(pidFile, 30_000);
        Thread.sleep(300L);
        runnerThread.interrupt();
        runnerThread.join(10_000);

        assertFalse(runnerThread.isAlive(), "runner thread did not return after interruption");
        assertInstanceOf(InterruptedException.class, thrown.get());
        SyntheticProcessFixture.awaitProcessGone(pid, 10_000);
    }

    @Test
    @DisplayName("子进程不读取 stdin：大量输入的写入阻塞受同一 deadline 约束，超时后有界失败并回收")
    void blockedStdinWriteIsBoundedByTheSameDeadline() throws Exception {
        Path pidFile = tempDir.resolve("blocked-stdin-pid.txt");
        String largeStdin = "x".repeat(4 * 1024 * 1024);

        long start = System.nanoTime();
        KubectlProcessRunner.CommandResult result = KubectlProcessRunner.run(
                SyntheticProcessFixture.command("hang-pid:" + pidFile), largeStdin, Duration.ofSeconds(1));
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        long pid = SyntheticProcessFixture.awaitPidFile(pidFile, 30_000);

        assertFalse(result.success());
        assertTrue(result.timedOut(), () -> "failureReason=" + result.failureReason());
        assertTrue(elapsedMillis < 1_000 + CLEANUP_GRACE_MILLIS + SCHEDULING_TOLERANCE_MILLIS,
                () -> "a blocked stdin write was not bounded, elapsed=" + elapsedMillis + "ms");
        SyntheticProcessFixture.awaitProcessGone(pid, 10_000);
    }

    @Test
    @DisplayName("子进程关闭 stdin：写入失败不返回成功")
    void stdinWriteFailureIsNotReportedAsSuccess() throws Exception {
        KubectlProcessRunner.CommandResult result = KubectlProcessRunner.run(
                SyntheticProcessFixture.command("stdin-closed"), "y".repeat(1024 * 1024), Duration.ofSeconds(30));

        assertFalse(result.success(), "a failed stdin delivery was reported as success");
        assertTrue(result.processExited(), "child did not exit");
        assertFalse(result.timedOut());
        assertEquals(0, result.exitCode());
        assertNotNull(result.failureReason());
        assertTrue(result.failureReason().contains("stdin delivery failed"),
                () -> "failureReason=" + result.failureReason());
    }

    @Test
    @DisplayName("stdout 读取失败：返回失败并保留已取得的输出")
    void stdoutReadFailureIsNotReportedAsSuccess() throws Exception {
        KubectlProcessRunner.CommandResult result = KubectlProcessRunner.run(
                SyntheticProcessFixture.command("print:PART"), null, Duration.ofSeconds(30), failingAfterFirstRead());

        assertFalse(result.success(), "a read failure was reported as success");
        assertFalse(result.timedOut());
        assertNotNull(result.failureReason());
        assertTrue(result.failureReason().contains("stdout read failed"),
                () -> "failureReason=" + result.failureReason());
        assertTrue(result.stdout().contains("PART"), () -> "stdout=" + result.stdout());
    }

    @Test
    @DisplayName("任务以未捕获异常结束（exit code 0）：该任务不算正常完成，结果必须失败")
    void uncaughtTaskFailureIsNotReportedAsSuccess() throws Exception {
        KubectlProcessRunner.CommandResult result = KubectlProcessRunner.run(
                SyntheticProcessFixture.command("print:PART"), null, Duration.ofSeconds(30),
                failingWithRuntimeExceptionAfterFirstRead());

        assertFalse(result.success(), "a task that ended by throwing was treated as a completed one");
        assertFalse(result.timedOut(), "the process exited and every task ended, so this is not a timeout");
        assertTrue(result.processExited());
        assertEquals(0, result.exitCode(), "the child exit code alone must not decide success");
        assertNotNull(result.failureReason());
        assertTrue(result.failureReason().contains("stdout read failed"),
                () -> "failureReason=" + result.failureReason());
        assertTrue(result.failureReason().contains("IllegalStateException"),
                () -> "failureReason=" + result.failureReason());
        assertTrue(result.stdout().contains("PART"), () -> "stdout=" + result.stdout());
    }

    @Test
    @DisplayName("进入回收前已有待处理中断：不返回成功结果，抛出 InterruptedException 且保留标志")
    void pendingInterruptAtReclamationEntryIsNotTurnedIntoAResult() throws Exception {
        CountDownLatch atReclamationEntry = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        AtomicInteger barrierInterrupts = new AtomicInteger();
        KubectlProcessRunner.ReclamationBarrier barrier = () -> {
            atReclamationEntry.countDown();
            try {
                proceed.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                // the test interrupts the runner exactly here, which is the window under test: the
                // main path is over and reclamation has not inspected the interrupt state yet
                Thread.currentThread().interrupt();
                barrierInterrupts.incrementAndGet();
            }
        };

        AtomicReference<Throwable> thrown = new AtomicReference<>();
        AtomicReference<KubectlProcessRunner.CommandResult> result = new AtomicReference<>();
        AtomicBoolean interruptFlagRestored = new AtomicBoolean();
        Thread runnerThread = new Thread(() -> {
            try {
                result.set(KubectlProcessRunner.run(SyntheticProcessFixture.command("print:PART"), null,
                        Duration.ofSeconds(30), UnaryOperator.identity(), barrier));
            } catch (Throwable t) {
                thrown.set(t);
            }
            interruptFlagRestored.set(Thread.currentThread().isInterrupted());
        }, "runner-pending-interrupt-test");
        runnerThread.setDaemon(true);

        long start = System.nanoTime();
        try {
            runnerThread.start();
            assertTrue(atReclamationEntry.await(30, TimeUnit.SECONDS), "runner never reached reclamation");
            runnerThread.interrupt();
            runnerThread.join(15_000);
        } finally {
            // never leave the barrier waiting when an assertion above fails
            proceed.countDown();
        }
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertFalse(runnerThread.isAlive(), "runner thread did not return after a pending interrupt");
        assertInstanceOf(InterruptedException.class, thrown.get());
        assertTrue(thrown.get().getMessage().contains("already pending"),
                () -> "unexpected message: " + thrown.get().getMessage());
        assertTrue(interruptFlagRestored.get(), "the interrupt flag must survive reclamation");
        assertEquals(1, barrierInterrupts.get(), "the pending interrupt must be the one observed at the boundary");
        assertNull(result.get(), "a stopped command must not produce a result");
        assertTrue(elapsedMillis < CLEANUP_GRACE_MILLIS + SCHEDULING_TOLERANCE_MILLIS,
                () -> "reclamation was not bounded, elapsed=" + elapsedMillis + "ms");
    }

    @Test
    @DisplayName("清理等待期间中断：回收进入等待后才中断，抛出 InterruptedException 且保留标志")
    void interruptWhileReclamationIsWaitingIsPropagated() throws Exception {
        InterruptIgnoringReader blocking = new InterruptIgnoringReader();
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        AtomicReference<KubectlProcessRunner.CommandResult> result = new AtomicReference<>();
        AtomicBoolean interruptFlagRestored = new AtomicBoolean();

        Thread runnerThread = new Thread(() -> {
            try {
                // the zero deadline is already spent, so the runner is reclaiming from the start
                // while the interrupting tasks keep reclamation waiting
                result.set(KubectlProcessRunner.run(SyntheticProcessFixture.command("print:PART"), null,
                        Duration.ZERO, blocking));
            } catch (Throwable t) {
                thrown.set(t);
            }
            interruptFlagRestored.set(Thread.currentThread().isInterrupted());
        }, "runner-cleanup-interrupt-test");
        runnerThread.setDaemon(true);

        long start = System.nanoTime();
        try {
            runnerThread.start();
            // the stream pool can only interrupt a blocked task once reclamation waits for it, so
            // this is a reclamation event and not a guess about how long the runner has been running
            assertTrue(blocking.awaitPoolInterrupt(30_000), "reclamation never waited for the stream tasks");
            runnerThread.interrupt();
            runnerThread.join(15_000);
        } finally {
            // never leak the blocked tasks when an assertion above fails
            blocking.release();
        }
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertFalse(runnerThread.isAlive(), "runner thread did not return after a cleanup interrupt");
        assertInstanceOf(InterruptedException.class, thrown.get());
        assertTrue(thrown.get().getMessage().contains("cleanup"),
                () -> "unexpected message: " + thrown.get().getMessage());
        assertTrue(interruptFlagRestored.get(), "the interrupt flag must survive reclamation");
        assertNull(result.get(), "a stopped command must not produce a result");
        assertTrue(elapsedMillis < CLEANUP_GRACE_MILLIS + SCHEDULING_TOLERANCE_MILLIS,
                () -> "reclamation was not bounded, elapsed=" + elapsedMillis + "ms");
    }

    @Test
    @DisplayName("stderr 读取失败：返回失败并保留已取得的输出")
    void stderrReadFailureIsNotReportedAsSuccess() throws Exception {
        KubectlProcessRunner.CommandResult result = KubectlProcessRunner.run(
                SyntheticProcessFixture.command("print-then-hang"), null, Duration.ofSeconds(1), failingAfterFirstRead());

        assertFalse(result.success());
        assertNotNull(result.failureReason());
        assertTrue(result.failureReason().contains("stderr read failed")
                        || result.failureReason().contains("stdout read failed"),
                () -> "failureReason=" + result.failureReason());
    }

    @Test
    @DisplayName("进程已退出但输出未读完：仍受同一 deadline 约束，不返回成功")
    void drainAfterExitIsStillBoundedByTheSameDeadline() throws Exception {
        long start = System.nanoTime();
        KubectlProcessRunner.CommandResult result = KubectlProcessRunner.run(
                SyntheticProcessFixture.command("print:PART"), null, Duration.ofSeconds(1), blockingAfterFirstRead());
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertFalse(result.success());
        assertTrue(result.timedOut());
        assertTrue(elapsedMillis < 1_000 + CLEANUP_GRACE_MILLIS + SCHEDULING_TOLERANCE_MILLIS,
                () -> "draining was not bounded by the deadline, elapsed=" + elapsedMillis + "ms");
        assertTrue(result.stdout().contains("PART"), () -> "stdout=" + result.stdout());
        assertFalse(result.notes().isEmpty(), "unconfirmed reclamation must be reported");
    }

    @Test
    @DisplayName("输出读取慢于固定 1 秒排空窗口：完整输出不被截断")
    void slowOutputIsNotTruncatedByAFixedDrainWindow() throws Exception {
        KubectlProcessRunner.CommandResult result = KubectlProcessRunner.run(
                SyntheticProcessFixture.command("print:PART"), null, Duration.ofSeconds(30),
                slowFirstRead(1200L));

        assertTrue(result.success(), () -> "failureReason=" + result.failureReason());
        assertEquals("PART", result.stdout());
    }

    /** Passes the first read through, then fails: an I/O failure while the command is still running. */
    private static UnaryOperator<InputStream> failingAfterFirstRead() {
        return stream -> new FilterInputStream(stream) {

            private boolean served;

            @Override
            public int read(byte[] buffer, int offset, int length) throws IOException {
                if (!served) {
                    served = true;
                    return super.read(buffer, offset, length);
                }
                throw new IOException("synthetic read failure");
            }
        };
    }

    /** Passes the first read through, then dies with an unchecked exception: a task that ends badly. */
    private static UnaryOperator<InputStream> failingWithRuntimeExceptionAfterFirstRead() {
        return stream -> new FilterInputStream(stream) {

            private boolean served;

            @Override
            public int read(byte[] buffer, int offset, int length) throws IOException {
                if (!served) {
                    served = true;
                    return super.read(buffer, offset, length);
                }
                throw new IllegalStateException("synthetic runtime read failure");
            }
        };
    }

    /** Blocks after the first read until the task is interrupted: output that never reaches EOF. */
    private static UnaryOperator<InputStream> blockingAfterFirstRead() {
        return stream -> new FilterInputStream(stream) {

            private boolean served;

            @Override
            public int read(byte[] buffer, int offset, int length) throws IOException {
                if (!served) {
                    served = true;
                    return super.read(buffer, offset, length);
                }
                try {
                    Thread.sleep(Long.MAX_VALUE);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted while blocked on the child stream");
                }
                return -1;
            }
        };
    }

    /** Delays the first read past a fixed one-second drain window. */
    private static UnaryOperator<InputStream> slowFirstRead(long delayMillis) {
        return stream -> new FilterInputStream(stream) {

            private boolean delayed;

            @Override
            public int read(byte[] buffer, int offset, int length) throws IOException {
                if (!delayed) {
                    delayed = true;
                    try {
                        Thread.sleep(delayMillis);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("interrupted while delaying the read");
                    }
                }
                return super.read(buffer, offset, length);
            }
        };
    }

    /**
     * Blocks every read until released and keeps blocking after interrupts, so reclamation cannot
     * end early; the interrupt that terminating the pool delivers to a blocked task is reported, so
     * a test can observe that reclamation has reached its waiting stage.
     */
    private static final class InterruptIgnoringReader implements UnaryOperator<InputStream> {

        private final CountDownLatch released = new CountDownLatch(1);
        private final CountDownLatch poolInterruptSeen = new CountDownLatch(1);

        @Override
        public InputStream apply(InputStream stream) {
            return new FilterInputStream(stream) {

                @Override
                public int read(byte[] buffer, int offset, int length) throws IOException {
                    while (released.getCount() > 0) {
                        try {
                            released.await();
                        } catch (InterruptedException e) {
                            // keep blocking: the test releases this task explicitly
                            poolInterruptSeen.countDown();
                        }
                    }
                    return -1;
                }

                @Override
                public int read() throws IOException {
                    return read(new byte[1], 0, 1);
                }
            };
        }

        boolean awaitPoolInterrupt(long timeoutMillis) throws InterruptedException {
            return poolInterruptSeen.await(timeoutMillis, TimeUnit.MILLISECONDS);
        }

        void release() {
            released.countDown();
        }
    }
}
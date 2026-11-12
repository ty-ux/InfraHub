package com.infrahub.release.service.executor.kubernetes;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * Runs a single argv command with concurrent stdout/stderr consumption.
 *
 * <p>One absolute deadline covers stdin delivery, waiting for exit and draining both output
 * streams. A command only succeeds when the process exited, all three tasks ran to their end and
 * the configured stdin content was delivered - an exit code of 0 alone is not enough: a task that
 * ended by throwing is not a task that completed. Failures that happen while the command is
 * executing are reported together with whatever output was already collected, instead of being
 * dropped; errors caused by reclamation itself are not.</p>
 *
 * <p>Reclamation (terminating the process, closing the child streams, joining the stream tasks and
 * terminating the pool) runs after the deadline or after the outcome is decided, and is bounded by
 * {@link #CLEANUP_GRACE}. Anything that cannot be confirmed reclaimed is reported through
 * {@link CommandResult#notes()} without replacing the original outcome. An interrupt raised while
 * reclaiming - or already pending when reclamation starts - is recorded, the interrupt flag is kept
 * and {@link InterruptedException} is thrown once reclamation finished, so a stopped caller never
 * receives a successful result.</p>
 */
final class KubectlProcessRunner {

    private static final long POLL_MILLIS = 50L;

    /** Bounds destroy() followed by waiting for the process to exit. */
    private static final Duration TERMINATE_GRACE = Duration.ofSeconds(1);
    /** Bounds destroyForcibly() followed by waiting for the process to exit. */
    private static final Duration FORCE_GRACE = Duration.ofSeconds(1);
    /** Bounds closing the three child streams (shared). */
    private static final Duration CLOSE_GRACE = Duration.ofMillis(500);
    /** Bounds joining the three stream tasks (shared). */
    private static final Duration TASK_GRACE = Duration.ofMillis(500);
    /** Bounds terminating the stream task pool. */
    private static final Duration POOL_GRACE = Duration.ofMillis(500);

    /**
     * Upper bound of the reclamation performed for one command after the execution deadline (or
     * after the outcome is decided). It is charged on top of the caller's timeout.
     */
    static final Duration CLEANUP_GRACE = TERMINATE_GRACE.plus(FORCE_GRACE).plus(CLOSE_GRACE)
            .plus(TASK_GRACE).plus(POOL_GRACE);

    private KubectlProcessRunner() {
    }

    record CommandResult(boolean success, boolean processExited, boolean timedOut, int exitCode,
                         String stdout, String stderr, String failureReason, List<String> notes) {
    }

    static CommandResult run(List<String> command, String stdinContent, Duration timeout)
            throws IOException, InterruptedException {
        return run(command, stdinContent, timeout, UnaryOperator.identity());
    }

    /**
     * Test seam: {@code outputWrapper} may replace the child's output streams so that read failures
     * can be exercised; production always passes {@link UnaryOperator#identity()}.
     */
    static CommandResult run(List<String> command, String stdinContent, Duration timeout,
                             UnaryOperator<InputStream> outputWrapper) throws IOException, InterruptedException {
        return run(command, stdinContent, timeout, outputWrapper, null);
    }

    /**
     * Test seam: {@code reclamationBarrier} is notified on this thread right before reclamation
     * inspects the interrupt state, so a test can stop the caller at that boundary deterministically
     * instead of guessing how long the runner has been running; production passes {@code null}.
     */
    static CommandResult run(List<String> command, String stdinContent, Duration timeout,
                             UnaryOperator<InputStream> outputWrapper, ReclamationBarrier reclamationBarrier)
            throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(false).start();

        long deadlineNanos = System.nanoTime() + Math.max(timeout.toNanos(), 0L);
        RunState state = new RunState();
        Sink stdout = new Sink();
        Sink stderr = new Sink();

        ExecutorService streamPool = Executors.newFixedThreadPool(3, daemonThreadFactory());
        List<Future<?>> tasks = new ArrayList<>();
        tasks.add(streamPool.submit(guarded("stdin delivery failed", state, reason -> state.stdinFailure = reason,
                () -> writeStdin(process, stdinContent))));
        tasks.add(streamPool.submit(guarded("stdout read failed", state, stdout::recordFailure,
                () -> readStream(outputWrapper, process.getInputStream(), stdout))));
        tasks.add(streamPool.submit(guarded("stderr read failed", state, stderr::recordFailure,
                () -> readStream(outputWrapper, process.getErrorStream(), stderr))));

        boolean processExited = false;
        boolean allTasksDone = false;
        List<String> notes = List.of();
        InterruptedException interrupted = null;
        boolean pendingInterrupt = false;
        try {
            try {
                processExited = awaitExit(process, deadlineNanos);
                if (processExited) {
                    allTasksDone = awaitTasks(tasks, deadlineNanos);
                }
            } catch (InterruptedException e) {
                interrupted = e;
            }
        } finally {
            if (reclamationBarrier != null) {
                reclamationBarrier.beforeReclamation();
            }
            // an interrupt that arrived after the last blocking call is still pending and must count
            // as the outcome of this run; reclamation may wait for it, so it is re-applied afterwards
            pendingInterrupt = Thread.interrupted();
            boolean restoreInterrupt = pendingInterrupt || interrupted != null;
            try {
                notes = cleanup(process, streamPool, tasks, state);
            } finally {
                if (restoreInterrupt) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        // the caller's own interrupt wins, and a stopped caller never receives a result
        if (interrupted != null) {
            throw interrupted;
        }
        if (pendingInterrupt) {
            throw new InterruptedException("an interrupt was already pending when reclamation started");
        }
        // not every interrupt is raised by a blocking call, so the flag is checked regardless; the
        // flag is already set on every path that reaches here
        if (state.cleanupInterrupted || Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("cleanup was interrupted while reclaiming the child process");
        }

        int exitCode = processExited ? process.exitValue() : -1;
        String failureReason = failureReason(state, stdout, stderr);
        boolean tasksCompletedNormally = state.completedTasks.get() == tasks.size();
        boolean success = processExited && allTasksDone && tasksCompletedNormally
                && failureReason == null && exitCode == 0;
        boolean timedOut = !success && (!processExited || !allTasksDone);
        return new CommandResult(success, processExited, timedOut, exitCode,
                stdout.content(), stderr.content(), failureReason, notes);
    }

    private static boolean awaitExit(Process process, long deadlineNanos) throws InterruptedException {
        while (true) {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                return !process.isAlive();
            }
            if (process.waitFor(sliceMillis(remainingNanos), TimeUnit.MILLISECONDS)) {
                return true;
            }
        }
    }

    /** Waits for stdin delivery and both output streams under the same deadline as the process exit. */
    private static boolean awaitTasks(List<Future<?>> tasks, long deadlineNanos) throws InterruptedException {
        while (true) {
            if (allDone(tasks)) {
                return true;
            }
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                return allDone(tasks);
            }
            Thread.sleep(sliceMillis(remainingNanos));
        }
    }

    private static boolean allDone(List<Future<?>> tasks) {
        for (Future<?> task : tasks) {
            if (!task.isDone()) {
                return false;
            }
        }
        return true;
    }

    private static long sliceMillis(long remainingNanos) {
        return Math.min(POLL_MILLIS, Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos) + 1L));
    }

    /**
     * Wraps a task so that an unexpected end is never mistaken for a completed one: a task that
     * throws did not run to its end, so it is reported as a failure and does not count towards
     * {@link RunState#completedTasks}. Once reclamation started, the same error is an artefact of
     * closing the streams or killing the tasks, and must not overwrite the original outcome.
     */
    private static Runnable guarded(String failurePrefix, RunState state, Consumer<String> recorder, IoTask task) {
        return () -> {
            try {
                task.run();
                state.completedTasks.incrementAndGet();
            } catch (Throwable t) {
                if (!state.cleanupStarted) {
                    recorder.accept(failurePrefix + ": " + describe(t));
                }
            }
        };
    }

    /** Closes stdin so a child waiting for EOF can proceed; writes the content as UTF-8 when present. */
    private static void writeStdin(Process process, String stdinContent) throws IOException {
        try (OutputStream stdin = process.getOutputStream()) {
            if (stdinContent != null && !stdinContent.isEmpty()) {
                stdin.write(stdinContent.getBytes(StandardCharsets.UTF_8));
                stdin.flush();
            }
        }
    }

    private static void readStream(UnaryOperator<InputStream> wrapper, InputStream raw, Sink sink)
            throws IOException {
        try (Reader reader = new InputStreamReader(wrapper.apply(raw), StandardCharsets.UTF_8)) {
            char[] buffer = new char[8192];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                sink.append(buffer, read);
            }
        }
    }

    private static String failureReason(RunState state, Sink stdout, Sink stderr) {
        if (state.stdinFailure != null) {
            return state.stdinFailure;
        }
        return stdout.failure() != null ? stdout.failure() : stderr.failure();
    }

    private static String describe(Throwable t) {
        return t.getClass().getName() + ": " + t.getMessage();
    }

    private static List<String> cleanup(Process process, ExecutorService streamPool, List<Future<?>> tasks,
                                        RunState state) {
        List<String> notes = new ArrayList<>();
        // from here on, I/O errors come from streams and tasks we close ourselves
        state.cleanupStarted = true;
        terminate(process, notes, state);
        closeStreams(process, notes, state);
        awaitTasksQuietly(tasks, notes, state);
        terminatePool(streamPool, notes, state);
        return notes;
    }

    private static void terminate(Process process, List<String> notes, RunState state) {
        try {
            if (process.isAlive()) {
                process.destroy();
                if (!process.waitFor(TERMINATE_GRACE.toMillis(), TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(FORCE_GRACE.toMillis(), TimeUnit.MILLISECONDS);
                }
            }
            if (process.isAlive()) {
                notes.add("process (pid " + process.pid() + ") is still alive after destroy/destroyForcibly");
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            recordCleanupInterrupt(state);
            notes.add("cleanup was interrupted while terminating the process; forced kill requested");
        }
    }

    /**
     * Closing a child stream can block (a reader that never sees EOF, a writer the child never
     * reads), so every close runs on its own daemon thread under a shared bound instead of becoming
     * a new blocking point.
     */
    private static void closeStreams(Process process, List<String> notes, RunState state) {
        List<Closeable> streams = List.of(process.getOutputStream(), process.getInputStream(), process.getErrorStream());
        List<Thread> closers = new ArrayList<>();
        for (int index = 0; index < streams.size(); index++) {
            Closeable stream = streams.get(index);
            Thread closer = daemonThread("kubectl-process-close-" + index, () -> closeQuietly(stream));
            closer.start();
            closers.add(closer);
        }
        if (!joinAll(closers, CLOSE_GRACE, state)) {
            notes.add("child streams were not closed within " + CLOSE_GRACE.toMillis() + "ms");
        }
    }

    private static void awaitTasksQuietly(List<Future<?>> tasks, List<String> notes, RunState state) {
        long deadlineNanos = System.nanoTime() + TASK_GRACE.toNanos();
        for (Future<?> task : tasks) {
            long remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
            try {
                task.get(Math.max(remainingMillis, 1L), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                notes.add("a stream task did not finish within " + TASK_GRACE.toMillis() + "ms");
            } catch (ExecutionException e) {
                notes.add("a stream task failed: " + e.getCause());
            } catch (InterruptedException e) {
                recordCleanupInterrupt(state);
                notes.add("cleanup was interrupted while waiting for stream tasks");
                return;
            }
        }
    }

    private static void terminatePool(ExecutorService streamPool, List<String> notes, RunState state) {
        streamPool.shutdownNow();
        try {
            if (!streamPool.awaitTermination(POOL_GRACE.toMillis(), TimeUnit.MILLISECONDS)) {
                notes.add("stream task pool did not terminate within " + POOL_GRACE.toMillis() + "ms");
            }
        } catch (InterruptedException e) {
            recordCleanupInterrupt(state);
            notes.add("cleanup was interrupted while waiting for the stream task pool");
        }
    }

    private static boolean joinAll(List<Thread> threads, Duration grace, RunState state) {
        long deadlineNanos = System.nanoTime() + grace.toNanos();
        boolean allJoined = true;
        for (Thread thread : threads) {
            long remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
            try {
                thread.join(Math.max(remainingMillis, 1L));
            } catch (InterruptedException e) {
                recordCleanupInterrupt(state);
                return false;
            }
            if (thread.isAlive()) {
                allJoined = false;
            }
        }
        return allJoined;
    }

    /** Reclamation waits only because the flag was cleared, so the interrupt is recorded and re-applied. */
    private static void recordCleanupInterrupt(RunState state) {
        state.cleanupInterrupted = true;
        Thread.currentThread().interrupt();
    }

    private static void closeQuietly(Closeable closeable) {
        try {
            closeable.close();
        } catch (IOException ignored) {
            // releasing a stream must not mask the original outcome
        }
    }

    private static Thread daemonThread(String name, Runnable runnable) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    private static ThreadFactory daemonThreadFactory() {
        return runnable -> daemonThread("kubectl-process-stream", runnable);
    }

    /** Test seam: notified on the runner thread when reclamation starts; may hold reclamation back. */
    @FunctionalInterface
    interface ReclamationBarrier {

        void beforeReclamation();
    }

    @FunctionalInterface
    private interface IoTask {

        void run() throws Exception;
    }

    private static final class Sink {

        private final StringBuffer content = new StringBuffer();
        private volatile String failure;

        void append(char[] buffer, int length) {
            content.append(buffer, 0, length);
        }

        void recordFailure(String reason) {
            if (failure == null) {
                failure = reason;
            }
        }

        String content() {
            return content.toString();
        }

        String failure() {
            return failure;
        }
    }

    private static final class RunState {

        /** Set before streams are closed or tasks are killed; later I/O errors are cleanup artefacts. */
        volatile boolean cleanupStarted;

        /** Set when reclamation itself was interrupted, so the caller keeps the interrupt. */
        volatile boolean cleanupInterrupted;

        volatile String stdinFailure;

        /** Only tasks that ran to their end are counted; a task that died is not a completed one. */
        final AtomicInteger completedTasks = new AtomicInteger();
    }
}
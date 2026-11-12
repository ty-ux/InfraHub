package com.infrahub.release.service.executor.kubernetes;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Minimal JDK-only child process used by the kubernetes executor tests.
 * Never calls kubectl; behaviour is selected by the first argument (mode).
 */
public final class SyntheticProcessFixture {

    static final int BURST_BYTES = 256 * 1024;
    static final String BURST_DONE = "BURST-DONE";

    private SyntheticProcessFixture() {
    }

    public static void main(String[] args) throws Exception {
        forceUtf8Stdio();

        String mode = args.length > 0 ? args[0] : "ok";

        if (mode.startsWith("print:")) {
            System.out.print(mode.substring("print:".length()));
        } else if (mode.startsWith("print-later:")) {
            String[] parts = mode.split(":", 3);
            Thread.sleep(Long.parseLong(parts[1]));
            System.out.print(parts[2]);
        } else if (mode.startsWith("fail:")) {
            String[] parts = mode.split(":", 3);
            System.out.println("OUT-OK");
            System.out.flush();
            System.err.println(parts.length > 2 ? parts[2] : "fail");
            System.err.flush();
            System.exit(Integer.parseInt(parts[1]));
        } else if (mode.startsWith("hang-pid:")) {
            writePidFile(Path.of(mode.substring("hang-pid:".length())));
            sleepForever();
        } else if (mode.startsWith("spawn-holder:")) {
            Path pidFile = Path.of(mode.substring("spawn-holder:".length()));
            ProcessBuilder pb = new ProcessBuilder(command("hang"));
            // the holder must inherit this process' pipes, otherwise it would get fresh ones and the
            // parent would see EOF immediately
            pb.inheritIO();
            Process holder = pb.start();
            writePidFile(pidFile, holder.pid());
            System.out.print("SPAWNED");
        } else {
            switch (mode) {
                case "ok" -> {
                }
                case "hang" -> sleepForever();
                case "stdin-closed" -> {
                    // refuse further input: the parent's write is expected to fail
                    System.in.close();
                    Thread.sleep(300L);
                }
                case "print-then-hang" -> {
                    System.out.println("EARLY-OUT");
                    System.err.println("EARLY-ERR");
                    System.out.flush();
                    System.err.flush();
                    sleepForever();
                }
                case "burst" -> burst();
                case "stdin-eof" -> readStdinToEof();
                case "echo-stdin" -> echoStdin();
                default -> throw new IllegalArgumentException("unknown mode: " + mode);
            }
        }

        System.out.flush();
        System.err.flush();
    }

    /**
     * The parent decodes both streams as UTF-8; the platform default console encoding would corrupt
     * non-ASCII output and make the UTF-8 tests meaningless.
     */
    private static void forceUtf8Stdio() {
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8));
    }

    private static void sleepForever() throws InterruptedException {
        Thread.sleep(120_000L);
    }

    private static void writePidFile(Path path) throws IOException {
        writePidFile(path, ProcessHandle.current().pid());
    }

    private static void writePidFile(Path path, long pid) throws IOException {
        Files.writeString(path, Long.toString(pid), StandardCharsets.UTF_8);
    }

    /**
     * Kills a process spawned by the {@code spawn-holder} mode. Never throws: it only exists so a
     * failing test cannot leak the holder.
     */
    static void releaseHolder(Path pidFile) {
        try {
            String content = Files.readString(pidFile, StandardCharsets.UTF_8).trim();
            if (content.isEmpty()) {
                return;
            }
            ProcessHandle.of(Long.parseLong(content)).ifPresent(handle -> {
                handle.destroyForcibly();
                try {
                    handle.onExit().get(10, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                    // best effort
                }
            });
        } catch (Exception ignored) {
            // best effort
        }
    }

    private static void readStdinToEof() throws IOException {
        long total = 0L;
        byte[] buffer = new byte[4096];
        int read;
        try (InputStream stdin = System.in) {
            while ((read = stdin.read(buffer)) != -1) {
                total += read;
            }
        }
        System.out.print("STDIN_BYTES=" + total);
    }

    private static void echoStdin() throws IOException {
        byte[] all = System.in.readAllBytes();
        System.out.print("STDIN:" + new String(all, StandardCharsets.UTF_8));
    }

    private static void burst() throws IOException {
        OutputStream stdout = System.out;
        OutputStream stderr = System.err;
        byte[] outChunk = new byte[1024];
        byte[] errChunk = new byte[1024];
        for (int i = 0; i < outChunk.length; i++) {
            outChunk[i] = 'o';
            errChunk[i] = 'e';
        }

        for (int written = 0; written < BURST_BYTES; written += outChunk.length) {
            stdout.write(outChunk);
            stderr.write(errChunk);
            if (written % (16 * 1024) == 0) {
                stdout.flush();
                stderr.flush();
            }
        }
        stdout.flush();
        stderr.flush();

        PrintStream out = System.out;
        out.print(BURST_DONE);
        out.flush();
    }

    static List<String> command(String mode) {
        List<String> command = new ArrayList<>();
        command.add(javaBinary());
        command.add("-cp");
        command.add(fixtureClasspath());
        command.add(SyntheticProcessFixture.class.getName());
        command.add(mode);
        return command;
    }

    static long awaitPidFile(Path pidFile, long timeoutMillis) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadline) {
            try {
                if (Files.exists(pidFile)) {
                    String content = Files.readString(pidFile, StandardCharsets.UTF_8).trim();
                    if (!content.isEmpty()) {
                        return Long.parseLong(content);
                    }
                }
            } catch (IOException | NumberFormatException ignored) {
                // child still writing the file
            }
            Thread.sleep(50L);
        }
        throw new AssertionError("child did not report its pid within " + timeoutMillis + "ms");
    }

    static void awaitProcessGone(long pid, long timeoutMillis) throws Exception {
        ProcessHandle handle = ProcessHandle.of(pid).orElse(null);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadline) {
            if (handle == null || !handle.isAlive()) {
                return;
            }
            Thread.sleep(50L);
        }
        throw new AssertionError("child process " + pid + " is still alive after " + timeoutMillis + "ms");
    }

    private static String javaBinary() {
        Path bin = Path.of(System.getProperty("java.home"), "bin");
        Path candidate = bin.resolve("java.exe");
        return Files.exists(candidate) ? candidate.toString() : bin.resolve("java").toString();
    }

    private static String fixtureClasspath() {
        try {
            return Path.of(SyntheticProcessFixture.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).toString();
        } catch (Exception e) {
            throw new IllegalStateException("unable to resolve fixture classpath", e);
        }
    }
}
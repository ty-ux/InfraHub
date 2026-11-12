package com.infrahub.infra.ssh;

import com.infrahub.common.error.ErrorCode;
import com.infrahub.common.exception.BizException;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SocketFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 基于 CLI {@code ssh}（私钥）或 JSch（密码）的命令执行器。
 *
 * <p><b>超时必须是"真的把底层资源收回来"</b>：调用方（采集）只等固定预算，
 * 若这里不传超时、不杀进程、不断会话，那么"放弃等待"之后子进程与阻塞线程会一直在后台堆积
 * —— 采集每轮都会再压一层，最终表现是机器悄悄变慢、进程表里堆满 ssh。
 * 因此本类做到：
 * <ul>
 *   <li>私钥分支：stdout/stderr 各由守护线程排空（避免先读一条流时管道写满互等死锁），
 *       主线程用 {@code waitFor(timeout)} 等待，超时或被中断一律 {@code destroyForcibly()}；</li>
 *   <li>密码分支：会话连接、通道连接、命令等待**共用同一个单调时钟总截止**；握手期间的阻塞读
 *       由 {@link DeadlineSocketFactory} 接管，切成小片后逐片检查绝对截止与调用线程的中断标志，
 *       命中就**关掉真实套接字**让阻塞中的 {@code connect} 立刻返回；命令阶段按剩余预算切片轮询；
 *       {@code finally} 断开 channel 与 session（真实的套接字与 JSch 线程在此回收）。</li>
 * </ul>
 *
 * <p><b>取消的语义</b>：调用方（采集）用 {@code Future.cancel(true)} 取消探测，落到这里就是
 * 调用线程被中断。{@code cancel(true)} 本身只置一个中断标志，JSch 的阻塞调用并不会因此醒来，
 * 所以"取消"要真的回收资源，只能靠两件事：把每段阻塞调用夹在剩余预算之内（有界结束），
 * 以及终于必然执行的 {@code disconnect()}。
 *
 * <p><b>为什么光把剩余预算交给 JSch 还不够</b>：JSch 的连接超时只约束**单次读**。对端只要一直
 * 给数据（哪怕每 40ms 一个字节），每次读都会成功返回，总截止就永远轮不到执行；反过来对端完全
 * 不说话时，单次读又要等满整个连接超时，"取消"于是要陪等到预算尽头。所以握手期间本类自己接管
 * 读取（见 {@link DeadlineSocketFactory}）：每片只等 {@link #HANDSHAKE_READ_SLICE_MILLIS} 毫秒，
 * 片与片之间检查绝对截止与中断，命中就主动关掉套接字。**不另起工作线程**：切片跑在发起调用的
 * 那个线程上，中断才有意义；JSch 认证之后自己的收包线程从同一条流取数据，那里没有总截止的概念，
 * 语义与改动前保持一致。
 */
@Service
public class CliSshExecutorService implements SshExecutorService {

    private static final int CONNECT_TIMEOUT_MILLIS = 10_000;
    private static final int COMMAND_TIMEOUT_MILLIS = 120_000;

    /** 命令阶段的轮询间隔上限：等待被切成小片，取消才不至于被一整段 sleep 挡住。 */
    private static final long COMMAND_POLL_MILLIS = 100L;

    /** 排空流的线程收尾上限：进程已被杀掉后流必然 EOF，这里只是不让收尾无限期挂住。 */
    private static final long DRAIN_JOIN_MILLIS = 2_000;

    /**
     * 握手期间单次读取的切片长度。
     *
     * <p>这是"总截止与取消能在阻塞期间生效"的唯一手段：本线程能打断自己的阻塞读的方式，就是让这次
     * 读按一个小超时返回。片长取得比一次网络往返小得多，才能既快又不必依赖对端配合。
     */
    private static final int HANDSHAKE_READ_SLICE_MILLIS = 25;

    @Override
    public SshExecutionResult execute(SshExecutionRequest request) {
        if ("PASSWORD".equalsIgnoreCase(request.getAuthType())) {
            return executeWithPassword(request);
        }
        if ("PRIVATE_KEY".equalsIgnoreCase(request.getAuthType())) {
            return executeWithPrivateKey(request);
        }
        throw new BizException(ErrorCode.VALIDATION_ERROR, "不支持的 SSH 认证方式");
    }

    /** 请求未指定超时（既有发布调用方）时退回默认值，行为与改动前一致。 */
    private static int timeoutOf(SshExecutionRequest request) {
        Long requested = request.getTimeoutMillis();
        return requested == null || requested <= 0 ? COMMAND_TIMEOUT_MILLIS : requested.intValue();
    }

    private SshExecutionResult executeWithPrivateKey(SshExecutionRequest request) {
        if (request.getPrivateKeyPath() == null || request.getPrivateKeyPath().isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "SSH 私钥路径不能为空");
        }

        List<String> command = new ArrayList<>();
        command.add("ssh");
        command.add("-o");
        command.add("StrictHostKeyChecking=no");
        command.add("-i");
        command.add(request.getPrivateKeyPath());
        command.add("-p");
        command.add(String.valueOf(request.getPort() == null ? 22 : request.getPort()));
        command.add(request.getUsername() + "@" + request.getHost());
        command.add(encodeCommandForCli(request.getCommand()));

        int timeoutMillis = timeoutOf(request);
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        OffsetDateTime startedAt = OffsetDateTime.now();
        Process process = null;
        StreamDrain stdout = null;
        StreamDrain stderr = null;
        try {
            process = processBuilder.start();
            stdout = StreamDrain.start(process.getInputStream(), "ssh-stdout-drain");
            stderr = StreamDrain.start(process.getErrorStream(), "ssh-stderr-drain");

            if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor();
                OffsetDateTime endedAt = OffsetDateTime.now();
                // 本地 ssh 进程已被杀掉，但远端命令可能仍在运行：这是“结果未知”，不是“远端失败”
                return SshExecutionResult.timedOut(
                        stdout.await(), stderr.await() + "\nSSH command timed out after " + timeoutMillis + " ms",
                        startedAt, endedAt);
            }

            int exitCode = process.exitValue();
            String stdoutText = stdout.await();
            String stderrText = stderr.await();
            OffsetDateTime endedAt = OffsetDateTime.now();
            return SshExecutionResult.fromCliExit(exitCode, stdoutText, stderrText, startedAt, endedAt);
        } catch (InterruptedException exception) {
            // 采集侧取消走的就是这条路径：不杀进程的话，"取消"只是调用方不再等，子进程照跑不误。
            if (process != null) {
                process.destroyForcibly();
            }
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCode.INTERNAL_ERROR, "SSH 执行被中断: " + exception.getMessage());
        } catch (IOException exception) {
            if (process != null) {
                process.destroyForcibly();
            }
            throw new BizException(ErrorCode.INTERNAL_ERROR, "SSH 执行失败: " + exception.getMessage());
        } finally {
            if (stdout != null) {
                stdout.closeQuietly();
            }
            if (stderr != null) {
                stderr.closeQuietly();
            }
        }
    }

    /**
     * Windows OpenSSH receives a native command line rather than Java's argument vector.
     * Keep quotes, newlines and shell expansions out of that boundary: only the remote
     * shell decodes the UTF-8 script. Base64 is transport encoding, not secret protection.
     */
    static String encodeCommandForCli(String script) {
        String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_8));
        return "printf %s " + encoded + " | base64 -d | sh";
    }

    private SshExecutionResult executeWithPassword(SshExecutionRequest request) {
        if (request.getPassword() == null || request.getPassword().isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "SSH 密码不能为空");
        }

        int budgetMillis = timeoutOf(request);
        // 单调时钟 + 唯一一个总截止：会话连接、通道连接、命令等待共用它，任何一段都不再领新预算。
        // 用 System.nanoTime 而不是 currentTimeMillis：后者会被系统时间调整（NTP 回拨）往回拨，
        // 一次回拨就能让"总截止"变成永远到不了。
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMillis);
        Session session = null;
        ChannelExec channel = null;
        OffsetDateTime startedAt = OffsetDateTime.now();
        try {
            session = openPasswordSession(request);
            // 先接管读取，再握手：JSch 自己的连接超时只约束单次读，管不住"持续有数据"的握手。
            armHandshakeDeadline(session, deadlineNanos, budgetMillis);
            session.connect(connectTimeoutMillis(deadlineNanos));
            // 取消就是调用线程被中断，而 JSch 的阻塞调用不响应中断（只受各自超时约束），
            // 因此中断可能"晚"到这里才被看见。看见就不再往下发命令，立刻走收尾断开真实连接。
            failIfInterrupted();
            if (pastDeadline(deadlineNanos)) {
                // 预算已在连接阶段花光：不开通道、不发命令，如实按总预算报超时。
                // 关键是**不再补一份新预算**，否则"总截止"就退化成了"每阶段各一份"。
                return timeoutResult(budgetMillis, startedAt, "", "");
            }

            channel = openExecChannel(session);
            channel.setCommand(request.getCommand());

            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            channel.setErrStream(stderr);
            var stdoutStream = channel.getInputStream();

            channel.connect(connectTimeoutMillis(deadlineNanos));
            failIfInterrupted();
            byte[] buffer = new byte[8192];
            while (true) {
                while (stdoutStream.available() > 0) {
                    int read = stdoutStream.read(buffer);
                    if (read < 0) {
                        break;
                    }
                    stdout.write(buffer, 0, read);
                }
                if (channel.isClosed()) {
                    break;
                }
                if (pastDeadline(deadlineNanos)) {
                    return timeoutResult(budgetMillis, startedAt,
                            stdout.toString(StandardCharsets.UTF_8),
                            stderr.toString(StandardCharsets.UTF_8));
                }
                // 等待切片不超过剩余预算：既让取消在有界时间内被看到，也不会睡过截止时刻
                // （旧实现固定睡 100ms，预算只剩 1ms 时照样多睡满 100ms）。
                Thread.sleep(Math.min(COMMAND_POLL_MILLIS, remainingMillis(deadlineNanos)));
            }

            OffsetDateTime endedAt = OffsetDateTime.now();
            while (stdoutStream.available() > 0) {
                int read = stdoutStream.read(buffer);
                if (read < 0) {
                    break;
                }
                stdout.write(buffer, 0, read);
            }
            String stdoutText = stdout.toString(StandardCharsets.UTF_8);
            String stderrText = stderr.toString(StandardCharsets.UTF_8);
            int exitCode = channel.getExitStatus();
            if (exitCode == 0) {
                return SshExecutionResult.success(stdoutText, stderrText, startedAt, endedAt);
            }
            if (exitCode > 0) {
                return SshExecutionResult.failure(exitCode, stdoutText, stderrText, startedAt, endedAt);
            }
            // 通道已关闭但 JSch 没有拿到退出状态（连接中断、远端未发送 exit-status）：远端结果未知
            return SshExecutionResult.unknown(exitCode, stdoutText, stderrText, startedAt, endedAt);
        } catch (JSchException | IOException exception) {
            // 中断与网络故障可能同时可见：JSch 的阻塞读不响应中断，中断往往等到这里才被看见。
            // 线程已被中断时，真正的原因是取消，不能把它记成一次网络故障（否则现场会把
            // "我们主动放弃了"读成"机器连不上"）。
            if (Thread.currentThread().isInterrupted()) {
                throw new BizException(ErrorCode.INTERNAL_ERROR, "SSH 执行被中断: " + exception.getMessage());
            }
            throw new BizException(ErrorCode.INTERNAL_ERROR, "SSH 执行失败: " + exception.getMessage());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCode.INTERNAL_ERROR, "SSH 执行被中断: " + exception.getMessage());
        } finally {
            // 无条件断开：通道与会话（及其内部线程/套接字）在此回收，取消与超时都不会漏
            if (channel != null) {
                channel.disconnect();
            }
            if (session != null) {
                session.disconnect();
            }
        }
    }

    /**
     * 总预算耗尽的统一结果：退出码固定 -1，摘要里写明预算，便于现场把"我们放弃了"和"远端失败"分开看。
     *
     * <p>三条路径（连接后已超预算、命令阶段超时）共用它，摘要文案只有一处，不会各写一套。
     */
    private static SshExecutionResult timeoutResult(int budgetMillis, OffsetDateTime startedAt,
                                                    String stdoutText, String stderrText) {
        return SshExecutionResult.timedOut(stdoutText,
                stderrText + "\nSSH command timed out after " + budgetMillis + " ms",
                startedAt, OffsetDateTime.now());
    }

    /**
     * 距离总截止还剩多少毫秒（向下取整，最小 1）。
     *
     * <p><b>不返回 0</b>：JSch 把 {@code connect(0)} 解读为"用会话默认超时"，而会话默认超时就是 0
     * ——也就是无限等下去，恰好是本类要避免的那件事。宁可让它立刻判超时，也不能让它无上限地挂住。
     */
    private static long remainingMillis(long deadlineNanos) {
        return Math.max(1L, TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime()));
    }

    private static boolean pastDeadline(long deadlineNanos) {
        return System.nanoTime() - deadlineNanos >= 0;
    }

    /**
     * 单次连接（会话或通道）允许占用的时间：总预算的剩余部分，且不超过 {@link #CONNECT_TIMEOUT_MILLIS}。
     *
     * <p><b>刻意不设下限</b>：旧实现写的是 {@code Math.min(10_000, Math.max(1_000, timeoutMillis))}，
     * 会把 100ms 的预算放大成一个 1000ms 的连接超时 —— 采集按 100ms 放弃等待之后，这次连接还会
     * 独自再跑近 1 秒，"100ms 预算"名不副实。现在连接只能花掉预算里剩下的时间，花光就如实报超时。
     * 上界仍然保留：默认 120s 的预算不该允许把 120s 全耗在连接上。
     */
    private static int connectTimeoutMillis(long deadlineNanos) {
        return (int) Math.min(CONNECT_TIMEOUT_MILLIS, remainingMillis(deadlineNanos));
    }

    /** 每段阻塞调用之后都查一次中断：JSch 自己不响应中断，只有这里能及时停手。 */
    private static void failIfInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("执行线程已被取消");
        }
    }

    /**
     * 建立一个密码认证会话。
     *
     * <p>抽成方法只为留出测试接缝（假会话可确定性地覆盖连接/命令阶段的预算与取消），
     * 生产路径照旧走 JSch 默认实现，凭据处理没有任何变化。
     */
    Session openPasswordSession(SshExecutionRequest request) throws JSchException {
        JSch jsch = new JSch();
        Session session = jsch.getSession(request.getUsername(), request.getHost(),
                request.getPort() == null ? 22 : request.getPort());
        session.setPassword(request.getPassword());
        session.setConfig("StrictHostKeyChecking", "no");
        session.setConfig("PreferredAuthentications", "password");
        return session;
    }

    /** 打开 exec 通道。与 {@link #openPasswordSession} 同为测试接缝。 */
    ChannelExec openExecChannel(Session session) throws JSchException {
        return (ChannelExec) session.openChannel("exec");
    }

    /**
     * 给会话装上"握手总截止与取消"的读取接管，必须在 {@code connect} **之前**调用。
     *
     * <p>刻意不动 {@link #openPasswordSession} 的签名：假会话（测试接缝）上这是一次普通调用，
     * 既有的假会话用例照旧只跑被测类自己的预算逻辑，真实链路则由工厂接管握手期的读取。
     */
    private static void armHandshakeDeadline(Session session, long deadlineNanos, int budgetMillis) {
        session.setSocketFactory(new DeadlineSocketFactory(Thread.currentThread(), deadlineNanos,
                connectTimeoutMillis(deadlineNanos), budgetMillis));
    }

    /**
     * 让"总截止"与"取消"能在**阻塞期间**生效的套接字工厂。
     *
     * <p>JSch 给的连接超时只约束单次读，所以它管不住两件事：对端持续发数据时每次读都成功返回，
     * 总截止永远等不到；对端不说话时单次读又长得让取消陪等到预算尽头。这里换掉 JSch 拿到的
     * 输入流，把每次读压成 {@link #HANDSHAKE_READ_SLICE_MILLIS} 毫秒的小片，片与片之间检查
     * 绝对截止与**发起调用的线程**的中断标志；命中就关掉真实套接字再抛异常，让阻塞中的
     * {@code Session.connect} 立刻退出，而不是等对端主动关闭或等预算自然走完。
     *
     * <p>{@code createSocket} 里还要自己限时：JSch 走工厂分支时不再用连接超时约束 TCP 连接，
     * 不补这一步，连不上的目标就会绕开预算。
     *
     * <p>只对发起调用的线程生效：认证完成后 JSch 会另起收包线程从同一条流里取后续数据，
     * 那里没有"总截止"这回事，读超时必须保持 JSch 自己设的值——否则一个正常空闲的会话会被
     * 当成长时间无数据而掐断。
     */
    private static final class DeadlineSocketFactory implements SocketFactory {

        private final Thread caller;
        private final long deadlineNanos;
        private final int connectTimeoutMillis;
        private final int budgetMillis;
        private Socket socket;

        DeadlineSocketFactory(Thread caller, long deadlineNanos, int connectTimeoutMillis, int budgetMillis) {
            this.caller = caller;
            this.deadlineNanos = deadlineNanos;
            this.connectTimeoutMillis = connectTimeoutMillis;
            this.budgetMillis = budgetMillis;
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            Socket created = new Socket();
            created.connect(new InetSocketAddress(host, port), connectTimeoutMillis);
            this.socket = created;
            return created;
        }

        @Override
        public InputStream getInputStream(Socket socket) throws IOException {
            return new DeadlineInputStream(socket, socket.getInputStream());
        }

        @Override
        public OutputStream getOutputStream(Socket socket) throws IOException {
            return socket.getOutputStream();
        }

        /** 把真实套接字的读切成小片，片与片之间检查总截止与取消。 */
        private final class DeadlineInputStream extends InputStream {

            private final Socket underlying;
            private final InputStream delegate;

            DeadlineInputStream(Socket underlying, InputStream delegate) {
                this.underlying = underlying;
                this.delegate = delegate;
            }

            @Override
            public int read() throws IOException {
                byte[] single = new byte[1];
                int read = read(single, 0, 1);
                return read < 0 ? -1 : (single[0] & 0xFF);
            }

            @Override
            public int read(byte[] buffer, int offset, int length) throws IOException {
                if (length == 0) {
                    return 0;
                }
                if (Thread.currentThread() != caller) {
                    // JSch 认证之后的收包线程：不套用总截止与取消，行为与改动前一致。
                    return delegate.read(buffer, offset, length);
                }
                while (true) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw abort("SSH 握手被取消");
                    }
                    if (System.nanoTime() - deadlineNanos >= 0) {
                        throw abort("SSH 握手在 " + budgetMillis + " ms 的总预算内未完成");
                    }
                    // 每片都要重新压一次读超时：JSch 在 connect 里会把套接字的读超时改成连接超时，
                    // 那之后单次读又会变成"能等多久等多久"，切片就白切了。
                    int restore = underlying.getSoTimeout();
                    underlying.setSoTimeout(HANDSHAKE_READ_SLICE_MILLIS);
                    try {
                        return delegate.read(buffer, offset, length);
                    } catch (SocketTimeoutException sliceElapsed) {
                        // 这一片没等到数据：回到循环头重新判断截止与中断，
                        // 而不是把"单次读超时"当成握手失败抛给 JSch。
                    } finally {
                        underlying.setSoTimeout(restore);
                    }
                }
            }

            @Override
            public int available() throws IOException {
                return delegate.available();
            }

            @Override
            public void close() throws IOException {
                delegate.close();
            }

            /** 主动关掉真实套接字，再把失败抛回去：阻塞在里面的 {@code connect} 立刻就能返回。 */
            private IOException abort(String reason) {
                try {
                    underlying.close();
                } catch (IOException ignored) {
                    // 收尾路径：本来就是要关掉它，关不掉说明已经不在了
                }
                return new IOException(reason);
            }
        }
    }

    /**
     * 把一条子进程输出流排空到一个守护线程。
     *
     * <p>存在的意义有两个：其一，{@code ProcessBuilder} 的两条管道各有固定缓冲，
     * 先 {@code readAllBytes()} stdout 再读 stderr，在 stderr 写满时会与子进程互等；
     * 其二，主线程必须能去 {@code waitFor(timeout)}，而不是被一条阻塞式读取钉死
     * —— 钉死的线程无法响应中断，也就无法执行"杀进程"这一步。
     */
    private static final class StreamDrain {

        private final Thread thread;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private final InputStream stream;

        private StreamDrain(InputStream stream, String name) {
            this.stream = stream;
            this.thread = new Thread(this::pump, name);
            this.thread.setDaemon(true);
        }

        static StreamDrain start(InputStream stream, String name) {
            StreamDrain drain = new StreamDrain(stream, name);
            drain.thread.start();
            return drain;
        }

        private void pump() {
            byte[] chunk = new byte[8192];
            try (InputStream source = stream) {
                int read;
                while ((read = source.read(chunk)) >= 0) {
                    buffer.write(chunk, 0, read);
                }
            } catch (IOException ignored) {
                // 进程被强杀后读取会抛异常，此时已有的字节仍然有效，直接收尾
            }
        }

        /** 等待排空结束并返回文本；进程已被杀掉后流必然 EOF，这里的等待是有界的。 */
        String await() {
            try {
                thread.join(DRAIN_JOIN_MILLIS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            return buffer.toString(StandardCharsets.UTF_8);
        }

        void closeQuietly() {
            try {
                stream.close();
            } catch (IOException ignored) {
                // 收尾路径，关不掉也无从处理
            }
        }
    }
}

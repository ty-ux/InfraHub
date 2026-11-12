package com.infrahub.infra.ssh;

import com.infrahub.common.exception.BizException;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 采集超时/取消的**底层回收**证据（R5）。
 *
 * <p><b>为什么必须有这个类</b>：R5 的原话是"不能只是调用 {@code Future.cancel(true)}
 * 就宣称底层已停止"。{@code Future.cancel(true)} 只做一件事——插一个中断标志；
 * 如果下层实现正阻塞在 {@code readAllBytes()} 或无限期 {@code process.waitFor()}，
 * 中断根本落不到子进程上，于是"取消"之后 {@code ssh} 子进程照样在跑、
 * 线程照样被占着，采集每轮再压一层，机器慢慢变卡且进程表里堆满 ssh。
 * 采集侧的 {@code HostInstanceCollectionServiceTest} 只能证明**调度侧**有界
 * （拒绝而不是排队、shutdown 有界），证明不了子进程真的死了；那个证据只能在这里出。
 *
 * <p><b>怎么做到确定性</b>：不依赖任何真实 SSH 服务器、不依赖外网。测试在回环地址上起一个
 * **只 accept、永远不说话**的 {@link ServerSocket}，让 {@code ssh} 卡在"等待服务端 banner"
 * 这一步（已实测：该状态下 ssh 不会自己退出）。于是：
 * <ul>
 *   <li>给它一个 1.2s 预算 → 只能靠下层杀进程收场；</li>
 *   <li>给它 60s 预算但从中断调用线程 → 只能靠下层响应中断并杀进程收场。</li>
 * </ul>
 * 两种情形都靠**子进程 pid 真的消失**来判定，而不是"调用方不再等待"。
 *
 * <p><b>探测子进程身份</b>：远端命令串里带一个唯一 marker，用
 * {@link ProcessHandle#allProcesses()} 按命令行匹配。若当前 JVM 看不到其它进程的命令行
 * （权限/平台限制），本类会 {@code Skipped} 并把原因写清楚——**不会**因为看不到就假装通过。
 *
 * <p><b>密码分支（F3）用两套互补的假件</b>：
 * <ul>
 *   <li><b>回环静默服务端</b>：能证明"预算是多少就真的跑多久"和"收场时真实套接字被关掉"，
 *       因为这里走的是 JSch 真实实现、真实 TCP 套接字；</li>
 *   <li><b>回环"碎嘴"服务端</b>（{@link TrickleBannerServer}）：能证明"总截止是真的总截止"——
 *       对端每 40ms 发一条合成提示时，单次读永远能成功返回，只有真正的总截止才收得住场；</li>
 *   <li><b>假会话/假通道</b>（{@link StubSshExecutor}）：回环服务端走不通完整的 SSH 握手
 *       （要 KEX、认证、通道协议），"已连上之后的通道/命令阶段还认不认同一个总预算"就只能
 *       用可控的会话与通道来跑；离线环境也没有可用的嵌入式 SSH 服务端。</li>
 * </ul>
 * 三者证明的是不同的东西，交付说明里必须写明哪条结论来自哪一套——不能拿假会话的结论
 * 冒充真实链路的证据。
 */
class CliSshExecutorServiceProcessReclamationTest {

    /** 停机/收尾时留给子进程消失的时间上限 */
    private static final long RECLAIM_TIMEOUT_MILLIS = 5_000L;

    private static final String OBSERVE_MARKER_PREFIX = "infrahub-reclaim-";

    private static boolean sshUsable;
    private static String sshUnavailableReason;
    private static boolean childObservable;

    private final CliSshExecutorService service = new CliSshExecutorService();
    private final List<SilentBannerServer> servers = new ArrayList<>();
    private final List<TrickleBannerServer> trickleServers = new ArrayList<>();
    private final List<Long> observedChildPids = new ArrayList<>();

    @BeforeAll
    static void probeEnvironment() {
        // 只探测一次：ssh 是否可用、能否看到其它进程的命令行。
        try {
            Process probe = new ProcessBuilder("ssh", "-V").redirectErrorStream(true).start();
            boolean exited = probe.waitFor(10, TimeUnit.SECONDS);
            if (!exited) {
                probe.destroyForcibly();
                sshUsable = false;
                sshUnavailableReason = "本机 ssh 探测进程 10s 未退出，跳过底层回收验证";
            } else {
                sshUsable = true;
            }
        } catch (IOException exception) {
            sshUsable = false;
            sshUnavailableReason = "本机没有可用的 ssh 客户端（" + exception.getMessage()
                    + "），无法验证 CLI 分支的子进程回收";
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            sshUsable = false;
            sshUnavailableReason = "探测 ssh 可用性时被中断";
        }
        childObservable = sshUsable && probeChildObservability();
        System.out.println("[it-ssh-reclaim] sshUsable=" + sshUsable
                + " childObservable=" + childObservable
                + (sshUnavailableReason == null ? "" : " reason=" + sshUnavailableReason));
    }

    /**
     * 真正探一次"能不能观测到本进程拉起的子进程"。
     *
     * <p>两个坑：其一，不能拿 {@code ProcessHandle.current().info().commandLine()} 当门槛——
     * 本机实测它恒为空（Windows 上常见），会把两条关键用例无谓跳过；其二，也不能靠"子进程
     * 的命令行里有没有 marker"找子进程——本机实测别的进程的 {@code info().commandLine()}
     * 一样是空（只有 {@code command()} 能看到 {@code ssh.exe}）。
     * 可靠且跨平台的做法是**父子关系**：子进程是当前 JVM 的直接子进程，用
     * {@link ProcessHandle#children()} 在调用前后做差集；拿到句柄之后 {@code isAlive()} 即权威判定。
     */
    private static boolean probeChildObservability() {
        SilentBannerServer server = null;
        Process child = null;
        try {
            server = new SilentBannerServer();
            Set<Long> before = childPids();
            child = new ProcessBuilder("ssh", "-o", "StrictHostKeyChecking=no",
                    "-i", "C:/not-a-real-key-for-visibility-probe",
                    "-p", String.valueOf(server.port()),
                    "infrahub-probe@127.0.0.1", "echo visibility-probe")
                    .redirectErrorStream(true)
                    .start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline) {
                for (ProcessHandle handle : ProcessHandle.current().children().toList()) {
                    if (!before.contains(handle.pid()) && handle.isAlive()) {
                        return true;
                    }
                }
                Thread.sleep(100L);
            }
            return false;
        } catch (IOException exception) {
            return false;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            if (child != null) {
                child.destroyForcibly();
                try {
                    child.waitFor(RECLAIM_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }
            if (server != null) {
                server.close();
            }
        }
    }

    @AfterEach
    void tearDown() {
        for (SilentBannerServer server : servers) {
            server.close();
        }
        servers.clear();
        for (TrickleBannerServer server : trickleServers) {
            server.close();
        }
        trickleServers.clear();
        // 收尾：本测试见过的子进程必须都已消失，否则就是"取消之后底层还在跑"。
        for (Long pid : observedChildPids) {
            awaitProcessGone(pid, RECLAIM_TIMEOUT_MILLIS);
        }
        observedChildPids.clear();
    }

    @Test
    @DisplayName("单目标超时：到点后底层 ssh 子进程真的被杀掉，而不是只让调用方不再等")
    void timeoutKillsTheUnderlyingSshProcess() throws Exception {
        assumeTrue(sshUsable, sshUnavailableReason);
        assumeTrue(childObservable, "当前 JVM 观察不到自己拉起的子进程，无法判定子进程是否真的结束");

        SilentBannerServer server = startSilentServer();
        String marker = OBSERVE_MARKER_PREFIX + UUID.randomUUID();
        long budgetMillis = 1_200L;
        SshExecutionRequest request = privateKeyRequest(server.port(), marker, budgetMillis);

        AtomicReference<SshExecutionResult> result = new AtomicReference<>();
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            try {
                result.set(service.execute(request));
            } catch (Throwable throwable) {
                thrown.set(throwable);
            }
        }, "ssh-timeout-caller");
        caller.setDaemon(true);

        long startedAtNanos = System.nanoTime();
        // 基线必须在拉起调用线程**之前**取：否则被测代码掐起来的 ssh 已经在基线里，
        // 差集永远是空的，测试会误判成"底层没起进程"。
        Set<Long> before = childPids();
        caller.start();
        ChildObservation child = awaitNewChild(before, 4_000L);
        assertThat(child.aliveSeen())
                .as("必须先确认底层子进程真的起来了，否则后面的'被杀掉'没有意义")
                .isTrue();
        observedChildPids.add(child.pid());

        caller.join(10_000L);
        long elapsedMillis = (System.nanoTime() - startedAtNanos) / 1_000_000;

        assertThat(caller.isAlive()).as("超时必须让调用线程收尾").isFalse();
        assertThat(thrown.get()).as("超时是正常返回的失败结果，不是异常").isNull();
        SshExecutionResult timedOut = result.get();
        assertThat(timedOut).isNotNull();
        assertThat(timedOut.getExitCode()).as("超时统一用 -1 表示没有退出码").isEqualTo(-1);
        assertThat(timedOut.getStderr())
                .as("超时摘要必须写明预算，便于现场定位")
                .contains("timed out after " + budgetMillis + " ms");
        assertThat(elapsedMillis)
                .as("预算 1.2s，返回时间允许有调度/杀进程的余量，但不能是无限期")
                .isLessThan(10_000L);

        boolean gone = awaitProcessGone(child.pid(), RECLAIM_TIMEOUT_MILLIS);
        System.out.println("[it-ssh-reclaim] timeout elapsedMillis=" + elapsedMillis
                + " childPid=" + child.pid() + " childGone=" + gone);
        assertThat(gone)
                .as("超时之后子进程必须真的死了；只调 destroyForcibly 而不等它结束就会有漏网进程")
                .isTrue();
    }

    @Test
    @DisplayName("调用方被中断（Future.cancel(true) 的真实路径）：底层 ssh 子进程同样被杀掉")
    void interruptKillsTheUnderlyingSshProcess() throws Exception {
        assumeTrue(sshUsable, sshUnavailableReason);
        assumeTrue(childObservable, "当前 JVM 观察不到自己拉起的子进程，无法判定子进程是否真的结束");

        SilentBannerServer server = startSilentServer();
        String marker = OBSERVE_MARKER_PREFIX + UUID.randomUUID();
        // 预算给足 60s：这条用例只允许"中断"这一个原因让它结束。
        SshExecutionRequest request = privateKeyRequest(server.port(), marker, 60_000L);

        AtomicReference<Throwable> thrown = new AtomicReference<>();
        AtomicBoolean returnedNormally = new AtomicBoolean(false);
        Thread caller = new Thread(() -> {
            try {
                service.execute(request);
                returnedNormally.set(true);
            } catch (Throwable throwable) {
                thrown.set(throwable);
            }
        }, "ssh-interrupt-caller");
        caller.setDaemon(true);

        Set<Long> before = childPids();
        caller.start();
        ChildObservation child = awaitNewChild(before, 4_000L);
        assertThat(child.aliveSeen()).as("子进程必须先起来").isTrue();
        observedChildPids.add(child.pid());

        long interruptedAtNanos = System.nanoTime();
        caller.interrupt();
        caller.join(10_000L);
        long elapsedMillis = (System.nanoTime() - interruptedAtNanos) / 1_000_000;

        assertThat(caller.isAlive())
                .as("中断必须真正落到下层：阻塞的 waitFor 若不可中断，采集线程会一直被钉住")
                .isFalse();
        assertThat(returnedNormally.get()).as("被中断的调用不该返回正常结果").isFalse();
        assertThat(thrown.get())
                .as("中断路径应报 BizException，而不是吞掉或抛 Checked 异常")
                .isInstanceOf(BizException.class);
        assertThat(thrown.get().getMessage()).contains("被中断");

        boolean gone = awaitProcessGone(child.pid(), RECLAIM_TIMEOUT_MILLIS);
        System.out.println("[it-ssh-reclaim] interrupt elapsedMillis=" + elapsedMillis
                + " childPid=" + child.pid() + " childGone=" + gone);
        assertThat(gone)
                .as("这正是 Future.cancel(true) 走的路：取消之后子进程必须消失")
                .isTrue();
    }

    @Test
    @DisplayName("既有发布调用方不传超时：仍走默认预算，不会因为新加的字段被提前杀掉")
    void releaseCallersWithoutTimeoutKeepTheDefaultBudget() throws Exception {
        assumeTrue(sshUsable, sshUnavailableReason);

        // 连一个**没有人监听**的回环端口：ssh 连接被拒会自己快速退出。
        int closedPort = closedLoopbackPort();
        String marker = OBSERVE_MARKER_PREFIX + UUID.randomUUID();
        SshExecutionRequest request = SshExecutionRequest.builder()
                .host("127.0.0.1")
                .port(closedPort)
                .username("infrahub-probe")
                .authType("PRIVATE_KEY")
                .privateKeyPath("C:/not-a-real-key-for-compat-check")
                .command("echo " + marker)
                .build();
        assertThat(request.getTimeoutMillis()).as("这条用例的前提就是没有传超时").isNull();

        long startedAtNanos = System.nanoTime();
        SshExecutionResult result = service.execute(request);
        long elapsedMillis = (System.nanoTime() - startedAtNanos) / 1_000_000;

        System.out.println("[it-ssh-reclaim] no-timeout exitCode=" + result.getExitCode()
                + " elapsedMillis=" + elapsedMillis + " success=" + result.isSuccess());
        assertThat(result.isSuccess()).as("连不上的端口应当是失败的执行结果").isFalse();
        assertThat(result.getStderr())
                .as("默认预算是 120s，这里进程自己结束得早，不该出现超时摘要")
                .doesNotContain("timed out after");
        assertThat(result.getExitCode())
                .as("自己结束的进程必须留下真实的退出码，而不是超时用的 -1")
                .isNotEqualTo(-1);
        assertThat(elapsedMillis).as("默认预算下不会立刻被杀").isLessThan(60_000L);
    }

    @Test
    @DisplayName("密码分支：连接超时也夹在预算之内，不会先把 10s 花在连不上")
    void passwordBranchConnectTimeoutIsClampedIntoBudget() throws Exception {
        assumeTrue(sshUsable, sshUnavailableReason);

        SilentBannerServer server = startSilentServer();
        long budgetMillis = 800L;
        SshExecutionRequest request = SshExecutionRequest.builder()
                .host("127.0.0.1")
                .port(server.port())
                .username("infrahub-probe")
                .authType("PASSWORD")
                .password("not-a-real-password")
                .command("echo infrahub-reclaim-password-branch")
                .timeoutMillis(budgetMillis)
                .build();

        AtomicReference<Throwable> thrown = new AtomicReference<>();
        long startedAtNanos = System.nanoTime();
        try {
            service.execute(request);
        } catch (Throwable throwable) {
            thrown.set(throwable);
        }
        long elapsedMillis = (System.nanoTime() - startedAtNanos) / 1_000_000;

        System.out.println("[it-ssh-reclaim] password-branch elapsedMillis=" + elapsedMillis
                + " thrown=" + (thrown.get() == null ? "none" : thrown.get().getClass().getSimpleName()));
        assertThat(thrown.get())
                .as("对方不说话，握手必须失败而不是一直挂着")
                .isNotNull();
        assertThat(elapsedMillis)
                .as("连接超时取 min(10s, 预算)，因此 800ms 的预算必须明显快于 10s 收场")
                .isLessThan(6_000L);
    }

    // ---------- 密码分支：同一个单调时钟总截止（F3） ----------

    @Test
    @DisplayName("密码分支：100ms 预算就按 100ms 收场（旧实现会放大成 1000ms），并且真的关掉了套接字")
    void passwordBranchHonoursTheBudgetInsteadOfAmplifyingIt() throws Exception {
        SilentBannerServer server = startSilentServer();
        long budgetMillis = 100L;

        AtomicReference<Throwable> thrown = new AtomicReference<>();
        long startedAtNanos = System.nanoTime();
        try {
            service.execute(passwordRequest(server.port(), budgetMillis));
        } catch (Throwable throwable) {
            thrown.set(throwable);
        }
        long elapsedMillis = (System.nanoTime() - startedAtNanos) / 1_000_000;

        Socket accepted = server.awaitAccepted(3_000L);
        assertThat(accepted)
                .as("必须先确认真的连上了这个假服务端，否则后面的「收场时间」没有意义")
                .isNotNull();
        boolean peerClosed = awaitPeerClosed(accepted, 3_000L);

        System.out.println("[it-ssh-reclaim] password-budget budgetMs=" + budgetMillis
                + " elapsedMillis=" + elapsedMillis + " peerClosed=" + peerClosed
                + " thrown=" + (thrown.get() == null ? "none" : thrown.get().getClass().getSimpleName()));

        assertThat(thrown.get()).as("对方不说话，握手必须失败而不是一直挂着").isNotNull();
        assertThat(elapsedMillis)
                .as("旧实现把连接超时写成 max(1_000, 预算)，100ms 的预算实际要跑 1s 以上；"
                        + "现在连接只能花剩余预算，100ms 必须明显快于 1000ms")
                .isLessThan(600L);
        assertThat(peerClosed)
                .as("收场必须把真实套接字关掉，而不是只让调用方不再等")
                .isTrue();
    }

    @Test
    @DisplayName("密码分支连接阶段被取消：仍在预算内有界结束，套接字关掉，错误如实标成「被中断」")
    void passwordBranchCancelledDuringConnectEndsBoundedAndClosesTheSocket() throws Exception {
        SilentBannerServer server = startSilentServer();
        long budgetMillis = 300L;

        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            try {
                service.execute(passwordRequest(server.port(), budgetMillis));
            } catch (Throwable throwable) {
                thrown.set(throwable);
            }
        }, "ssh-password-connect-cancel");
        caller.setDaemon(true);

        long startedAtNanos = System.nanoTime();
        caller.start();
        Socket accepted = server.awaitAccepted(3_000L);
        assertThat(accepted).as("先确认真的连上了这个假服务端").isNotNull();

        // 中断落在 session.connect 内部：JSch 的阻塞读不响应中断，只能靠剩余预算收场
        Thread.sleep(100L);
        caller.interrupt();
        caller.join(5_000L);
        long elapsedMillis = (System.nanoTime() - startedAtNanos) / 1_000_000;

        boolean peerClosed = awaitPeerClosed(accepted, 3_000L);

        System.out.println("[it-ssh-reclaim] password-connect-cancel budgetMs=" + budgetMillis
                + " elapsedMillis=" + elapsedMillis + " peerClosed=" + peerClosed
                + " thrown=" + thrown.get());

        assertThat(caller.isAlive()).as("取消之后调用线程必须有界结束").isFalse();
        assertThat(thrown.get()).isInstanceOf(BizException.class);
        assertThat(thrown.get().getMessage())
                .as("中断与握手失败可能同时可见，但真正的原因是取消，不能记成一次网络故障")
                .contains("被中断");
        assertThat(elapsedMillis)
                .as("预算 300ms；不得因中断又被放大成旧的 1000ms 连接超时")
                .isLessThan(800L);
        assertThat(peerClosed).as("取消路径同样必须把真实套接字关掉").isTrue();
    }

    @Test
    @DisplayName("密码分支：大预算下提前取消——不等满剩余预算，600ms 内线程退出、真实套接字关掉")
    void passwordBranchCancelledEarlyDoesNotWaitOutTheRemainingBudget() throws Exception {
        SilentBannerServer server = startSilentServer();
        // 预算给足 5s：中断后若还占着线程和套接字，唯一的解释就是"阻塞读把中断吞了"。
        long budgetMillis = 5_000L;

        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            try {
                service.execute(passwordRequest(server.port(), budgetMillis));
            } catch (Throwable throwable) {
                thrown.set(throwable);
            }
        }, "ssh-password-early-cancel");
        caller.setDaemon(true);

        long startedAtNanos = System.nanoTime();
        caller.start();
        Socket accepted = server.awaitAccepted(3_000L);
        assertThat(accepted).as("先确认真的连上了这个假服务端，否则后面的「提前取消」没有意义").isNotNull();

        // 取消发生在 session.connect 内部的阻塞读上：旧实现只会在读返回之后才看见中断，
        // 于是要陪等近 5s（实测约 4.9s），期间线程与真实套接字一直被占着。
        Thread.sleep(100L);
        long cancelledAtNanos = System.nanoTime();
        caller.interrupt();
        caller.join(1_000L);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - cancelledAtNanos);
        long totalMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
        boolean stillRunningAfterJoin = caller.isAlive();
        boolean peerClosed = awaitPeerClosed(accepted, 2_000L);

        System.out.println("[it-ssh-reclaim] password-early-cancel budgetMs=" + budgetMillis
                + " totalMillis=" + totalMillis + " elapsedMillis=" + elapsedMillis
                + " stillRunning=" + stillRunningAfterJoin + " peerClosed=" + peerClosed
                + " thrown=" + (thrown.get() == null ? "none" : thrown.get().getClass().getSimpleName()));

        assertThat(stillRunningAfterJoin)
                .as("取消必须能在阻塞读之中生效：旧实现此时还在读里，要等到预算尽头的读超时才回头")
                .isFalse();
        assertThat(totalMillis)
                .as("预算 5000ms、中断时剩余约 4900ms：整通调用（连接 + 取消 + 收尾）必须远早于预算结束，"
                        + "旧实现实测约 4907ms")
                .isLessThan(1_000L);
        assertThat(elapsedMillis)
                .as("从发出取消到线程结束同样要有界")
                .isLessThan(1_000L);
        assertThat(peerClosed)
                .as("取消必须主动关掉真实套接字（对端能读到 EOF），而不是只让调用方不再等")
                .isTrue();
        assertThat(thrown.get()).isInstanceOf(BizException.class);
        assertThat(thrown.get().getMessage())
                .as("主动取消不能被记成一次网络故障")
                .contains("被中断");
    }

    @Test
    @DisplayName("密码分支：对端每 40ms 发一条提示也救不了预算——不能靠每次读超时续命")
    void passwordBranchTricklingPeerCannotOutliveTheBudget() throws Exception {
        long budgetMillis = 100L;
        // 40 条 × 40ms ≈ 1600ms：远长于预算。旧实现每次读都能成功返回，于是要等对端发完并关闭才收场。
        TrickleBannerServer server = startTrickleServer(40, 40L);

        AtomicReference<Throwable> thrown = new AtomicReference<>();
        long startedAtNanos = System.nanoTime();
        try {
            service.execute(passwordRequest(server.port(), budgetMillis));
        } catch (Throwable throwable) {
            thrown.set(throwable);
        }
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);

        Socket accepted = server.awaitAccepted(3_000L);
        assertThat(accepted).as("先确认真的连上了这个假服务端").isNotNull();
        boolean peerClosed = awaitPeerClosed(accepted, 2_000L);

        System.out.println("[it-ssh-reclaim] password-trickle budgetMs=" + budgetMillis
                + " elapsedMillis=" + elapsedMillis + " peerClosed=" + peerClosed
                + " thrown=" + (thrown.get() == null ? "none" : thrown.get().getMessage()));

        assertThat(thrown.get()).as("对端一直发数据也不改变结论：握手必须在总预算内收场").isNotNull();
        assertThat(elapsedMillis)
                .as("预算 100ms，对端的碎嘴要持续约 1600ms；贴住 100ms 才算「总截止」，"
                        + "而不是被单次读成功续命到对端关闭（旧实现实测约 949ms）")
                .isLessThan(800L);
        assertThat(peerClosed).as("预算到点同样要主动关掉真实套接字，而不是等对端关闭").isTrue();
        assertThat(thrown.get().getMessage())
                .as("收场原因必须是「超过总预算」，不能是「对端把连接关了」——后者说明是靠对端收的场")
                .contains("总预算")
                .doesNotContain("foreign host");
    }

    @Test
    @DisplayName("连接阶段就把预算花光：不再开通道、不再发命令，也不会再补一份新预算")
    void connectThatSpendsTheWholeBudgetIsNotRewardedWithAFreshOne() throws Exception {
        int budgetMillis = 300;
        Session session = slowConnectingSession(500L);
        ChannelExec channel = spinningChannel();
        StubSshExecutor executor = new StubSshExecutor(session, channel);

        SshExecutionResult result = executor.execute(passwordRequest(1234, budgetMillis));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getStderr()).contains("timed out after " + budgetMillis + " ms");
        assertThat(executor.channelOpens())
                .as("预算已被连接阶段花光，通道阶段不该再开工")
                .isZero();
        verify(channel, never()).connect(anyInt());

        ArgumentCaptor<Integer> connectTimeout = ArgumentCaptor.forClass(Integer.class);
        verify(session).connect(connectTimeout.capture());
        assertThat(connectTimeout.getValue())
                .as("连接超时只能是剩余预算：放大到 1000ms 会让 300ms 预算跑到 1s 以上；"
                        + "而 0 会被 JSch 解读成「无限等」")
                .isBetween(1, budgetMillis);
        verify(session).disconnect();
    }

    @Test
    @DisplayName("已连上的命令阶段：只认总预算，不会再领一份新预算")
    void connectedCommandPhaseSharesTheSameTotalBudget() throws Exception {
        int budgetMillis = 600;
        Session session = slowConnectingSession(500L);
        ChannelExec channel = spinningChannel();
        StubSshExecutor executor = new StubSshExecutor(session, channel);

        long startedAtNanos = System.nanoTime();
        SshExecutionResult result = executor.execute(passwordRequest(1234, budgetMillis));
        long elapsedMillis = (System.nanoTime() - startedAtNanos) / 1_000_000;

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getStderr()).contains("timed out after " + budgetMillis + " ms");
        assertThat(executor.channelOpens()).as("通道确实开过：这一条覆盖的正是已连接的命令阶段").isEqualTo(1);

        ArgumentCaptor<Integer> granted = ArgumentCaptor.forClass(Integer.class);
        verify(session).connect(granted.capture());
        verify(channel).connect(granted.capture());
        assertThat(granted.getAllValues())
                .as("会话连接与通道连接都只能花剩余预算，不能各自放大到 1000ms，也不能是 0（=无限等）")
                .allMatch(timeout -> timeout >= 1 && timeout <= budgetMillis);

        System.out.println("[it-ssh-reclaim] password-command-phase budgetMs=" + budgetMillis
                + " elapsedMillis=" + elapsedMillis + " granted=" + granted.getAllValues());
        assertThat(elapsedMillis)
                .as("命令阶段若另起一份 600ms 预算，总耗时会超过 1100ms；共用一个总截止则应贴住 600ms")
                .isLessThan(850L);
        verify(channel).disconnect();
        verify(session).disconnect();
    }

    @Test
    @DisplayName("已连上的命令阶段被取消：抛出「被中断」并真的断开通道与会话，而不只是插了个中断标志")
    void cancelledCommandPhaseDisconnectsTheRealChannelAndSession() throws Exception {
        Session session = mock(Session.class);
        ChannelExec channel = spinningChannel();
        StubSshExecutor executor = new StubSshExecutor(session, channel);

        AtomicReference<Throwable> thrown = new AtomicReference<>();
        AtomicBoolean returnedNormally = new AtomicBoolean(false);
        Thread caller = new Thread(() -> {
            try {
                // 预算给足 60s：这条用例只允许「中断」这一个原因让它结束
                executor.execute(passwordRequest(1234, 60_000));
                returnedNormally.set(true);
            } catch (Throwable throwable) {
                thrown.set(throwable);
            }
        }, "ssh-password-cancel-caller");
        caller.setDaemon(true);
        caller.start();

        // 先等它真的进到命令阶段，否则证明不了「已连接的那一段」的确被覆盖到了
        long openDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (executor.channelOpens() == 0 && System.nanoTime() < openDeadline) {
            Thread.sleep(20L);
        }
        assertThat(executor.channelOpens()).as("命令阶段必须先真的开始").isEqualTo(1);

        long interruptedAtNanos = System.nanoTime();
        caller.interrupt();
        caller.join(5_000L);
        long elapsedMillis = (System.nanoTime() - interruptedAtNanos) / 1_000_000;

        System.out.println("[it-ssh-reclaim] password-command-cancel elapsedMillis=" + elapsedMillis
                + " thrown=" + thrown.get());

        assertThat(caller.isAlive())
                .as("命令阶段必须在中断后有界结束（轮询 sleep 会响应中断），不能等满 60s 预算")
                .isFalse();
        assertThat(returnedNormally.get()).isFalse();
        assertThat(thrown.get()).isInstanceOf(BizException.class);
        assertThat(thrown.get().getMessage()).contains("被中断");
        assertThat(elapsedMillis)
                .as("60s 的预算下被取消，必须在秒级内收场，而不是等预算走完")
                .isLessThan(5_000L);

        // 真正的回收动作：断开通道与会话，而不是只让调用方不再等
        verify(channel).disconnect();
        verify(session).disconnect();
    }

    // ---------- 静默服务端 ----------

    /** 只 accept、永远不回 banner 的服务端：让 ssh 卡在握手等待上。 */
    private SilentBannerServer startSilentServer() throws IOException {
        SilentBannerServer server = new SilentBannerServer();
        servers.add(server);
        return server;
    }

    /** 一直说话、但说的都不是合法版本串的服务端：专门用来打"单次读超时冒充总截止"这个假。 */
    private TrickleBannerServer startTrickleServer(int notices, long intervalMillis) throws IOException {
        TrickleBannerServer server = new TrickleBannerServer(notices, intervalMillis);
        trickleServers.add(server);
        return server;
    }

    private static final class SilentBannerServer {

        private final ServerSocket serverSocket;
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final List<Socket> accepted = new ArrayList<>();

        SilentBannerServer() throws IOException {
            this.serverSocket = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
            Thread acceptor = new Thread(this::acceptLoop, "silent-banner-accept");
            acceptor.setDaemon(true);
            acceptor.start();
        }

        int port() {
            return serverSocket.getLocalPort();
        }

        /**
         * 等第一个被接受的连接。
         *
         * <p>密码分支的断言依赖"真的连上了"这个前提：拿不到被接受的套接字，就没法证明它之后
         * 被关掉，也没法证明连接阶段真的跑过。等不到就返回 null，由调用方断言失败，绝不静默跳过。
         */
        Socket awaitAccepted(long timeoutMillis) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
            while (System.nanoTime() < deadline) {
                synchronized (accepted) {
                    if (!accepted.isEmpty()) {
                        return accepted.get(0);
                    }
                }
                Thread.sleep(20L);
            }
            return null;
        }

        private void acceptLoop() {
            while (running.get()) {
                try {
                    Socket socket = serverSocket.accept();
                    // 收到就攥着，不写一个字节：ssh 会一直等服务端 banner。
                    synchronized (accepted) {
                        accepted.add(socket);
                    }
                } catch (IOException exception) {
                    return;
                }
            }
        }

        void close() {
            running.set(false);
            synchronized (accepted) {
                for (Socket socket : accepted) {
                    try {
                        socket.close();
                    } catch (IOException ignored) {
                        // 收尾路径，关不掉也无从处理
                    }
                }
                accepted.clear();
            }
            try {
                serverSocket.close();
            } catch (IOException ignored) {
                // 同上
            }
        }
    }

    /** 取一个当前没人监听的回环端口。 */
    private static int closedLoopbackPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    /**
     * 会**一直说话**的假服务端：接受连接后每 {@code intervalMillis} 发一条合成提示行。
     *
     * <p>它是"总截止必须是真的总截止"的对手方。JSch 的连接超时只约束单次读，对端只要持续发数据，
     * 每次读都会成功返回，于是一个只把预算交给 JSch 的实现能一直活到对端自己关闭为止
     * （实测：100ms 预算撑到 949ms 才因对端关闭而结束）。
     *
     * <p>行内容故意**不是** {@code SSH-2.0-} 开头：合法版本串会让 JSch 进入 KEX，测的就不再是
     * "握手等不到想要的东西"了。JSch 会跳过不认识的提示行继续读，正是这条路径让单次读总能成功。
     *
     * <p>收尾时**不主动关闭已接受的连接**：这样调用方读到的 EOF 只可能来自客户端自己关闭套接字，
     * 否则"对端读到 EOF"这条断言会被服务端自己的关闭动作伪造出来。
     */
    private static final class TrickleBannerServer {

        private final ServerSocket serverSocket;
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final List<Socket> accepted = new ArrayList<>();
        private final int notices;
        private final long intervalMillis;

        TrickleBannerServer(int notices, long intervalMillis) throws IOException {
            this.notices = notices;
            this.intervalMillis = intervalMillis;
            this.serverSocket = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
            Thread acceptor = new Thread(this::acceptAndTrickle, "trickle-banner-accept");
            acceptor.setDaemon(true);
            acceptor.start();
        }

        int port() {
            return serverSocket.getLocalPort();
        }

        /** 与 {@link SilentBannerServer#awaitAccepted} 同样的约定：等不到就返回 null，由调用方断言失败。 */
        Socket awaitAccepted(long timeoutMillis) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
            while (System.nanoTime() < deadline) {
                synchronized (accepted) {
                    if (!accepted.isEmpty()) {
                        return accepted.get(0);
                    }
                }
                Thread.sleep(20L);
            }
            return null;
        }

        private void acceptAndTrickle() {
            Socket socket = null;
            try {
                socket = serverSocket.accept();
                synchronized (accepted) {
                    accepted.add(socket);
                }
                for (int sent = 0; sent < notices && running.get(); sent++) {
                    socket.getOutputStream().write("synthetic notice\r\n".getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                    Thread.sleep(intervalMillis);
                }
            } catch (IOException peerAlreadyGone) {
                // 客户端按总预算收场并关掉套接字之后，写就会失败：这正是期望的结局，直接收摊
            } catch (InterruptedException stop) {
                Thread.currentThread().interrupt();
            }
        }

        void close() {
            running.set(false);
            synchronized (accepted) {
                for (Socket socket : accepted) {
                    try {
                        socket.close();
                    } catch (IOException ignored) {
                        // 收尾路径，关不掉也无从处理
                    }
                }
                accepted.clear();
            }
            try {
                serverSocket.close();
            } catch (IOException ignored) {
                // 同上
            }
        }
    }

    private static SshExecutionRequest privateKeyRequest(int port, String marker, long timeoutMillis) {
        return SshExecutionRequest.builder()
                .host("127.0.0.1")
                .port(port)
                .username("infrahub-probe")
                .authType("PRIVATE_KEY")
                // 故意的：ssh 会对不存在的私钥告警，但仍然会去连服务端，于是卡在 banner 等待
                .privateKeyPath("C:/not-a-real-key-for-reclaim-check")
                .command("echo " + marker)
                .timeoutMillis(timeoutMillis)
                .build();
    }

    // ---------- 密码分支的合成请求与假会话 ----------

    /** 合成凭据：只为让 JSch 走到握手，指向的是回环假服务端，绝不涉及任何真实主机与真实凭据。 */
    private static SshExecutionRequest passwordRequest(int port, long timeoutMillis) {
        return SshExecutionRequest.builder()
                .host("127.0.0.1")
                .port(port)
                .username("infrahub-probe")
                .authType("PASSWORD")
                .password("not-a-real-password")
                .command("echo infrahub-reclaim-password-branch")
                .timeoutMillis(timeoutMillis)
                .build();
    }

    /**
     * 等对端把套接字关掉：读到 EOF 或连接被重置都算关闭，一直读超时则说明连接被漏在后台。
     *
     * <p>服务端这边也要先把客户端发来的 banner 读掉，所以不能只看第一次 read 的结果。
     */
    private static boolean awaitPeerClosed(Socket socket, long timeoutMillis) {
        try {
            socket.setSoTimeout((int) Math.min(timeoutMillis, 2_000L));
            InputStream stream = socket.getInputStream();
            byte[] buffer = new byte[256];
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
            while (System.nanoTime() < deadline) {
                if (stream.read(buffer) < 0) {
                    return true;
                }
            }
            return false;
        } catch (SocketTimeoutException stillOpen) {
            return false;
        } catch (IOException closed) {
            return true;
        }
    }

    /**
     * 假会话/假通道注入点。
     *
     * <p>回环静默服务端只能覆盖到"握手失败"那一段：要走到"已连上并发出命令"，需要完整实现
     * KEX、认证、通道协议，离线环境没有可用的嵌入式 SSH 服务端，手写一套又会把测试本身变成
     * 一个不可信的自制协议实现。因此这里覆盖被测类的两个连接接缝，用可控的会话与通道把
     * "通道/命令阶段还认不认同一个总预算、取消时会不会断开真实对象"这两件事确定性地跑出来。
     *
     * <p>它证明不了真实 JSch 的握手时长分布——那部分由回环静默服务端那条用例负责。
     */
    private static final class StubSshExecutor extends CliSshExecutorService {

        private final Session session;
        private final ChannelExec channel;
        private final AtomicInteger channelOpens = new AtomicInteger();

        StubSshExecutor(Session session, ChannelExec channel) {
            this.session = session;
            this.channel = channel;
        }

        @Override
        Session openPasswordSession(SshExecutionRequest request) {
            return session;
        }

        @Override
        ChannelExec openExecChannel(Session ignored) {
            channelOpens.incrementAndGet();
            return channel;
        }

        int channelOpens() {
            return channelOpens.get();
        }
    }

    /** 装成「握手花了 connectMillis 才成功」的假会话，用来把预算先吃掉一部分。 */
    private static Session slowConnectingSession(long connectMillis) throws JSchException {
        Session session = mock(Session.class);
        doAnswer(invocation -> {
            Thread.sleep(connectMillis);
            return null;
        }).when(session).connect(anyInt());
        return session;
    }

    /**
     * 装成"已连上、但命令永远不结束"的假通道：{@code isClosed()} 恒假、输出流为空，
     * 于是被测代码只能靠预算或中断收场——这正是"已连接的命令阶段"需要被证明的那一点。
     */
    private static ChannelExec spinningChannel() throws IOException {
        ChannelExec channel = mock(ChannelExec.class);
        when(channel.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(channel.isClosed()).thenReturn(false);
        when(channel.getExitStatus()).thenReturn(0);
        return channel;
    }

    // ---------- 子进程观测 ----------

    private record ChildObservation(boolean aliveSeen, long pid) {
    }

    /**
     * 等待当前 JVM 新出现一个子进程（就是被测代码拉起的 ssh），并记下它的 pid。
     *
     * <p>只关心"出现过且拿到了 pid"，因为真正要判定的是**之后它消失了**。
     * 用父子关系而不是命令行匹配，原因见 {@link #probeChildObservability()}。
     */
    private static ChildObservation awaitNewChild(Set<Long> before, long timeoutMillis) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadline) {
            for (ProcessHandle handle : ProcessHandle.current().children().toList()) {
                if (!before.contains(handle.pid()) && handle.isAlive()) {
                    System.out.println("[it-ssh-reclaim] observed child pid=" + handle.pid()
                            + " command=" + handle.info().command().orElse("<unknown>"));
                    return new ChildObservation(true, handle.pid());
                }
            }
            Thread.sleep(50L);
        }
        return new ChildObservation(false, -1L);
    }

    /** 当前 JVM 直接子进程的 pid 快照，用于"调用前后做差集"找到被测代码拉起的那个 ssh。 */
    private static Set<Long> childPids() {
        Set<Long> pids = new HashSet<>();
        for (ProcessHandle handle : ProcessHandle.current().children().toList()) {
            pids.add(handle.pid());
        }
        return pids;
    }

    private static boolean awaitProcessGone(long pid, long timeoutMillis) {
        if (pid <= 0) {
            return false;
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadline) {
            Optional<ProcessHandle> handle = ProcessHandle.of(pid);
            if (handle.isEmpty() || !handle.get().isAlive()) {
                return true;
            }
            try {
                Thread.sleep(50L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }
}
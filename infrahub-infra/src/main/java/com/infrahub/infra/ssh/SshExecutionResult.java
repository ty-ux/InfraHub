package com.infrahub.infra.ssh;

import lombok.Builder;
import lombok.Value;

import java.time.OffsetDateTime;

@Value
@Builder
public class SshExecutionResult {

    /**
     * 一次 SSH 命令执行能够给出的确定性证据。
     *
     * <p>{@code success=false} 不足以区分“远端明确失败”和“我们放弃等待”。后者（超时、连接中断、
     * 没有拿到退出状态）意味着远端命令可能仍在运行，任何自动回滚或“已安全结束”的判断都不能建立在
     * 它上面。判定只看结构化证据，不解析 stdout/stderr 文本。
     */
    public enum Evidence {
        /** 进程退出码为 0。 */
        SUCCEEDED,
        /** 拿到远端进程的非零退出码。 */
        FAILED,
        /** 超时、连接中断或没有拿到退出状态，无法确认远端命令是否结束。 */
        UNKNOWN
    }

    boolean success;
    int exitCode;
    String stdout;
    String stderr;
    OffsetDateTime startedAt;
    OffsetDateTime endedAt;
    Evidence evidence;

    public static SshExecutionResult success(String stdout, String stderr, OffsetDateTime startedAt, OffsetDateTime endedAt) {
        return SshExecutionResult.builder()
                .success(true)
                .exitCode(0)
                .stdout(stdout)
                .stderr(stderr)
                .startedAt(startedAt)
                .endedAt(endedAt)
                .evidence(Evidence.SUCCEEDED)
                .build();
    }

    /**
     * 拿到远端明确退出码的失败。{@code exitCode <= 0} 时无法证明远端执行过，按 {@link Evidence#UNKNOWN} 记录。
     */
    public static SshExecutionResult failure(int exitCode, String stdout, String stderr, OffsetDateTime startedAt, OffsetDateTime endedAt) {
        return SshExecutionResult.builder()
                .success(false)
                .exitCode(exitCode)
                .stdout(stdout)
                .stderr(stderr)
                .startedAt(startedAt)
                .endedAt(endedAt)
                .evidence(exitCode > 0 ? Evidence.FAILED : Evidence.UNKNOWN)
                .build();
    }

    /** 执行预算耗尽：命令可能仍在远端运行，结果未知。 */
    public static SshExecutionResult timedOut(String stdout, String stderr, OffsetDateTime startedAt, OffsetDateTime endedAt) {
        return SshExecutionResult.builder()
                .success(false)
                .exitCode(-1)
                .stdout(stdout)
                .stderr(stderr)
                .startedAt(startedAt)
                .endedAt(endedAt)
                .evidence(Evidence.UNKNOWN)
                .build();
    }

    /** 通道关闭但没有可用的退出状态（连接中断等），结果未知。 */
    public static SshExecutionResult unknown(int exitCode, String stdout, String stderr, OffsetDateTime startedAt, OffsetDateTime endedAt) {
        return SshExecutionResult.builder()
                .success(false)
                .exitCode(exitCode)
                .stdout(stdout)
                .stderr(stderr)
                .startedAt(startedAt)
                .endedAt(endedAt)
                .evidence(Evidence.UNKNOWN)
                .build();
    }

    /** OpenSSH 255 is ambiguous between a transport error and a remote exit status. */
    public static SshExecutionResult fromCliExit(int exitCode, String stdout, String stderr,
                                                 OffsetDateTime startedAt, OffsetDateTime endedAt) {
        if (exitCode == 0) {
            return success(stdout, stderr, startedAt, endedAt);
        }
        return exitCode == 255
                ? unknown(exitCode, stdout, stderr, startedAt, endedAt)
                : failure(exitCode, stdout, stderr, startedAt, endedAt);
    }

    /**
     * 兼容旧调用方：没显式给出证据时按退出码推导，{@code exitCode <= 0} 一律不猜成确定失败。
     */
    public Evidence resolvedEvidence() {
        if (evidence != null) {
            return evidence;
        }
        if (success) {
            return Evidence.SUCCEEDED;
        }
        return exitCode > 0 ? Evidence.FAILED : Evidence.UNKNOWN;
    }

    public String fullLog() {
        return "stdout:\n" + (stdout == null ? "" : stdout) + "\n\nstderr:\n" + (stderr == null ? "" : stderr);
    }
}

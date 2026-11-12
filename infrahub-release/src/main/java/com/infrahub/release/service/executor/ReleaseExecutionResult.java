package com.infrahub.release.service.executor;

import com.infrahub.release.support.DeployExecutionOutcome;
import lombok.Builder;
import lombok.Getter;

import java.time.OffsetDateTime;

/**
 * Result of a release execution operation.
 * Standardized result object returned by all ReleaseExecutor implementations.
 */
@Getter
@Builder
public class ReleaseExecutionResult {

    private final boolean success;
    private final String message;
    private final Long deployRecordId;
    private final String stdout;
    private final String stderr;
    /**
     * 执行器拼好的完整部署日志（可含前置检查）。外层负责写入**唯一一条**部署记录，
     * 执行器自己不再创建或更新部署记录，避免同一实例出现两条记录。
     */
    private final String deployLog;
    private final OffsetDateTime startedAt;
    private final OffsetDateTime endedAt;
    /**
     * 执行器能证明的结果类别。旧调用方通过 builder 构造时为空，按 success 推导；
     * 新执行器必须显式给出，超时/中断/未拿到退出状态要标成 {@link DeployExecutionOutcome#UNKNOWN}。
     */
    private final DeployExecutionOutcome outcome;

    public static ReleaseExecutionResult success(String message) {
        return ReleaseExecutionResult.builder()
                .success(true)
                .message(message)
                .outcome(DeployExecutionOutcome.SUCCEEDED)
                .build();
    }

    public static ReleaseExecutionResult success(String message, Long deployRecordId) {
        return ReleaseExecutionResult.builder()
                .success(true)
                .message(message)
                .deployRecordId(deployRecordId)
                .outcome(DeployExecutionOutcome.SUCCEEDED)
                .build();
    }

    public static ReleaseExecutionResult failure(String message) {
        OffsetDateTime now = OffsetDateTime.now();
        return ReleaseExecutionResult.builder()
                .success(false)
                .message(message)
                .outcome(DeployExecutionOutcome.FAILED)
                .startedAt(now)
                .endedAt(now)
                .build();
    }

    public static ReleaseExecutionResult failure(String message, String stdout, String stderr) {
        OffsetDateTime now = OffsetDateTime.now();
        return ReleaseExecutionResult.builder()
                .success(false)
                .message(message)
                .stdout(stdout)
                .stderr(stderr)
                .outcome(DeployExecutionOutcome.FAILED)
                .startedAt(now)
                .endedAt(now)
                .build();
    }

    /** 明确没有开始执行（校验/前置条件不满足，未调用外部命令）。 */
    public static ReleaseExecutionResult notStarted(String message) {
        OffsetDateTime now = OffsetDateTime.now();
        return ReleaseExecutionResult.builder()
                .success(false)
                .message(message)
                .outcome(DeployExecutionOutcome.NOT_STARTED)
                .startedAt(now)
                .endedAt(now)
                .build();
    }

    /** 执行已经开始但无法确认结果（超时、中断、异常、未拿到退出状态）。 */
    public static ReleaseExecutionResult unknown(String message, String stdout, String stderr,
                                                 OffsetDateTime startedAt) {
        return ReleaseExecutionResult.builder()
                .success(false)
                .message(message)
                .stdout(stdout)
                .stderr(stderr)
                .outcome(DeployExecutionOutcome.UNKNOWN)
                .startedAt(startedAt)
                .endedAt(OffsetDateTime.now())
                .build();
    }

    /**
     * 兼容旧调用方：没有显式结果类别时，失败没有足够证据，按未知处理。
     */
    public DeployExecutionOutcome resolvedOutcome() {
        if (outcome != null) {
            return outcome;
        }
        return success ? DeployExecutionOutcome.SUCCEEDED : DeployExecutionOutcome.UNKNOWN;
    }

    public String fullLog() {
        StringBuilder sb = new StringBuilder();
        if (stdout != null && !stdout.isBlank()) {
            sb.append("STDOUT:\n").append(stdout);
        }
        if (stderr != null && !stderr.isBlank()) {
            if (sb.length() > 0) sb.append("\n");
            sb.append("STDERR:\n").append(stderr);
        }
        return sb.toString();
    }
}

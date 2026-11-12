package com.infrahub.release.service;

import com.infrahub.release.support.DeployExecutionOutcome;

import java.util.List;
import java.util.Objects;

/**
 * 一次部署执行（整单或金丝雀批次）的结果。
 *
 * <p>比原来的三元组多出的是**可证明程度**：{@link #outcome()} 说明这次执行是确定成功、确定失败、
 * 部分成功、未开始，还是结果未知。{@code success} 仍然保留，语义是“整批确定成功”，只有
 * {@link DeployExecutionOutcome#SUCCEEDED} 时为 true。
 *
 * <p>{@link #message()} 是**面向用户**的摘要，只允许包含固定类别、数量和关联 ID；
 * 原始 stdout/stderr、命令与异常细节写入部署记录的 deployLog，不进入这里。
 */
public final class ReleaseDeployExecutionResult {

    /** 单个实例的结果，供调用方与审计区分“哪个实例确定失败、哪个实例结果未知”。 */
    public record InstanceOutcome(Long instanceId, DeployExecutionOutcome outcome) {
    }

    private final boolean success;
    private final List<Long> successInstanceIds;
    private final String message;
    private final DeployExecutionOutcome outcome;
    private final int attemptedCount;
    private final int succeededCount;
    private final int failedCount;
    private final int unknownCount;
    private final int notStartedCount;
    private final String failureCode;
    private final List<InstanceOutcome> instanceOutcomes;

    /**
     * 兼容旧调用方（既有测试与旧执行结果构造）：没有提供结果类别时按 success 推导。
     * 生产代码应使用完整构造器，明确给出 {@link DeployExecutionOutcome}。
     */
    public ReleaseDeployExecutionResult(boolean success, List<Long> successInstanceIds, String message) {
        this(success, successInstanceIds, message,
                success ? DeployExecutionOutcome.SUCCEEDED : DeployExecutionOutcome.UNKNOWN,
                success ? sizeOf(successInstanceIds) : 0,
                success ? sizeOf(successInstanceIds) : 0,
                0,
                success ? 0 : 1,
                0,
                success ? null : "LEGACY_UNCLASSIFIED",
                List.of());
    }

    public ReleaseDeployExecutionResult(boolean success,
                                        List<Long> successInstanceIds,
                                        String message,
                                        DeployExecutionOutcome outcome,
                                        int attemptedCount,
                                        int succeededCount,
                                        int failedCount,
                                        int unknownCount,
                                        int notStartedCount,
                                        String failureCode,
                                        List<InstanceOutcome> instanceOutcomes) {
        this.success = success;
        this.successInstanceIds = successInstanceIds == null ? List.of() : List.copyOf(successInstanceIds);
        this.message = message;
        this.outcome = Objects.requireNonNull(outcome, "outcome");
        this.attemptedCount = attemptedCount;
        this.succeededCount = succeededCount;
        this.failedCount = failedCount;
        this.unknownCount = unknownCount;
        this.notStartedCount = notStartedCount;
        this.failureCode = failureCode;
        this.instanceOutcomes = instanceOutcomes == null ? List.of() : List.copyOf(instanceOutcomes);
    }

    private static int sizeOf(List<Long> ids) {
        return ids == null ? 0 : ids.size();
    }

    public boolean success() {
        return success;
    }

    public List<Long> successInstanceIds() {
        return successInstanceIds;
    }

    public String message() {
        return message;
    }

    public DeployExecutionOutcome outcome() {
        return outcome;
    }

    public int attemptedCount() {
        return attemptedCount;
    }

    public int succeededCount() {
        return succeededCount;
    }

    public int failedCount() {
        return failedCount;
    }

    public int unknownCount() {
        return unknownCount;
    }

    public int notStartedCount() {
        return notStartedCount;
    }

    /** 固定失败类别码（例如 PRECHECK_FAILED / COMMAND_TIMEOUT / EXECUTOR_ERROR），可能为空。 */
    public String failureCode() {
        return failureCode;
    }

    public List<InstanceOutcome> instanceOutcomes() {
        return instanceOutcomes;
    }

    /** 供审计与失败原因使用的固定计数摘要，不含任何远端输出。 */
    public String countSummary() {
        return "成功 " + succeededCount + " 个、确定失败 " + failedCount + " 个、结果未知 "
                + unknownCount + " 个、未开始 " + notStartedCount + " 个（共 " + attemptedCount + " 个）";
    }
}

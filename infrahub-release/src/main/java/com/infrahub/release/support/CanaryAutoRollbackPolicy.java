package com.infrahub.release.support;

/**
 * 金丝雀自动回滚的可用性开关。
 *
 * <p>金丝雀自动回滚保持关闭。启用它必须同时满足以下条件，缺一不可：
 * <ol>
 *   <li>触发它的执行结果必须是 {@link DeployExecutionOutcome#FAILED}（全部已执行实例确定失败），
 *       未知与部分成功一律不得触发；</li>
 *   <li>目标版本确定：当前版本快照存在，且回滚目标仍是本次发布前的版本；</li>
 *   <li>目标实例仍然有效：回滚认领时重新锁实例并校验归属；</li>
 *   <li>失败到回滚之间的并发协调连续生效：发布单以 FAILED 提交，回滚认领在同一服务锁下进行，
 *       且不出现“失败终态已提交、回滚尚未认领”的空窗；</li>
 *   <li>回滚执行不再在数据库事务内调用 SSH（当前实现仍然持锁执行，见交付说明）。</li>
 * </ol>
 * 在这些条件全部可验证之前，这里保持 false：宁可让操作者人工核对，也不做一次无法证明安全的回滚。
 *
 * <p>放在 support 包而不是服务类里，是因为 DTO 需要对外如实报告“自动回滚当前不可用”，
 * 而 DTO 不应反向依赖服务层。
 */
public final class CanaryAutoRollbackPolicy {

    private static final boolean AVAILABLE = false;

    private CanaryAutoRollbackPolicy() {
    }

    /** 自动回滚当前是否真的会被执行；恒为 false 时所有自动回滚入口都会直接拒绝。 */
    public static boolean isAvailable() {
        return AVAILABLE;
    }
}

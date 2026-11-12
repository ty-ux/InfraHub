package com.infrahub.release.support;

/**
 * 准入已变更：采样完成后、实际拨号前，目标的可拨号身份被改动，本轮拨号必须取消。
 *
 * <p>本异常承载的结论是**取消**，不是失败：它既不代表进程停止、也不代表主机离线，
 * 更不代表探测执行失败。捕获方必须转成 {@link CollectionResult#SKIPPED_ADMISSION_CHANGED}
 * 与 {@link CollectionErrorCategory#ADMISSION_CHANGED}，**不得**回退成 {@code PROBE_ERROR}
 * 或 {@code FAILED} 去推进连续失败计数。
 *
 * <p>消息里只允许出现"哪一项身份对不上"这类结构性描述，**不得**携带密码、私钥、passphrase
 * 或 Secret 值 —— 构造点本身也拿不到它们的明文。
 */
public class CollectionAdmissionChangedException extends RuntimeException {

    public CollectionAdmissionChangedException(String message) {
        super(message);
    }
}

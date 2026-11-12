package com.infrahub.release.support;

/**
 * 采集结果的来源。
 *
 * <p>本平台**没有部署 Agent**，因此 {@link #AGENT_REPORT} 是一个永远不写入的枚举位：
 * 保留它是为了在契约上把"真实 Agent 上报"与"平台主动探测"分开，
 * 防止将来有人把探测结果冒充成 Agent 心跳。
 *
 * @see docs/release/v2-host-instance-status-collection-contract.md 第 4.6 节
 */
public enum CollectionSource {

    /** 平台主动探测（当前唯一会写入的来源） */
    SSH_PROBE,

    /** 真实 Agent 上报 —— 当前平台无 Agent，永不写入 */
    AGENT_REPORT,

    /** 无来源 */
    NONE
}
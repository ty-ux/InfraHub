package com.infrahub.release.dto;

import java.time.OffsetDateTime;

/**
 * 一个采集目标在**采样时刻**的身份快照。
 *
 * <p>写回前会重新读取目标并与本快照比对（契约 §5.5），因此快照必须包含"被改动就该拒收样本"的
 * 全部字段：目标仍存在、实例仍绑同一台主机、SSH 配置仍是同一条且未被修改。
 *
 * @param targetType           目标类型，HOST / SERVICE_INSTANCE
 * @param targetId             目标主键
 * @param hostId               主机目标为自身 id；实例目标为实例绑定的 host id，可为 null
 * @param instanceType         实例类型，仅实例目标有值
 * @param sshConfigId          采样时捕获的启用 SSH 配置 id；无可用配置时为 null
 * @param sshConfigUpdatedAt   采样时捕获的 SSH 配置修改时刻，用于识别"配置已被改"
 * @param hostAddress          采样时**实际拨号**的地址（ip 优先、退回 hostname）；主机改址后旧样本必须被拒收
 * @param sshPort              采样时捕获的**生效 SSH 端口**；拨号端口只能取自本字段，不得再读库
 * @param serviceId            实例目标参与诊断命令解析的服务 id，仅实例目标有值；变更即拒收样本
 * @param envId                实例目标参与诊断命令解析的环境 id，仅实例目标有值；变更即拒收样本
 * @see docs/release/v2-host-instance-status-collection-contract.md 第 5.5 节
 */
public record CollectionTargetRef(
        String targetType,
        Long targetId,
        Long hostId,
        String instanceType,
        Long sshConfigId,
        OffsetDateTime sshConfigUpdatedAt,
        String hostAddress,
        Integer sshPort,
        Long serviceId,
        Long envId
) {

    public boolean hasConfig() {
        return sshConfigId != null;
    }
}
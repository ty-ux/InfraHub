package com.infrahub.release.service;

import com.infrahub.release.support.RuntimeInstanceStartIdentity;

import java.util.Optional;

/**
 * 当前**生效**启动身份的读取口。
 *
 * <p>只返回该实例当前代次的有效身份；已作废代次（重部署前的旧进程、实例改绑前的主机身份）
 * 不得返回。实现方必须把"生效"的定义写清楚，不能靠调用方自己猜。
 *
 * <p><b>为什么是接口</b>：真实实现要读一份新表（或新列）并校验签名/来源，而本仓库目前没有这份存储，
 * 也没有部署侧写入方。唯一实现 {@link UnregisteredRuntimeInstanceStartIdentitySource} 明确返回空，
 * 让"身份不可得"成为可审查的、恒定的结果，而不是散落在停止逻辑里的假设。
 * 后续接入真实实现时，替换的是这个 Bean，不是停止判定本身。
 */
public interface RuntimeInstanceStartIdentitySource {

    /**
     * @param instanceId 运行实例登记 ID
     * @return 当前生效身份；没有登记过身份时返回空（**不是**"进程不存在"）
     */
    Optional<RuntimeInstanceStartIdentity> findActive(Long instanceId);
}

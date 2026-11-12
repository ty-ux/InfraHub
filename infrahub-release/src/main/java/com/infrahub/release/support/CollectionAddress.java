package com.infrahub.release.support;

import com.infrahub.release.entity.AssetHost;
import org.springframework.util.StringUtils;

/**
 * 主机"实际拨号地址"的唯一裁定处。
 *
 * <p>规则只有一条：有 ip 用 ip，没有则退回 hostname。之所以要单列一个类，是因为这个值同时被两处使用：
 * <ul>
 *   <li>**探测**：样本是基于哪个地址采到的（{@code SshProcessCollectionObserver}）；</li>
 *   <li>**写回**：库里的地址是否还是采样时那一个（{@code CollectionWriteBackService}）。</li>
 * </ul>
 * 两处各写一份三元表达式，迟早会在改动时漂移 —— 一处改了规则、另一处没改，
 * 结果就是"发送到新地址的样本被当成旧地址样本拒收"或者更糟的反向情况。因此只留这一份实现。
 *
 * @see docs/release/v2-host-instance-status-collection-contract.md 第 5.5 节
 */
public final class CollectionAddress {

    private CollectionAddress() {
    }

    /**
     * @return 该主机在探测时会使用的地址；ip 与 hostname 都为空时返回 null（调用方须视作不可用）
     */
    public static String of(AssetHost host) {
        if (host == null) {
            return null;
        }
        return StringUtils.hasText(host.getIp()) ? host.getIp() : host.getHostname();
    }
}
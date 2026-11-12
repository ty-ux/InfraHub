package com.infrahub.release.service;

import com.infrahub.infra.ssh.SshExecutionRequest;
import com.infrahub.release.dto.CollectionTargetRef;
import com.infrahub.release.entity.AssetHost;
import com.infrahub.release.entity.AssetHostSshConfig;
import com.infrahub.release.support.CollectionAddress;
import com.infrahub.release.support.CollectionAdmissionChangedException;
import com.infrahub.release.support.CollectionTargetType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * 把**准入快照**变成**实际拨号请求**的唯一路径。
 *
 * <p><b>要修的问题</b>：观测器原先在采样之后重新按 id 读主机与 SSH 配置，再拿"读回来的值"去拨号。
 * 采样到拨号之间目标可以被改地址、改端口、换连接配置，于是本轮采集实际拨向的端点可能与准入时批准的
 * 那一个不同 —— 准入那一票没有被真正绑定到拨号上。
 *
 * <p><b>本类的做法</b>：
 * <ol>
 *   <li>读库只用于**检测改动**，不用于取值：地址与端口一律取自快照
 *       （{@code ref.hostAddress()} / {@code ref.sshPort()}），请求里的端点因此与准入时逐字相同；</li>
 *   <li>连接配置只按**快照里捕获的 id**取（{@code getById(ref.sshConfigId())}），
 *       绝不改取"当前启用的那一条" —— 否则等于把这次拨号悄悄改签到另一个端点；</li>
 *   <li>取回后逐项校验归属、启用状态、版本时刻与生效端口，任何一项对不上就抛
 *       {@link CollectionAdmissionChangedException}，本轮**在发出请求之前取消**；</li>
 *   <li>凭据在该配置对象上就地解析成请求字段，不与地址、端口交叉混用：
 *       地址端口来自快照，认证材料来自同一条被校验过的配置。</li>
 * </ol>
 *
 * <p><b>为什么不加行锁</b>：拨号要等网络，任何形式的 {@code SELECT ... FOR UPDATE} 都会把
 * 数据库行锁跨在网络等待上。因此这里全部是普通读；一致性由"请求取自快照 + 改动即取消"保证，
 * 而不是由锁保证。检测与拨号之间仍存在一个无法关闭的窗口，见
 * {@code docs/release/v2-host-instance-status-collection-contract.md} 第 5.5.1 节。
 *
 * <p><b>版本时刻比对</b>用 {@code toInstant()}：{@code OffsetDateTime.equals} 还要求偏移一致，
 * 而 timestamptz 读回来的偏移随会话时区变化，直接 equals 会把"同一时刻、不同偏移"误判成配置被改。
 *
 * <p><b>凭据不外泄</b>：本类不产生任何承载密码/私钥/passphrase 的中间对象，也不记录日志；
 * 解析出来的明文只出现在返回的 {@link SshExecutionRequest} 字段上。
 */
@Service
@RequiredArgsConstructor
public class CollectionDialResolver {

    private final AssetHostService assetHostService;
    private final AssetHostSshConfigService assetHostSshConfigService;
    private final SecretValueResolver secretValueResolver;

    /**
     * 用快照构造拨号请求；快照与当前库状态有任何可拨号身份的差异就抛
     * {@link CollectionAdmissionChangedException}，绝不"将就"用当前值拨出去。
     *
     * @param ref           采样时刻捕获的目标快照；调用方须已确认 {@link CollectionTargetRef#hasConfig()}
     * @param command       要执行的命令
     * @param timeoutMillis 下推到执行器的命令超时
     */
    public SshExecutionRequest buildRequest(CollectionTargetRef ref, String command, Long timeoutMillis) {
        Long hostId = hostIdOf(ref);
        if (hostId == null) {
            throw new CollectionAdmissionChangedException("采样快照没有可拨号主机，本轮取消");
        }
        if (ref.sshConfigId() == null) {
            throw new CollectionAdmissionChangedException("采样快照没有可拨号的 SSH 连接配置，本轮取消");
        }

        AssetHost host = assetHostService.getById(hostId);
        if (host == null) {
            throw new CollectionAdmissionChangedException("采样后目标主机已被删除(hostId=" + hostId + ")，本轮取消");
        }
        if (!Objects.equals(CollectionAddress.of(host), ref.hostAddress())) {
            throw new CollectionAdmissionChangedException("采样后主机拨号地址已变更(hostId=" + hostId + ")，本轮取消");
        }

        AssetHostSshConfig sshConfig = assetHostSshConfigService.getById(ref.sshConfigId());
        if (sshConfig == null) {
            throw new CollectionAdmissionChangedException(
                    "采样后 SSH 连接配置已被删除(sshConfigId=" + ref.sshConfigId() + ")，本轮取消");
        }
        if (!Objects.equals(sshConfig.getHostId(), hostId)) {
            throw new CollectionAdmissionChangedException(
                    "SSH 连接配置已被改绑到别的主机(sshConfigId=" + ref.sshConfigId() + ")，本轮取消");
        }
        if (!Boolean.TRUE.equals(sshConfig.getEnabled())) {
            throw new CollectionAdmissionChangedException(
                    "采样后 SSH 连接配置已被停用(sshConfigId=" + ref.sshConfigId() + ")，本轮取消");
        }
        if (!sameInstant(sshConfig.getUpdatedAt(), ref.sshConfigUpdatedAt())) {
            throw new CollectionAdmissionChangedException(
                    "采样后 SSH 连接配置已被修改(sshConfigId=" + ref.sshConfigId() + ")，本轮取消");
        }
        if (!Objects.equals(sshConfig.getSshPort(), ref.sshPort())) {
            throw new CollectionAdmissionChangedException(
                    "采样后 SSH 生效端口已变更(sshConfigId=" + ref.sshConfigId() + ")，本轮取消");
        }

        return SshExecutionRequest.builder()
                // 端点取自快照：即使上面读到的行在检测之后又被改动，这里也不会跟着变
                .host(ref.hostAddress())
                .port(ref.sshPort())
                // 认证材料取自同一条被校验过的配置，不与上面两项交叉
                .username(sshConfig.getUsername())
                .authType(sshConfig.getAuthType())
                .password(secretValueResolver.resolve(sshConfig.getPasswordSecretRefId()))
                .privateKeyPath(secretValueResolver.resolve(sshConfig.getPrivateKeySecretRefId()))
                .passphrase(secretValueResolver.resolve(sshConfig.getPassphraseSecretRefId()))
                .command(command)
                .timeoutMillis(timeoutMillis)
                .build();
    }

    /** 快照里"这台机器是谁"：主机目标就是自身，实例目标是它所绑定的主机。 */
    public static Long hostIdOf(CollectionTargetRef ref) {
        return CollectionTargetType.HOST.equals(ref.targetType()) ? ref.targetId() : ref.hostId();
    }

    static boolean sameInstant(OffsetDateTime left, OffsetDateTime right) {
        if (left == null || right == null) {
            return left == right;
        }
        return left.toInstant().equals(right.toInstant());
    }
}

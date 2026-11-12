package com.infrahub.release.support;

import java.time.OffsetDateTime;

/**
 * 按**实例**绑定的可信启动身份：平台用来证明"主机上的某个进程就是这条登记"的全部材料。
 *
 * <p><b>为什么需要它</b>：{@code runtime_service_instance} 现在只有名字、类型、主机、版本和状态，
 * 没有进程、启动代次或制品身份。部署参数（{@code deploy.root} / {@code deploy.port} /
 * {@code deploy.pidFile}）按**应用+环境**解析，同服务同主机的多个实例共用同一份参数；
 * 部署命令是用户配置的模板，内容不可知。仅凭 PID 文件、端口监听者或命令行子串都无法证明归属，
 * 任何进程都可以在命令行里带上同样的字符串，PID 也会在核对与发信号之间被复用。
 *
 * <p><b>字段即信任边界</b>：下列字段必须由**启动该进程的一方**在启动成功时写入并登记，
 * 不能事后从主机上反查拼凑：
 * <ul>
 *   <li>{@code instanceId} / {@code hostId} —— 身份属于哪条登记、哪台主机；与库中行不一致即拒绝；</li>
 *   <li>{@code generation} —— 每次成功启动生成的新代次（不透明、唯一）。重部署后旧代次作废，
 *       旧响应、旧请求、旧事件都不得再作用于新进程；</li>
 *   <li>{@code bootId} —— 目标机本次启动的 {@code /proc/sys/kernel/random/boot_id}。重启后必然变化，
 *       使"旧 PID 在新内核里指向别的进程"这一类替换可被识别；</li>
 *   <li>{@code pid} + {@code processStartTicks} —— 进程在**本次启动内**的身份。
 *       单看 PID 不够，必须加上 {@code /proc/<pid>/stat} 第 22 字段（内核分配、同一 PID 复用后必然不同）；</li>
 *   <li>{@code artifactDigest} —— 实际落到目标机的制品摘要（推荐 {@code sha256:<64 hex>}），
 *       把"跑着的进程"与"哪一份制品"绑定；</li>
 *   <li>{@code deployRecordId} —— 产生该进程的部署记录（可选，历史关联用）；</li>
 *   <li>{@code startedAt} / {@code source} —— 登记时间与来源（部署写入或人工认领），用于审计与失效判定。</li>
 * </ul>
 *
 * <p><b>仍不能证明的事情（不得省略）</b>：以上字段证明的是"登记时观察到的那个进程"。
 * 从最后一次校验到实际发信号之间仍存在竞态窗口：进程可能在窗口内退出、被替换、或同一 PID 被复用。
 * 因此真正的执行者必须使用内核级句柄（Linux {@code pidfd}）发信号，并在目标机本地复核，
 * 而不是把本记录的字段当作"已经锁定"的证明。
 *
 * <p><b>当前状态</b>：本仓库没有存储这些字段的表或列，也没有部署侧写入方，
 * 因此 {@link com.infrahub.release.service.RuntimeInstanceStartIdentitySource} 目前恒返回空。
 * 补齐存储与写入方属于数据库迁移 + 发布链路改造，见交付说明的阻塞项。
 */
public record RuntimeInstanceStartIdentity(
        Long instanceId,
        Long hostId,
        String generation,
        String bootId,
        Long pid,
        Long processStartTicks,
        String artifactDigest,
        Long deployRecordId,
        OffsetDateTime startedAt,
        Source source) {

    /** 身份来源：由平台部署流程写入，或从既有进程人工认领（认领同样必须记录上述全部字段）。 */
    public enum Source {
        DEPLOY,
        ADOPTED
    }

    /**
     * 字段是否完整到可以作为"可校验的身份"。
     *
     * <p>缺任何一项都不得进入执行者；这里只做字段级判定，**不**代表进程归属已被证明。
     */
    public boolean isComplete() {
        return instanceId != null
                && hostId != null
                && hasText(generation)
                && hasText(bootId)
                && pid != null && pid > 0
                && processStartTicks != null && processStartTicks >= 0
                && hasText(artifactDigest)
                && source != null;
    }

    /**
     * 身份是否绑定到指定实例与主机。
     *
     * <p>实例改绑主机后，旧身份必然不匹配；调用方据此拒绝，而不是"按新主机再解析一次"。
     */
    public boolean belongsTo(Long expectedInstanceId, Long expectedHostId) {
        return expectedInstanceId != null
                && expectedHostId != null
                && expectedInstanceId.equals(instanceId)
                && expectedHostId.equals(hostId);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}

package com.infrahub.release.dto;

import com.infrahub.release.support.CollectionScopeRejection;

import java.util.List;

/**
 * 受限轮次（{@code collectOnceForScope}）的观察汇总。
 *
 * <p><b>为什么不能复用 {@link CollectionRunSummary}</b>：无参入口的汇总只有
 * {@code total/applied/recordedOnly/discarded/expired/writeFailed} 六项。受限轮次需要额外讲清三件事：
 * <ul>
 *   <li>枚举与准入是**两步**：{@code enumerated} 是这一轮看到的全部目标，
 *       {@code admitted} 才允许进入探测池。两者不等不能只报一个 total ——
 *       "总数一样"不等于"没有越界"；</li>
 *   <li>本轮**不做任何过期结算**：{@code expiredSettlementSkipped} 显式说明这一点，
 *       使 {@code expiredSettled=0} 不会被读成"不存在过期证据"（本轮根本没有去查）；</li>
 *   <li>拒绝要能逐条定位：{@code rejections} 按阶段（枚举后 / 探测前 / 写回事务边界）与
 *       分类原因列出，而不是只给一个计数。</li>
 * </ul>
 * 无参入口继续使用 {@link CollectionRunSummary}，其构造点与调用方**未受本类影响**。
 *
 * @param enumerated              本轮枚举到的目标数（受限轮次仍然全量枚举，准入发生在枚举之后）
 * @param admitted                通过允许集、获准进入探测环节的目标数
 * @param probed                  实际交给探测池的样本数（含"线程池满/停机"被立即归类为调度拒绝的样本）
 * @param applied                 目标状态已按观测结果写入的次数
 * @param recordedOnly            只写了采集状态、未改动目标状态的次数
 * @param discarded               允许集内目标因迟到样本被拒收的次数（既有语义，未放宽）
 * @param writeFailed             写回抛异常（事务已回滚）的次数
 * @param expiredSettled          过期结算数。受限轮次恒为 0，因为它不做过期结算
 * @param expiredSettlementSkipped 本轮是否跳过了全部过期结算。受限轮次恒为 true
 * @param rejections              逐条拒绝留痕（非机密目标键 + 阶段 + 分类原因）
 */
public record CollectionScopedRunSummary(
        int enumerated,
        int admitted,
        int probed,
        int applied,
        int recordedOnly,
        int discarded,
        int writeFailed,
        int expiredSettled,
        boolean expiredSettlementSkipped,
        List<CollectionScopeRejection> rejections
) {

    public CollectionScopedRunSummary {
        // 防御性拷贝：调用方拿到的与轮次内部使用的是同一份不可变快照
        rejections = List.copyOf(rejections);
    }

    /** 被拒绝的目标数（按留痕条数计）。 */
    public int rejected() {
        return rejections.size();
    }

    /** 停机等"未开展工作"的轮次：全零、且明确标注跳过了过期结算。 */
    public static CollectionScopedRunSummary empty() {
        return new CollectionScopedRunSummary(0, 0, 0, 0, 0, 0, 0, 0, true, List.of());
    }

    /** 按阶段计数的拒绝数，便于断言"限制究竟在哪一层生效"。 */
    public int rejectedAt(CollectionScopeRejection.Stage stage) {
        int count = 0;
        for (CollectionScopeRejection rejection : rejections) {
            if (rejection.stage() == stage) {
                count++;
            }
        }
        return count;
    }

    @Override
    public String toString() {
        return "enumerated=" + enumerated
                + " admitted=" + admitted
                + " probed=" + probed
                + " applied=" + applied
                + " recordedOnly=" + recordedOnly
                + " discarded=" + discarded
                + " writeFailed=" + writeFailed
                + " expiredSettled=" + expiredSettled
                + " expiredSettlementSkipped=" + expiredSettlementSkipped
                + " rejected=" + rejections.size();
    }
}

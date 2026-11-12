package com.infrahub.release.support;

import com.infrahub.release.dto.CollectionTargetRef;

import java.util.Objects;

/**
 * 受限轮次里的一次**拒绝**留痕：发生在哪一阶段、拒绝的是哪个目标、依据哪一类原因。
 *
 * <p><b>为什么拒绝要单独成形、并且必须带阶段</b>：受限轮次的汇总如果只有"总数/成功数"，
 * 就无法区分下面两种完全不同的情形 ——
 * <ul>
 *   <li>允许集内目标被正常探测并写回；</li>
 *   <li>允许集外目标被枚举到、随后在**某一道闸门**上被拒绝（枚举后 / 探测前 / 写回事务边界）。</li>
 * </ul>
 * "总数一样"**不**等于"没有越界"。因此每次拒绝都带 {@link Stage} 与
 * {@link CollectionAdmissionPolicy.Reason}，汇总里可以按阶段与维度逐条列出。
 *
 * <p><b>阶段为什么必须记</b>：同一个目标在枚举后被拒、与在写回事务边界被拒，说明的是不同的事
 * （前者是"清单里没有"，后者是"采样之后身份被改了"）。只记一个布尔值会把这两种情况混为一谈，
 * 事后无法判定"限制究竟在哪一层生效"。
 *
 * <p><b>只含非机密信息</b>：目标键是 {@code targetType/targetId}，{@code detail} 只描述差异维度
 * 与取值（地址/端口/配置 id 与版本时刻/服务/环境），**不含任何凭据**。
 */
public record CollectionScopeRejection(
        Stage stage,
        String targetType,
        Long targetId,
        CollectionAdmissionPolicy.Reason reason,
        String detail
) {

    /** 拒绝发生在受限轮次的哪一道闸门。 */
    public enum Stage {
        /** 枚举之后：清单里没有这个目标（新增目标、等量替换的 id） */
        ENUMERATION,
        /** 真正提交给探测池之前：用策略再核一次当前采样身份 */
        PRE_PROBE,
        /** 写回事务边界上：策略拒绝，或锁定的当前身份已不再见证被授权的采样身份 */
        WRITE_BOUNDARY
    }

    public CollectionScopeRejection {
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(reason, "reason");
    }

    public static CollectionScopeRejection at(Stage stage, CollectionTargetRef ref,
                                              CollectionAdmissionPolicy.Reason reason, String detail) {
        return new CollectionScopeRejection(stage, ref.targetType(), ref.targetId(), reason, detail);
    }

    /** 非机密目标键，与 {@link CollectionAdmissionPolicy#describe(CollectionTargetRef)} 同形。 */
    public String label() {
        return targetType + "/" + targetId;
    }

    @Override
    public String toString() {
        return stage + " " + label() + " :: " + reason + " (" + detail + ")";
    }
}

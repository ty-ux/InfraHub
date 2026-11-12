package com.infrahub.release.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 一条**被合并掉的**订单级未解除事实（{@code runtime_execution_unknown_merge}）。
 *
 * <p>V31 的去重索引只到 (source_type, 发布单/回滚单, 部署记录)，不含 plan_id / batch_id：
 * 同一发布单下不同灰度批次的订单级事实会命中同一行（先到的那一行），插入被
 * {@code ON CONFLICT DO NOTHING} 静默挡下。V33 把 plan/batch 加进键之后不会再发生**新的**合并，
 * 但已被合并的事实无法还原——它自己的 plan/batch 从来没有写入任何地方。
 *
 * <p>这张台账在合并发生的那一刻记下“哪一条被并进了哪一条”，因此：
 * <ul>
 *   <li>被并掉的批次对操作者仍然可见（不再只存在于审计自由文本里）；</li>
 *   <li>解除吸收方那一条事实时，可以要求操作者显式确认它同时覆盖了这些批次，
 *       而不是“解除一条 = 隐式解除其它批次的阻断”。</li>
 * </ul>
 *
 * <p>写入用 {@code ON CONFLICT DO NOTHING}，因此重复登记同一个被并批次不会产生重复台账行。
 */
@Data
@TableName("runtime_execution_unknown_merge")
public class RuntimeExecutionUnknownMerge {

    private Long id;
    /** 吸收方：仍然存在的那条未解除事实。 */
    private Long unknownId;
    private Long serviceId;
    private Long envId;
    private String sourceType;
    private Long releaseOrderId;
    private Long rollbackOrderId;
    /** 被并掉的那条事实的批次归属；它只存在于这里。 */
    private Long planId;
    private Long batchId;
    private String detectionSource;
    private String detectionReason;
    private OffsetDateTime mergedAt;
}

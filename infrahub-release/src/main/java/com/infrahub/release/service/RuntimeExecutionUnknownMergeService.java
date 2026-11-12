package com.infrahub.release.service;

import com.infrahub.common.error.ErrorCode;
import com.infrahub.common.exception.BizException;
import com.infrahub.release.entity.RuntimeExecutionUnknown;
import com.infrahub.release.entity.RuntimeExecutionUnknownMerge;
import com.infrahub.release.mapper.RuntimeExecutionUnknownMergeMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/**
 * 被合并的订单级未知事实台账（{@code runtime_execution_unknown_merge}）。
 *
 * <p><b>为什么需要台账</b>：唯一索引的粒度决定去重行为。V31 的索引不含 plan/batch，
 * 同一发布单下不同灰度批次的订单级事实会命中同一行：阻断不会消失，但“另一个批次也在未知中”
 * 这件事只存在于当时的应用日志里 —— 谁解除了那一行，谁的阻断就一起消失了，而操作人无从知道。
 * 台账把这次合并变成一条持久事实：哪条登记被并入哪个事实、来源与批次是什么、什么时候。
 *
 * <p><b>它是解除的守卫</b>：{@link RuntimeExecutionUnknownService} 在解除一条事实前会查台账，
 * 有被并入的登记时要求操作人显式确认“解除会同时放行这些登记”。合并一旦发生**无法自动回滚**：
 * 被并入的登记没有自己的行，平台不会替它重建一条（重建等于凭日志伪造事实），
 * 只能靠人工核对与显式确认。
 *
 * <p><b>失败关闭</b>：发生合并却无法写台账（V34 未应用）时拒绝继续，整个登记短事务回滚，
 * 调用方不得释放执行认领 —— 否则会留下“阻断还在、合并不可见”的状态，日后解除时静默放行别的批次。
 *
 * <p>只读路径（台账缺失时返回空）不因此失效：普通旧库的未知事实列表仍可读取，
 * 只是响应里会标明合并信息不可核实。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RuntimeExecutionUnknownMergeService {

    private static final String MESSAGE_TABLE_MISSING =
            "订单级未知事实的合并台账不可用（数据库表 runtime_execution_unknown_merge 不存在，V34 迁移未应用）；"
                    + "同一发布单下不同批次的未知事实无法可见留存，已按失败关闭拒绝继续";

    private final RuntimeExecutionUnknownMergeMapper mergeMapper;

    private volatile Boolean tableAvailable;

    /** 台账表是否可用（V34 已应用）。 */
    public boolean isTableAvailable() {
        Boolean cached = tableAvailable;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (tableAvailable == null) {
                tableAvailable = mergeMapper.probe() != null;
                if (!tableAvailable) {
                    log.warn("数据库表 runtime_execution_unknown_merge 不存在（V34 迁移未应用）；"
                            + "订单级未知事实的合并台账不可用");
                }
            }
            return tableAvailable;
        }
    }

    /**
     * 记录“本次登记被既有订单级事实吸收”。
     *
     * <p>必须在冲突登记所在的同一短事务内调用：台账与阻断事实同生共死，
     * 不会出现“事实在库里、合并没记录”的中间态。
     *
     * <p>幂等：同一 (事实, source, plan, batch) 重复登记只留一条台账，
     * 再次命中既有台账行视为已经记录（不是失败）。
     *
     * @param absorbedBy             吸收方（库里已存在的那条订单级未解除事实）
     * @param absorbedRegistration   本次未落库的登记
     * @throws BizException 台账表不可用，或台账写入未生效
     */
    public void recordMergedRegistration(RuntimeExecutionUnknown absorbedBy, RuntimeExecutionUnknown absorbedRegistration) {
        if (absorbedBy == null || absorbedBy.getId() == null || absorbedRegistration == null) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "合并登记缺少事实身份，无法留存合并台账");
        }
        if (!isTableAvailable()) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, MESSAGE_TABLE_MISSING);
        }
        RuntimeExecutionUnknownMerge row = new RuntimeExecutionUnknownMerge();
        row.setUnknownId(absorbedBy.getId());
        row.setServiceId(absorbedRegistration.getServiceId());
        row.setEnvId(absorbedRegistration.getEnvId());
        row.setSourceType(absorbedRegistration.getSourceType());
        row.setReleaseOrderId(absorbedRegistration.getReleaseOrderId());
        row.setRollbackOrderId(absorbedRegistration.getRollbackOrderId());
        row.setPlanId(absorbedRegistration.getPlanId());
        row.setBatchId(absorbedRegistration.getBatchId());
        row.setDetectionSource(absorbedRegistration.getDetectionSource());
        row.setDetectionReason(absorbedRegistration.getDetectionReason());
        if (mergeMapper.insertIfAbsent(row) != 1) {
            // 0 行是幂等重放（同一次登记重复进来），不是错误；但要能读回这条台账才算记录成功。
            RuntimeExecutionUnknownMerge existing = mergeMapper.listByUnknownId(absorbedBy.getId()).stream()
                    .filter(item -> sameRegistration(item, row))
                    .findFirst()
                    .orElse(null);
            if (existing == null) {
                throw new BizException(ErrorCode.INTERNAL_ERROR,
                        "未知事实 " + absorbedBy.getId() + " 的合并台账写入未生效，登记事实不可信，已拒绝继续");
            }
        }
    }

    /** 某条未解除事实名下被吸收的登记（按 id 升序）。台账不可用时返回空集合。 */
    public List<RuntimeExecutionUnknownMerge> listByUnknownId(Long unknownId) {
        if (!isTableAvailable() || unknownId == null) {
            return List.of();
        }
        return mergeMapper.listByUnknownId(unknownId);
    }

    /** 某条未解除事实吸收了哪些登记（按 id 升序）。台账不可用时返回空集合。 */
    public List<RuntimeExecutionUnknownMerge> listByUnknownIds(List<Long> unknownIds) {
        if (!isTableAvailable() || unknownIds == null || unknownIds.isEmpty()) {
            return List.of();
        }
        return mergeMapper.listByUnknownIds(unknownIds);
    }

    /**
     * 某条事实名下被吸收的登记数量。台账不可用时返回 0 —— 调用方必须结合
     * {@link #isTableAvailable()} 判断“确实没有合并”还是“无法核实”。
     */
    public long countByUnknownId(Long unknownId) {
        if (!isTableAvailable() || unknownId == null) {
            return 0L;
        }
        return mergeMapper.countByUnknownId(unknownId);
    }

    private static boolean sameRegistration(RuntimeExecutionUnknownMerge left, RuntimeExecutionUnknownMerge right) {
        return Objects.equals(left.getSourceType(), right.getSourceType())
                && Objects.equals(left.getReleaseOrderId(), right.getReleaseOrderId())
                && Objects.equals(left.getRollbackOrderId(), right.getRollbackOrderId())
                && Objects.equals(left.getPlanId(), right.getPlanId())
                && Objects.equals(left.getBatchId(), right.getBatchId());
    }
}

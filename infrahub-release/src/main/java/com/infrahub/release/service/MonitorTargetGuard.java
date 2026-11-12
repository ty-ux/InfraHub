package com.infrahub.release.service;

import com.infrahub.common.error.ErrorCode;
import com.infrahub.common.exception.BizException;
import com.infrahub.release.entity.RuntimeServiceInstance;
import com.infrahub.release.mapper.RuntimeServiceInstanceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Locale;

/**
 * 监控配置写入的实例目标守卫。
 *
 * <p><b>要解决的问题</b>：告警规则、健康检查可以按 {@code target_type + target_id} 指向某个运行实例，
 * 两张表都没有外键。若写入时不与"移除实例登记"共同协调，就会出现：移除事务提交后、甚至提交之前
 * 排队等待的写入，成功创建一条指向已删除实例的有效配置。
 *
 * <p><b>协调方式：与移除登记共用同一行锁</b>。写入方在插入/更新之前先对目标实例行做
 * {@code SELECT ... FOR UPDATE}：
 * <ul>
 *   <li>监控写入先拿到锁并提交 → 移除登记随后拿到实例行锁，其"有效监控配置"检查必然看到这一行并拒绝；</li>
 *   <li>移除登记先提交 → 写入方的锁定读返回空行，写入被明确拒绝，不会留下悬空引用。</li>
 * </ul>
 * 因此它替代了上一轮的监控配置表级 {@code SHARE ROW EXCLUSIVE} 锁：不再阻塞无关实例的监控写入，
 * 也能覆盖"移除提交之后再创建"这种表锁本来就挡不住的情形。
 *
 * <p><b>必须在事务内调用</b>：行锁随事务提交或回滚释放，没有事务时锁定读立即释放，起不到协调作用。
 * 缺少事务按内部错误拒绝（说明是调用方漏加事务，而不是业务条件不满足）。
 *
 * <p><b>覆盖面与限制</b>：本守卫只作用于产品内会写入实例引用的入口（告警规则与健康检查的创建、
 * 修改目标、启用）。停用、删除不需要校验（它们移除引用，不创建引用）；直接改库、离线脚本、
 * 以及将来新增而不调用本守卫的写入口不受约束，见交付说明。
 */
@Service
@RequiredArgsConstructor
public class MonitorTargetGuard {

    /** 指向运行实例的两种历史写法：配置页写 {@code INSTANCE}，采集侧写 {@code SERVICE_INSTANCE}。 */
    private static final List<String> INSTANCE_TARGET_TYPES = List.of("INSTANCE", "SERVICE_INSTANCE");

    private final RuntimeServiceInstanceMapper runtimeServiceInstanceMapper;

    /** 目标类型是否表示"某个运行实例"（两种写法都算）。空白与 null 不视为实例目标。 */
    public static boolean isInstanceTarget(String targetType) {
        if (targetType == null) {
            return false;
        }
        return INSTANCE_TARGET_TYPES.contains(targetType.trim().toUpperCase(Locale.ROOT));
    }

    /**
     * 对实例型目标加行锁并校验其存在；非实例目标不做任何事。
     *
     * <p>实例型目标缺少 {@code targetId} 时直接拒绝：否则会写入一条“类型是实例、却没有任何实例 ID”
     * 的配置，既无法被移除登记核对，也无法在启用时被守卫发现。
     *
     * @param targetType 目标类型（{@code INSTANCE} / {@code SERVICE_INSTANCE} 会触发校验）
     * @param targetId   目标实例 ID
     * @throws BizException 无活动事务、实例型目标缺少 targetId，或目标实例行不存在
     */
    public void requireInstanceTargetExists(String targetType, Long targetId) {
        if (!isInstanceTarget(targetType)) {
            return;
        }
        if (targetId == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR,
                    "实例型监控目标必须指定 targetId(targetType=" + targetType.trim() + ")，已拒绝写入");
        }
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new BizException(ErrorCode.INTERNAL_ERROR,
                    "监控配置写入缺少活动事务，实例行锁不会生效，已拒绝写入");
        }
        RuntimeServiceInstance instance = runtimeServiceInstanceMapper.selectForUpdate(targetId);
        if (instance == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR,
                    "监控配置的目标实例不存在(targetType=" + targetType.trim() + ", targetId=" + targetId
                            + ")，可能已被移除登记，已拒绝写入");
        }
    }
}

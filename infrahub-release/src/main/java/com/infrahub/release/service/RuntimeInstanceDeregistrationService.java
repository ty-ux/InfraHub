package com.infrahub.release.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.infrahub.base.mapper.BaseServiceMapper;
import com.infrahub.common.error.ErrorCode;
import com.infrahub.common.exception.BizException;
import com.infrahub.release.dto.RuntimeInstanceRemovalResponse;
import com.infrahub.release.entity.MonitorAlertRule;
import com.infrahub.release.entity.MonitorCollectionState;
import com.infrahub.release.entity.MonitorHealthCheck;
import com.infrahub.release.entity.MonitorMetricTarget;
import com.infrahub.release.entity.ReleaseOrder;
import com.infrahub.release.entity.RuntimeServiceInstance;
import com.infrahub.release.mapper.MonitorCollectionStateMapper;
import com.infrahub.release.mapper.ReleaseRollbackOrderMapper;
import com.infrahub.release.mapper.RuntimeServiceInstanceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 移除运行实例登记。
 *
 * <p><b>只移除登记，不碰进程</b>：本服务不对目标主机发起任何连接，也不发送任何信号。
 * 服务器上的进程即使仍在运行，也不会因为登记被移除而停止——响应里用
 * {@code processStopped=false} 和明确的文案把这一点说死。
 *
 * <p><b>阻碍即拒绝</b>：核对见到下列任一情况就拒绝，并说明要先处理什么；不自动取消任务，
 * 也不替用户改配置或删规则：
 * <ul>
 *   <li>该服务在该环境有执行中（{@code RUNNING}）的发布单或回滚任务；</li>
 *   <li>启用中的告警规则直接指向该实例；</li>
 *   <li>启用中的健康检查指向该实例；</li>
 *   <li>监控指标目标指向该实例。</li>
 * </ul>
 *
 * <p><b>并发协调（两把真实的数据库行锁，按固定顺序获取，不是"加了 @Transactional 就算并发安全"）</b>：
 * <ol>
 *   <li>{@code base_service} 行锁（{@code SELECT ... FOR UPDATE}，先取）：与
 *       {@link ReleaseExecutionClaimService} 认领发布、{@link RollbackTargetCoordinator#lockForExecution}
 *       认领回滚执行使用的是**同一把锁**，因此"检查执行中任务"与"认领新任务"互斥；</li>
 *   <li>{@code runtime_service_instance} 行锁（后取，并读回锁后整行）：与采集写回
 *       （{@code CollectionWriteBackService}）、监控配置写入（{@code MonitorTargetGuard}）
 *       使用同一行，使"检查有效监控配置 + 删除"与"创建/修改/启用指向该实例的配置"互斥。</li>
 * </ol>
 * 加锁顺序固定为 {@code base_service → runtime_service_instance}，与发布认领
 * （{@code base_service → release_order}）、回滚执行（{@code base_service → 回滚单 → 实例}）、
 * 采集写回（{@code 实例 → 采集状态 → SSH 配置}）一致，不形成反向取锁环。
 *
 * <p><b>为什么不再使用监控配置表的表级锁</b>：{@code SHARE ROW EXCLUSIVE} 只能阻塞本事务持锁期间的写入，
 * 移除提交之后再创建配置仍会留下悬空引用，而且会阻塞无关实例的监控写入。现在由所有产品内写入入口
 * （{@code MonitorAlertRuleService} / {@code MonitorHealthCheckService} 的创建、改目标、启用）
 * 在写入前锁定同一实例行并校验目标存在：监控写入先提交，移除的检查必然看到该引用并拒绝；
 * 移除先提交，之后的目标锁定读返回空行，写入被拒绝。
 * 不参与协调的入口（直接改库、指标目标的离线写入）见交付说明；它们不受本机制约束。
 *
 * <p><b>发布单/回滚单的"创建"本身不参与协调</b>，但那一步只产生 {@code CREATED}/{@code APPROVED}
 * 状态的非执行任务，不构成"继续作用于该实例"的活动操作；真正执行时会先经过上面的服务行锁。
 *
 * <p><b>删除范围</b>：只删 {@code runtime_service_instance} 的**一行**和它在
 * {@code monitor_collection_state} 里的采集状态行；主机、服务、环境、版本、部署记录、
 * 同主机其他实例、监控配置本身一律不动。该表没有软删除列，新增列属于数据库结构变更，
 * 不在本轮范围内（见交付说明的阻塞项）。历史记录（部署记录、监控快照、告警事件）保持原样：
 * 它们不指向该实例行，也没有外键，删除登记不会让它们悬空。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RuntimeInstanceDeregistrationService {

    /** 采集状态与监控配置里指向运行实例的两种历史写法，两处都要核对。 */
    private static final List<String> INSTANCE_TARGET_TYPES = List.of("SERVICE_INSTANCE", "INSTANCE");
    private static final String RELEASE_STATUS_RUNNING = "RUNNING";

    private final RuntimeServiceInstanceService runtimeServiceInstanceService;
    private final RuntimeServiceInstanceMapper runtimeServiceInstanceMapper;
    private final BaseServiceMapper baseServiceMapper;
    private final ReleaseOrderService releaseOrderService;
    private final ReleaseRollbackOrderMapper releaseRollbackOrderMapper;
    private final MonitorAlertRuleService monitorAlertRuleService;
    private final MonitorHealthCheckService monitorHealthCheckService;
    private final MonitorMetricTargetService monitorMetricTargetService;
    private final MonitorCollectionStateMapper monitorCollectionStateMapper;
    private final RuntimeInstanceAuditLogService auditLogService;
    private final RuntimeExecutionUnknownService runtimeExecutionUnknownService;

    @Transactional
    public RuntimeInstanceRemovalResponse deregister(Long instanceId) {
        requireTransaction("移除实例登记");

        // 1) 第一次读取只用于确定锁对象：serviceId 是实例的归属字段，必须先锁服务行。
        RuntimeServiceInstance snapshot = runtimeServiceInstanceMapper.selectById(instanceId);
        if (snapshot == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR,
                    "运行实例不存在(id=" + instanceId + ")，可能已被移除登记");
        }
        if (snapshot.getServiceId() == null || snapshot.getEnvId() == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR,
                    "运行实例(id=" + instanceId + ")缺少服务或环境关联，无法核对阻碍项，已拒绝移除");
        }

        // 2) 服务行锁先于实例行锁：与发布执行认领、回滚执行认领共用同一把锁。
        if (baseServiceMapper.lockById(snapshot.getServiceId()) == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR,
                    "运行实例(id=" + instanceId + ")关联的服务不存在，已拒绝移除");
        }

        // 2.1) 结果未知的发布/回滚可能仍在作用于同一服务与环境：登记行与历史 UNKNOWN
        // 部署记录都是“未解除的未知事实”，存在即拒绝移除。移除登记不等于停止进程，
        // 此时删掉实例只会让后续核对失去线索。
        runtimeExecutionUnknownService.assertNoUnresolvedUnknown(
                snapshot.getServiceId(), snapshot.getEnvId());

        // 3) 锁实例行并读回**锁后**整行：判定与删除都用锁保护下的当前值，不用加锁前的快照。
        RuntimeServiceInstance instance = runtimeServiceInstanceMapper.selectFullForUpdate(instanceId);
        if (instance == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR,
                    "运行实例不存在(id=" + instanceId + ")，可能已被移除登记");
        }
        // 归属在核对期间被改动说明存在本机制未覆盖的写入：失败关闭，而不是用错锁继续
        if (!Objects.equals(instance.getServiceId(), snapshot.getServiceId())
                || !Objects.equals(instance.getEnvId(), snapshot.getEnvId())) {
            throw new BizException(ErrorCode.VALIDATION_ERROR,
                    "运行实例(id=" + instanceId + ")归属在核对期间发生变化，已拒绝移除");
        }

        requireNoRunningTask(instance);
        requireNoActiveMonitorConfig(instance);

        Map<String, Object> before = new LinkedHashMap<>();
        before.put("instanceName", instance.getInstanceName());
        before.put("serviceId", instance.getServiceId());
        before.put("envId", instance.getEnvId());
        before.put("hostId", instance.getHostId());
        before.put("instanceType", instance.getInstanceType());
        before.put("versionId", instance.getVersionId());
        before.put("instanceStatus", instance.getInstanceStatus());
        before.put("healthStatus", instance.getHealthStatus());

        int collectionStatesRemoved = monitorCollectionStateMapper.delete(
                new LambdaQueryWrapper<MonitorCollectionState>()
                        .in(MonitorCollectionState::getTargetType, INSTANCE_TARGET_TYPES)
                        .eq(MonitorCollectionState::getTargetId, instanceId));

        boolean removed = runtimeServiceInstanceService.removeById(instanceId);
        if (!removed) {
            // 行锁已持有仍删不掉：只能是语句层面的异常，不能谎报成功
            throw new BizException(ErrorCode.VALIDATION_ERROR,
                    "运行实例(id=" + instanceId + ")移除失败，未改动任何数据");
        }

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("removed", true);
        after.put("processStopped", false);
        after.put("collectionStatesRemoved", collectionStatesRemoved);
        after.put("lastRecordedInstanceStatus", instance.getInstanceStatus());

        auditLogService.record(instanceId,
                RuntimeInstanceAuditLogService.ACTION_DEREGISTER_INSTANCE,
                "SUCCESS", before, after);

        return buildResponse(instance, collectionStatesRemoved > 0);
    }

    private void requireNoRunningTask(RuntimeServiceInstance instance) {
        long runningReleases = releaseOrderService.lambdaQuery()
                .eq(ReleaseOrder::getServiceId, instance.getServiceId())
                .eq(ReleaseOrder::getEnvId, instance.getEnvId())
                .eq(ReleaseOrder::getStatus, RELEASE_STATUS_RUNNING)
                .count();
        long runningRollbacks = releaseRollbackOrderMapper.countRunningByServiceAndEnv(
                instance.getServiceId(), instance.getEnvId());
        if (runningReleases > 0 || runningRollbacks > 0) {
            throw new BizException(ErrorCode.VALIDATION_ERROR,
                    "该实例所属服务在当前环境存在执行中的任务（发布 " + runningReleases
                            + " 个、回滚 " + runningRollbacks
                            + " 个），暂不能移除登记；请等待任务结束后重试，本操作不会取消这些任务");
        }
    }

    private void requireNoActiveMonitorConfig(RuntimeServiceInstance instance) {
        long enabledAlertRules = monitorAlertRuleService.lambdaQuery()
                .apply("UPPER(TRIM(target_type)) IN ('INSTANCE', 'SERVICE_INSTANCE')")
                .eq(MonitorAlertRule::getTargetId, instance.getId())
                .eq(MonitorAlertRule::getEnabled, Boolean.TRUE)
                .count();
        long enabledHealthChecks = monitorHealthCheckService.lambdaQuery()
                .apply("UPPER(TRIM(target_type)) IN ('INSTANCE', 'SERVICE_INSTANCE')")
                .eq(MonitorHealthCheck::getTargetId, instance.getId())
                .eq(MonitorHealthCheck::getEnabled, Boolean.TRUE)
                .count();
        long metricTargets = monitorMetricTargetService.lambdaQuery()
                .apply("UPPER(TRIM(target_type)) IN ('INSTANCE', 'SERVICE_INSTANCE')")
                .eq(MonitorMetricTarget::getTargetId, instance.getId())
                .count();
        if (enabledAlertRules > 0 || enabledHealthChecks > 0 || metricTargets > 0) {
            throw new BizException(ErrorCode.VALIDATION_ERROR,
                    "该实例仍被监控配置指向（启用中的告警规则 " + enabledAlertRules
                            + " 条、启用中的健康检查 " + enabledHealthChecks
                            + " 条、监控指标目标 " + metricTargets
                            + " 条），移除后会留下继续指向已移除实例的有效配置；请先在监控页面停用或解绑，再移除登记");
        }
    }

    private RuntimeInstanceRemovalResponse buildResponse(RuntimeServiceInstance instance, boolean collectionStateRemoved) {
        RuntimeInstanceRemovalResponse response = new RuntimeInstanceRemovalResponse();
        response.setInstanceId(instance.getId());
        response.setInstanceName(instance.getInstanceName());
        response.setServiceId(instance.getServiceId());
        response.setEnvId(instance.getEnvId());
        response.setHostId(instance.getHostId());
        response.setRemoved(true);
        response.setProcessStopped(false);
        response.setLastRecordedInstanceStatus(instance.getInstanceStatus());
        response.setLastRecordedHealthStatus(instance.getHealthStatus());
        response.setCollectionStateRemoved(collectionStateRemoved);
        response.setRemovedAt(OffsetDateTime.now());

        String message = "已移除运行实例登记；本次操作未连接目标主机，也未停止服务器上的进程。";
        if (RELEASE_STATUS_RUNNING.equalsIgnoreCase(instance.getInstanceStatus())) {
            message += "移除前登记的实例状态为 RUNNING，服务器上的进程可能仍在运行，请另行核实。";
        }
        response.setMessage(message);
        return response;
    }

    /** 无活动事务时拒绝：行锁与表锁都会在自动提交的语句结束时立即释放，起不到协调作用。 */
    private static void requireTransaction(String scene) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, scene + "缺少活动事务，已拒绝执行");
        }
    }
}

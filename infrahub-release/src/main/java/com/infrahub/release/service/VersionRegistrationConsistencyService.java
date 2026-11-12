package com.infrahub.release.service;

import com.infrahub.release.dto.VersionRegistrationConsistency;
import com.infrahub.release.entity.AssetHost;
import com.infrahub.release.entity.ReleaseCurrentVersion;
import com.infrahub.release.entity.ReleaseOrder;
import com.infrahub.release.entity.ReleaseRollbackOrder;
import com.infrahub.release.entity.ReleaseVersion;
import com.infrahub.release.entity.RuntimeServiceInstance;
import com.infrahub.release.mapper.ReleaseOrderMapper;
import com.infrahub.release.mapper.ReleaseRollbackOrderMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 当前**登记版本一致性**：把某个目标版本与当前实例集合逐个实例的登记版本作比较。
 *
 * <p>这是与 {@link ReleaseDeployCoverageService} 完全不同的事实：覆盖范围回答“当时这次执行
 * 覆盖了哪些实例”，本类回答“现在这些实例在平台登记上分别是什么版本，是否都等于目标版本”。
 * 历史发布覆盖全部实例，不代表这些实例现在仍登记在该版本；此处必须逐实例比较
 * {@code runtime_service_instance.version_id}。
 *
 * <p><b>证据边界</b>：登记版本是平台持久化状态，不是远端实际运行版本。本类只读数据库，
 * 不发起 SSH/HTTP，不做实时探测。实例被移除后不再属于当前集合；被改绑服务/环境后也不再
 * 属于当前集合；主机改绑通过实例行的主机字段反映。
 *
 * <p><b>来源校验</b>：目标版本必须能追溯到发布单或回滚单，且来源的服务/环境/版本与目标一致。
 * 来源缺失或不一致时，即使所有实例恰好匹配，也不给出“全部一致”的结论，只保留逐实例登记事实。
 */
@Service
@RequiredArgsConstructor
public class VersionRegistrationConsistencyService {

    private static final String ROLLBACK_STATUS_SUCCESS = "SUCCESS";

    private final ReleaseVersionService releaseVersionService;
    private final RuntimeServiceInstanceService runtimeServiceInstanceService;
    private final AssetHostService assetHostService;
    private final ReleaseOrderMapper releaseOrderMapper;
    private final ReleaseRollbackOrderMapper releaseRollbackOrderMapper;

    /** 发布单详情：目标版本就是该单自己的版本。 */
    public VersionRegistrationConsistency forReleaseOrder(ReleaseOrder order) {
        if (order == null) {
            TargetInfo target = new TargetInfo();
            target.reason = "发布单不存在，无法确认目标版本";
            return build(target, VersionRegistrationConsistency.SOURCE_NONE, null, null, null, null);
        }
        TargetInfo target = new TargetInfo();
        target.targetVersionId = order.getVersionId();
        if (order.getVersionId() == null) {
            target.sourceConsistent = false;
            target.reason = "发布单没有目标版本，无法比较当前实例的登记版本";
        } else {
            target.targetVersion = releaseVersionService.getById(order.getVersionId());
            target.sourceConsistent = target.targetVersion != null;
            target.reason = target.targetVersion == null
                    ? "发布单的目标版本记录不存在，无法比较当前实例的登记版本" : null;
        }
        return build(target, VersionRegistrationConsistency.SOURCE_RELEASE_ORDER,
                order.getId(), order.getOrderNo(), order.getServiceId(), order.getEnvId());
    }

    /** 版本指针：来源按回滚单优先、发布单其次；必须与指针的服务/环境/版本一致。 */
    public VersionRegistrationConsistency forVersionPointer(ReleaseCurrentVersion pointer) {
        if (pointer == null) {
            TargetInfo target = new TargetInfo();
            target.reason = "没有版本指针行，无法确认目标版本";
            return build(target, VersionRegistrationConsistency.SOURCE_NONE,
                    null, null, null, null);
        }

        TargetInfo target = new TargetInfo();
        target.targetVersionId = pointer.getVersionId();
        target.targetVersion = pointer.getVersionId() == null
                ? null : releaseVersionService.getById(pointer.getVersionId());
        if (pointer.getVersionId() == null) {
            target.reason = "版本指针没有目标版本，无法比较当前实例的登记版本";
        } else if (target.targetVersion == null) {
            target.reason = "版本指针的目标版本记录不存在，无法比较当前实例的登记版本";
        }

        if (pointer.getSourceRollbackOrderId() != null) {
            return forRollbackPointer(pointer, target);
        }
        if (pointer.getSourceReleaseOrderId() == null) {
            target.sourceConsistent = false;
            target.reason = "版本指针没有记录来源订单，无法核对目标版本的来源";
            return build(target, VersionRegistrationConsistency.SOURCE_NONE,
                    null, null, pointer.getServiceId(), pointer.getEnvId());
        }
        ReleaseOrder sourceOrder = releaseOrderMapper.selectById(pointer.getSourceReleaseOrderId());
        if (sourceOrder == null) {
            target.reason = "版本指针记录的来源发布单不存在，无法核对目标版本的来源";
            return build(target, VersionRegistrationConsistency.SOURCE_CURRENT_VERSION_RELEASE,
                    pointer.getSourceReleaseOrderId(), null,
                    pointer.getServiceId(), pointer.getEnvId());
        }
        target.sourceConsistent = Objects.equals(sourceOrder.getServiceId(), pointer.getServiceId())
                && Objects.equals(sourceOrder.getEnvId(), pointer.getEnvId())
                && Objects.equals(sourceOrder.getVersionId(), pointer.getVersionId());
        if (!target.sourceConsistent) {
            target.reason = "版本指针的服务、环境或目标版本与来源发布单不一致，"
                    + "不能据此判断当前实例的登记版本";
        }
        return build(target, VersionRegistrationConsistency.SOURCE_CURRENT_VERSION_RELEASE,
                sourceOrder.getId(), sourceOrder.getOrderNo(),
                pointer.getServiceId(), pointer.getEnvId());
    }

    private VersionRegistrationConsistency forRollbackPointer(ReleaseCurrentVersion pointer,
                                                              TargetInfo target) {
        Long rollbackOrderId = pointer.getSourceRollbackOrderId();
        ReleaseRollbackOrder rollback = releaseRollbackOrderMapper.selectById(rollbackOrderId);
        if (rollback == null) {
            target.reason = "版本指针记录的回滚单不存在，无法核对目标版本的来源";
            return build(target, VersionRegistrationConsistency.SOURCE_CURRENT_VERSION_ROLLBACK,
                    rollbackOrderId, null, pointer.getServiceId(), pointer.getEnvId());
        }
        ReleaseOrder sourceOrder = rollback.getReleaseOrderId() == null ? null : releaseOrderMapper.selectById(rollback.getReleaseOrderId());
        if (sourceOrder == null && rollback.getServiceId() == null) {
            target.reason = "回滚单关联的发布单不存在，无法核对目标版本的来源";
            return build(target, VersionRegistrationConsistency.SOURCE_CURRENT_VERSION_ROLLBACK,
                    rollback.getId(), rollback.getRollbackNo(),
                    pointer.getServiceId(), pointer.getEnvId());
        }
        target.sourceConsistent = Objects.equals(sourceOrder == null ? rollback.getServiceId() : sourceOrder.getServiceId(), pointer.getServiceId())
                && Objects.equals(sourceOrder == null ? rollback.getEnvId() : sourceOrder.getEnvId(), pointer.getEnvId())
                && Objects.equals(rollback.getToVersionId(), pointer.getVersionId())
                && ROLLBACK_STATUS_SUCCESS.equalsIgnoreCase(rollback.getStatus());
        if (!target.sourceConsistent) {
            target.reason = "版本指针的服务、环境或目标版本与回滚单不一致，"
                    + "或回滚单当前不是成功状态；不能据此判断当前实例的登记版本";
        }
        return build(target, VersionRegistrationConsistency.SOURCE_CURRENT_VERSION_ROLLBACK,
                rollback.getId(), rollback.getRollbackNo(),
                pointer.getServiceId(), pointer.getEnvId());
    }

    private VersionRegistrationConsistency build(TargetInfo target, String sourceType,
                                                 Long sourceOrderId, String sourceOrderNo,
                                                 Long serviceId, Long envId) {
        VersionRegistrationConsistency view = new VersionRegistrationConsistency();
        view.setTargetSourceType(sourceType);
        view.setSourceOrderId(sourceOrderId);
        view.setSourceOrderNo(sourceOrderNo);
        view.setSourceConsistent(target.sourceConsistent);
        view.setSourceRejectionReason(target.sourceConsistent ? null : target.reason);
        view.setDisclaimer(VersionRegistrationConsistency.DISCLAIMER);
        view.setTargetVersionId(target.targetVersionId);
        if (target.targetVersion != null) {
            view.setTargetVersionNo(target.targetVersion.getVersionNo());
        }

        if (serviceId == null || envId == null) {
            view.setStatus(VersionRegistrationConsistency.STATUS_UNKNOWN);
            view.setStatement("缺少服务或环境身份，无法比较当前实例的登记版本。");
            return view;
        }

        List<RuntimeServiceInstance> currentInstances =
                runtimeServiceInstanceService.listByServiceAndEnv(serviceId, envId);
        currentInstances.sort(Comparator.comparing(RuntimeServiceInstance::getId,
                Comparator.nullsLast(Comparator.naturalOrder())));
        Set<Long> versionIds = new HashSet<>();
        Set<Long> hostIds = new HashSet<>();
        for (RuntimeServiceInstance instance : currentInstances) {
            if (instance.getVersionId() != null) {
                versionIds.add(instance.getVersionId());
            }
            if (instance.getHostId() != null) {
                hostIds.add(instance.getHostId());
            }
        }
        if (target.targetVersionId != null) {
            versionIds.add(target.targetVersionId);
        }
        Map<Long, ReleaseVersion> versions = new HashMap<>();
        if (!versionIds.isEmpty()) {
            for (ReleaseVersion version : releaseVersionService.listByIds(versionIds)) {
                versions.put(version.getId(), version);
            }
        }
        Map<Long, AssetHost> hosts = new HashMap<>();
        if (!hostIds.isEmpty()) {
            for (AssetHost host : assetHostService.listByIds(hostIds)) {
                hosts.put(host.getId(), host);
            }
        }

        List<VersionRegistrationConsistency.InstanceRegistration> rows = new ArrayList<>();
        int matching = 0;
        int differing = 0;
        int unregistered = 0;
        for (RuntimeServiceInstance instance : currentInstances) {
            VersionRegistrationConsistency.InstanceRegistration row =
                    new VersionRegistrationConsistency.InstanceRegistration();
            row.setInstanceId(instance.getId());
            row.setInstanceName(instance.getInstanceName());
            row.setHostId(instance.getHostId());
            AssetHost host = instance.getHostId() == null ? null : hosts.get(instance.getHostId());
            row.setHostName(host == null ? null : host.getHostname());
            row.setRegisteredVersionId(instance.getVersionId());
            ReleaseVersion registeredVersion = instance.getVersionId() == null
                    ? null : versions.get(instance.getVersionId());
            row.setRegisteredVersionNo(registeredVersion == null
                    ? null : registeredVersion.getVersionNo());
            if (instance.getVersionId() == null) {
                row.setState(VersionRegistrationConsistency.STATE_UNREGISTERED);
                row.setMatchesTarget(false);
                unregistered++;
            } else if (target.targetVersionId != null
                    && Objects.equals(instance.getVersionId(), target.targetVersionId)) {
                row.setState(VersionRegistrationConsistency.STATE_MATCH);
                row.setMatchesTarget(true);
                matching++;
            } else {
                row.setState(VersionRegistrationConsistency.STATE_DIFFERENT);
                row.setMatchesTarget(false);
                differing++;
            }
            rows.add(row);
        }
        view.setInstances(rows);
        view.setCurrentInstanceCount(rows.size());
        view.setMatchingCount(matching);
        view.setDifferingCount(differing);
        view.setUnregisteredCount(unregistered);

        boolean targetKnown = target.targetVersionId != null && target.targetVersion != null;
        if (!target.sourceConsistent) {
            view.setStatus(VersionRegistrationConsistency.STATUS_UNKNOWN);
            view.setStatement((target.reason == null ? "目标版本来源不一致" : target.reason)
                    + "。逐实例登记情况仅供人工核对，平台不给出“全部一致”的结论。");
            return view;
        }
        if (!targetKnown) {
            view.setStatus(VersionRegistrationConsistency.STATUS_UNKNOWN);
            view.setStatement((target.reason == null ? "无法确认目标版本" : target.reason)
                    + "。逐实例登记情况仅供人工核对，平台不给出“全部一致”的结论。");
            return view;
        }
        if (rows.isEmpty()) {
            view.setStatus(VersionRegistrationConsistency.STATUS_NO_INSTANCES);
            view.setStatement("该服务/环境当前没有登记实例，无法比较版本一致性。");
            return view;
        }
        if (matching == rows.size()) {
            view.setStatus(VersionRegistrationConsistency.STATUS_ALL_MATCH);
            view.setStatement("当前 " + rows.size() + " 个实例在平台登记上的版本都是 "
                    + displayVersion(view) + "。这是平台登记状态，不是远端实际运行版本的探测结果。");
            return view;
        }
        view.setStatus(VersionRegistrationConsistency.STATUS_SOME_DIFFER);
        view.setStatement("当前 " + rows.size() + " 个实例中，" + matching + " 个在平台登记上为 "
                + displayVersion(view) + "，" + differing + " 个登记为其他版本，"
                + unregistered + " 个未登记版本。这是平台登记状态，不是远端实际运行版本的探测结果。");
        return view;
    }

    private static String displayVersion(VersionRegistrationConsistency view) {
        return view.getTargetVersionNo() != null
                ? view.getTargetVersionNo()
                : "目标版本（ID " + view.getTargetVersionId() + "）";
    }

    /** 目标版本与来源核对中间结果。 */
    private static final class TargetInfo {
        private Long targetVersionId;
        private ReleaseVersion targetVersion;
        private boolean sourceConsistent;
        private String reason;
    }
}

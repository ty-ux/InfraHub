package com.infrahub.release.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.infrahub.base.entity.BaseEnv;
import com.infrahub.base.service.BaseEnvService;
import com.infrahub.release.dto.HostProfileResponse;
import com.infrahub.release.entity.AssetHost;
import com.infrahub.release.entity.MonitorAlertEvent;
import com.infrahub.release.entity.MonitorSnapshot;
import com.infrahub.release.entity.ResourcePool;
import com.infrahub.release.entity.RuntimeDeployRecord;
import com.infrahub.release.entity.RuntimeServiceInstance;
import com.infrahub.release.mapper.MonitorAlertEventMapper;
import com.infrahub.release.mapper.MonitorSnapshotMapper;
import com.infrahub.release.mapper.RuntimeDeployRecordMapper;
import com.infrahub.release.mapper.RuntimeServiceInstanceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AssetHostProfileService {

    private static final String HOST_TARGET_TYPE = "HOST";
    private static final String HEALTH_STATUS_ONLINE = "ONLINE";
    private static final String HEALTH_STATUS_OFFLINE = "OFFLINE";
    private static final String HEALTH_STATUS_DEGRADED = "DEGRADED";
    private static final String HEALTH_STATUS_UNKNOWN = "UNKNOWN";

    private final AssetHostService assetHostService;
    private final BaseEnvService baseEnvService;
    private final ResourcePoolService resourcePoolService;
    private final RuntimeServiceInstanceMapper runtimeServiceInstanceMapper;
    private final RuntimeDeployRecordMapper runtimeDeployRecordMapper;
    private final MonitorSnapshotMapper monitorSnapshotMapper;
    private final MonitorAlertEventMapper monitorAlertEventMapper;

    /**
     * 获取主机画像
     *
     * @param hostId 主机ID
     * @return 主机画像响应，如果主机不存在返回null
     */
    public HostProfileResponse getHostProfile(Long hostId) {
        AssetHost host = assetHostService.getById(hostId);
        if (host == null) {
            return null;
        }

        HostProfileResponse response = new HostProfileResponse();
        response.setHostId(host.getId());
        response.setHostname(host.getHostname());
        response.setIp(host.getIp());
        response.setEnvId(host.getEnvId());
        response.setResourcePoolId(host.getResourcePoolId());

        // 填充环境名称
        if (host.getEnvId() != null) {
            BaseEnv env = baseEnvService.getById(host.getEnvId());
            if (env != null) {
                response.setEnvName(env.getEnvName());
            }
        }

        // 填充资源池名称
        if (host.getResourcePoolId() != null) {
            ResourcePool pool = resourcePoolService.getById(host.getResourcePoolId());
            if (pool != null) {
                response.setResourcePoolName(pool.getPoolName());
            }
        }

        // 统计运行实例数
        LambdaQueryWrapper<RuntimeServiceInstance> instanceCountWrapper = new LambdaQueryWrapper<>();
        instanceCountWrapper.eq(RuntimeServiceInstance::getHostId, hostId);
        long instanceCount = runtimeServiceInstanceMapper.selectCount(instanceCountWrapper);
        response.setRuntimeInstanceCount((int) instanceCount);

        // 查找最近部署记录
        LambdaQueryWrapper<RuntimeDeployRecord> deployWrapper = new LambdaQueryWrapper<>();
        deployWrapper.eq(RuntimeDeployRecord::getHostId, hostId)
                .orderByDesc(RuntimeDeployRecord::getStartedAt)
                .last("LIMIT 1");
        RuntimeDeployRecord latestDeploy = runtimeDeployRecordMapper.selectList(deployWrapper)
                .stream().findFirst().orElse(null);
        if (latestDeploy != null) {
            response.setLatestDeployAt(latestDeploy.getStartedAt());
            response.setLatestDeployStatus(latestDeploy.getDeployStatus());
        }

        // 查找最近监控快照
        LambdaQueryWrapper<MonitorSnapshot> snapshotWrapper = new LambdaQueryWrapper<>();
        snapshotWrapper.eq(MonitorSnapshot::getTargetType, HOST_TARGET_TYPE)
                .eq(MonitorSnapshot::getTargetId, hostId)
                .orderByDesc(MonitorSnapshot::getSnapshotTime)
                .last("LIMIT 1");
        MonitorSnapshot latestSnapshot = monitorSnapshotMapper.selectList(snapshotWrapper)
                .stream().findFirst().orElse(null);
        if (latestSnapshot != null) {
            response.setLatestMonitorSnapshotAt(latestSnapshot.getSnapshotTime());
        }

        // 推导健康状态
        deriveHealthStatus(response, host, instanceCount);

        return response;
    }

    /**
     * 推导主机健康状态
     * 采用保守策略：数据不足时返回UNKNOWN，不假装ONLINE
     */
    private void deriveHealthStatus(HostProfileResponse response, AssetHost host, long instanceCount) {
        // 规则1：缺少主机状态数据
        if (host.getHostStatus() == null || host.getHostStatus().isBlank()) {
            response.setHealthStatus(HEALTH_STATUS_UNKNOWN);
            response.setHealthReason("缺少主机状态数据");
            return;
        }

        // 规则2：主机状态为离线
        if (HEALTH_STATUS_OFFLINE.equalsIgnoreCase(host.getHostStatus())) {
            response.setHealthStatus(HEALTH_STATUS_OFFLINE);
            response.setHealthReason("主机状态为离线");
            return;
        }

        // 规则3：Agent离线但存在运行实例
        if ("OFFLINE".equalsIgnoreCase(host.getAgentStatus()) && instanceCount > 0) {
            response.setHealthStatus(HEALTH_STATUS_DEGRADED);
            response.setHealthReason("Agent离线但存在运行实例");
            return;
        }

        // 规则4：检查是否存在活跃告警
        LambdaQueryWrapper<MonitorAlertEvent> alertWrapper = new LambdaQueryWrapper<>();
        alertWrapper.eq(MonitorAlertEvent::getTargetType, HOST_TARGET_TYPE)
                .eq(MonitorAlertEvent::getTargetId, host.getId())
                .eq(MonitorAlertEvent::getAlertStatus, "FIRING");
        boolean hasFiringAlert = monitorAlertEventMapper.selectCount(alertWrapper) > 0;
        if (hasFiringAlert) {
            response.setHealthStatus(HEALTH_STATUS_DEGRADED);
            response.setHealthReason("存在活跃告警");
            return;
        }

        // 规则5：检查运行实例是否健康
        // 兼容 HEALTHY 和 UP 作为健康状态，DOWN 及其他值为不健康
        if (instanceCount > 0) {
            LambdaQueryWrapper<RuntimeServiceInstance> instancesWrapper = new LambdaQueryWrapper<>();
            instancesWrapper.eq(RuntimeServiceInstance::getHostId, host.getId());
            List<RuntimeServiceInstance> instances = runtimeServiceInstanceMapper.selectList(instancesWrapper);
            boolean hasUnhealthyInstance = instances.stream()
                    .anyMatch(inst -> inst.getHealthStatus() != null
                            && !isHealthyStatus(inst.getHealthStatus()));
            if (hasUnhealthyInstance) {
                response.setHealthStatus(HEALTH_STATUS_DEGRADED);
                response.setHealthReason("存在异常运行实例");
                return;
            }
        }

        // 规则6：主机状态本身未知（例如刚手动登记、尚未探测过的主机）：健康状态不臆断
        if (HEALTH_STATUS_UNKNOWN.equalsIgnoreCase(host.getHostStatus())) {
            response.setHealthStatus(HEALTH_STATUS_UNKNOWN);
            response.setHealthReason("主机状态未知，尚未确认");
            return;
        }

        // 规则7：所有健康条件满足
        if (HEALTH_STATUS_ONLINE.equalsIgnoreCase(host.getHostStatus())
                && "ONLINE".equalsIgnoreCase(host.getAgentStatus())) {
            response.setHealthStatus(HEALTH_STATUS_ONLINE);
            response.setHealthReason("主机健康");
            return;
        }

        // 默认：使用主机状态
        response.setHealthStatus(host.getHostStatus().toUpperCase());
        response.setHealthReason("基于主机状态字段");
    }

    /**
     * 判断实例健康状态是否为健康
     * 兼容 HEALTHY 和 UP 作为健康状态
     */
    private boolean isHealthyStatus(String healthStatus) {
        if (healthStatus == null) {
            return true; // 无状态视为健康（保守处理）
        }
        String upper = healthStatus.toUpperCase();
        return "HEALTHY".equals(upper) || "UP".equals(upper);
    }
}

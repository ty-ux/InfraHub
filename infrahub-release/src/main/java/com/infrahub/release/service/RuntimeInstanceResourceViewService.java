package com.infrahub.release.service;

import com.infrahub.base.entity.BaseEnv;
import com.infrahub.base.entity.BaseServiceEntity;
import com.infrahub.base.mapper.BaseEnvMapper;
import com.infrahub.base.mapper.BaseServiceMapper;
import com.infrahub.release.dto.RuntimeInstanceResourceViewResponse;
import com.infrahub.release.entity.AssetHost;
import com.infrahub.release.entity.MonitorSnapshot;
import com.infrahub.release.entity.ReleaseCurrentVersion;
import com.infrahub.release.entity.ReleaseVersion;
import com.infrahub.release.entity.ResourcePool;
import com.infrahub.release.entity.RuntimeDeployRecord;
import com.infrahub.release.entity.RuntimeServiceInstance;
import com.infrahub.release.mapper.AssetHostMapper;
import com.infrahub.release.mapper.MonitorSnapshotMapper;
import com.infrahub.release.mapper.ReleaseCurrentVersionMapper;
import com.infrahub.release.mapper.ReleaseVersionMapper;
import com.infrahub.release.mapper.ResourcePoolMapper;
import com.infrahub.release.mapper.RuntimeDeployRecordMapper;
import com.infrahub.release.mapper.RuntimeServiceInstanceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 运行实例资源视图服务
 * 聚合服务实例、环境、主机、资源池、当前版本、部署记录、监控快照等信息
 */
@Service
@RequiredArgsConstructor
public class RuntimeInstanceResourceViewService {

    private final RuntimeServiceInstanceMapper runtimeServiceInstanceMapper;
    private final AssetHostMapper assetHostMapper;
    private final BaseServiceMapper baseServiceMapper;
    private final BaseEnvMapper baseEnvMapper;
    private final ReleaseCurrentVersionMapper releaseCurrentVersionMapper;
    private final ReleaseVersionMapper releaseVersionMapper;
    private final RuntimeDeployRecordMapper runtimeDeployRecordMapper;
    private final MonitorSnapshotMapper monitorSnapshotMapper;
    private final ResourcePoolMapper resourcePoolMapper;

    /**
     * 获取运行实例的资源视图
     *
     * @param instanceId 实例ID
     * @return 资源视图响应，实例不存在时返回 null
     */
    public RuntimeInstanceResourceViewResponse getResourceView(Long instanceId) {
        RuntimeServiceInstance instance = runtimeServiceInstanceMapper.selectById(instanceId);
        if (instance == null) {
            return null;
        }

        RuntimeInstanceResourceViewResponse response = new RuntimeInstanceResourceViewResponse();

        // 实例基本信息
        response.setInstanceId(instance.getId());
        response.setInstanceName(instance.getInstanceName());
        response.setInstanceStatus(instance.getInstanceStatus());
        response.setHealthStatus(instance.getHealthStatus());

        // 服务信息
        if (instance.getServiceId() != null) {
            response.setServiceId(instance.getServiceId());
            BaseServiceEntity service = baseServiceMapper.selectById(instance.getServiceId());
            if (service != null) {
                response.setServiceName(service.getServiceName());
            }
        }

        // 环境信息
        if (instance.getEnvId() != null) {
            response.setEnvId(instance.getEnvId());
            BaseEnv env = baseEnvMapper.selectById(instance.getEnvId());
            if (env != null) {
                response.setEnvName(env.getEnvName());
            }
        }

        // 主机信息
        if (instance.getHostId() != null) {
            response.setHostId(instance.getHostId());
            AssetHost host = assetHostMapper.selectById(instance.getHostId());
            if (host != null) {
                response.setHostname(host.getHostname());
                response.setIp(host.getIp());

                // 资源池信息（可空）
                if (host.getResourcePoolId() != null) {
                    response.setResourcePoolId(host.getResourcePoolId());
                    ResourcePool pool = resourcePoolMapper.selectById(host.getResourcePoolId());
                    if (pool != null) {
                        response.setResourcePoolName(pool.getPoolName());
                    }
                }
            }
        }

        // 当前版本信息（通过 envId + serviceId 查询 ReleaseCurrentVersion）
        if (instance.getServiceId() != null && instance.getEnvId() != null) {
            ReleaseCurrentVersion currentVersion = releaseCurrentVersionMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ReleaseCurrentVersion>()
                            .eq(ReleaseCurrentVersion::getEnvId, instance.getEnvId())
                            .eq(ReleaseCurrentVersion::getServiceId, instance.getServiceId())
            );
            if (currentVersion != null && currentVersion.getVersionId() != null) {
                response.setCurrentVersionId(currentVersion.getVersionId());
                ReleaseVersion version = releaseVersionMapper.selectById(currentVersion.getVersionId());
                if (version != null) {
                    response.setCurrentVersionNo(version.getVersionNo());
                }
            }
        }

        // 最近部署记录（通过 serviceId + envId + hostId 查询最新一条）
        if (instance.getServiceId() != null && instance.getEnvId() != null && instance.getHostId() != null) {
            RuntimeDeployRecord latestDeploy = runtimeDeployRecordMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<RuntimeDeployRecord>()
                            .eq(RuntimeDeployRecord::getServiceId, instance.getServiceId())
                            .eq(RuntimeDeployRecord::getEnvId, instance.getEnvId())
                            .eq(RuntimeDeployRecord::getHostId, instance.getHostId())
                            .orderByDesc(RuntimeDeployRecord::getCreatedAt)
                            .last("LIMIT 1")
            );
            if (latestDeploy != null) {
                response.setLatestDeployRecordId(latestDeploy.getId());
                response.setLatestDeployStatus(latestDeploy.getDeployStatus());
                response.setLatestDeployAt(latestDeploy.getEndedAt() != null ? latestDeploy.getEndedAt() : latestDeploy.getStartedAt());
            }
        }

        // 监控状态（通过 targetType='SERVICE_INSTANCE' + targetId 查询最新一条）
        MonitorSnapshot latestSnapshot = monitorSnapshotMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MonitorSnapshot>()
                        .eq(MonitorSnapshot::getTargetType, "SERVICE_INSTANCE")
                        .eq(MonitorSnapshot::getTargetId, instanceId)
                        .orderByDesc(MonitorSnapshot::getSnapshotTime)
                        .last("LIMIT 1")
        );
        if (latestSnapshot != null) {
            response.setLatestMonitorSnapshotAt(latestSnapshot.getSnapshotTime());
            // 尝试从 metricPayload 解析状态，如果解析失败则默认 UNKNOWN
            response.setMonitorStatus(parseMonitorStatus(latestSnapshot.getMetricPayload()));
        }

        return response;
    }

    /**
     * 从监控数据 payload 解析状态
     * 默认返回 UNKNOWN
     */
    private String parseMonitorStatus(String metricPayload) {
        if (metricPayload == null || metricPayload.isBlank()) {
            return "UNKNOWN";
        }
        // 简化处理：尝试从 JSON 中提取 status 字段
        // 如果格式不匹配或解析失败，返回 UNKNOWN
        try {
            if (metricPayload.contains("\"status\"")) {
                int statusIdx = metricPayload.indexOf("\"status\"");
                int valueStart = metricPayload.indexOf("\"", statusIdx + 9) + 1;
                int valueEnd = metricPayload.indexOf("\"", valueStart);
                if (valueStart > 0 && valueEnd > valueStart) {
                    return metricPayload.substring(valueStart, valueEnd);
                }
            }
        } catch (Exception e) {
            // 解析失败，返回 UNKNOWN
        }
        return "UNKNOWN";
    }
}
package com.infrahub.release.service;

import com.infrahub.common.api.PageResponse;
import com.infrahub.release.dto.*;
import com.infrahub.release.entity.ResourcePool;
import com.infrahub.release.enums.RiskLevel;
import com.infrahub.release.mapper.ResourcePoolMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ResourceCapacityRiskService {

    private static final String METRIC_CPU = "CPU";
    private static final String METRIC_MEMORY = "MEMORY";
    private static final String METRIC_DISK = "DISK";
    private static final String METRIC_OFFLINE_HOST = "OFFLINE_HOST";
    private static final String METRIC_UNKNOWN_HOST = "UNKNOWN_HOST";

    private final ResourcePoolService resourcePoolService;
    private final CapacityThresholdPolicyService thresholdPolicyService;
    private final ResourceGovernanceRecordService governanceRecordService;
    private final ResourcePoolMapper resourcePoolMapper;

    /**
     * 评估单个资源池的风险等级（不写治理记录）
     * 用于查询接口，避免读操作产生写副作用
     */
    public ResourcePoolRiskResponse evaluateRisk(Long resourcePoolId) {
        return evaluateRisk(resourcePoolId, false);
    }

    /**
     * 评估单个资源池的风险等级
     *
     * @param resourcePoolId 资源池ID
     * @param recordEvent 是否写入治理记录（WARNING/CRITICAL时）
     */
    public ResourcePoolRiskResponse evaluateRisk(Long resourcePoolId, boolean recordEvent) {
        ResourcePoolCapacityResponse capacity = resourcePoolService.getCapacity(resourcePoolId);
        if (capacity == null) {
            return null;
        }

        CapacityThreshold threshold = thresholdPolicyService.getThreshold(resourcePoolId);
        List<CapacityRiskItemResponse> risks = new ArrayList<>();
        RiskLevel overallRisk = RiskLevel.NORMAL;

        // 评估 CPU 使用率
        RiskLevel cpuRisk = evaluateUsagePercent(METRIC_CPU, capacity.getCpuUsagePercent(),
                threshold.getCpuWarningThreshold(), threshold.getCpuCriticalThreshold(), risks);
        overallRisk = RiskLevel.max(overallRisk, cpuRisk);

        // 评估内存使用率
        RiskLevel memoryRisk = evaluateUsagePercent(METRIC_MEMORY, capacity.getMemoryUsagePercent(),
                threshold.getMemoryWarningThreshold(), threshold.getMemoryCriticalThreshold(), risks);
        overallRisk = RiskLevel.max(overallRisk, memoryRisk);

        // 评估磁盘使用率
        RiskLevel diskRisk = evaluateUsagePercent(METRIC_DISK, capacity.getDiskUsagePercent(),
                threshold.getDiskWarningThreshold(), threshold.getDiskCriticalThreshold(), risks);
        overallRisk = RiskLevel.max(overallRisk, diskRisk);

        // 评估离线主机数
        RiskLevel offlineHostRisk = evaluateHostCount(METRIC_OFFLINE_HOST, capacity.getOfflineHostCount(),
                threshold.getOfflineHostWarningThreshold(), threshold.getOfflineHostCriticalThreshold(), risks);
        overallRisk = RiskLevel.max(overallRisk, offlineHostRisk);

        // 评估未知状态主机数
        RiskLevel unknownHostRisk = evaluateHostCount(METRIC_UNKNOWN_HOST, capacity.getUnknownHostCount(),
                threshold.getUnknownHostWarningThreshold(), threshold.getUnknownHostCriticalThreshold(), risks);
        overallRisk = RiskLevel.max(overallRisk, unknownHostRisk);

        // 如果所有指标都正常，添加一条正常状态记录
        if (risks.isEmpty()) {
            risks.add(CapacityRiskItemResponse.builder()
                    .metricType("OVERALL")
                    .currentValue(null)
                    .threshold(null)
                    .riskLevel(RiskLevel.NORMAL)
                    .reason("所有指标均在正常范围内")
                    .build());
        }

        // 收集风险原因列表
        List<String> riskReasons = risks.stream()
                .filter(r -> r.getRiskLevel() == RiskLevel.WARNING || r.getRiskLevel() == RiskLevel.CRITICAL)
                .map(CapacityRiskItemResponse::getReason)
                .toList();

        OffsetDateTime evaluatedAt = OffsetDateTime.now();
        ResourcePoolRiskResponse response = ResourcePoolRiskResponse.builder()
                .resourcePoolId(capacity.getResourcePoolId())
                .poolCode(capacity.getPoolCode())
                .poolName(capacity.getResourcePoolName())
                .resourcePoolName(capacity.getResourcePoolName())
                .envId(capacity.getEnvId())
                .envName(capacity.getEnvName())
                .riskLevel(overallRisk)
                .riskReasons(riskReasons)
                .cpuUsagePercent(capacity.getCpuUsagePercent())
                .memoryUsagePercent(capacity.getMemoryUsagePercent())
                .diskUsagePercent(capacity.getDiskUsagePercent())
                .onlineHostCount(capacity.getOnlineHostCount())
                .offlineHostCount(capacity.getOfflineHostCount())
                .unknownHostCount(capacity.getUnknownHostCount())
                .risks(risks)
                .evaluatedAt(evaluatedAt)
                .build();

        // Best-effort 写入治理记录（仅显式评估时）
        if (recordEvent) {
            recordGovernanceEvent(resourcePoolId, overallRisk, risks, evaluatedAt);
        }

        return response;
    }

    /**
     * 查询资源池风险列表（分页）
     */
    public PageResponse<ResourcePoolRiskResponse> listRisks(RiskQuery query) {
        // 先查询所有资源池
        List<ResourcePool> allPools = resourcePoolMapper.selectList(null);

        List<ResourcePoolRiskResponse> allRisks = new ArrayList<>();
        for (ResourcePool pool : allPools) {
            ResourcePoolRiskResponse risk = evaluateRisk(pool.getId());
            if (risk != null) {
                allRisks.add(risk);
            }
        }

        // 按筛选条件过滤
        List<ResourcePoolRiskResponse> filtered = allRisks.stream()
                .filter(r -> query.getEnvId() == null || query.getEnvId().equals(r.getEnvId()))
                .filter(r -> query.getRiskLevel() == null || query.getRiskLevel().equals(r.getRiskLevel()))
                .filter(r -> query.getResourcePoolId() == null || query.getResourcePoolId().equals(r.getResourcePoolId()))
                .toList();

        // 手动分页
        long total = filtered.size();
        int pageNum = (int) query.getPageNum();
        int pageSize = (int) query.getPageSize();
        int fromIndex = (pageNum - 1) * pageSize;
        int toIndex = Math.min(fromIndex + pageSize, filtered.size());

        List<ResourcePoolRiskResponse> pagedRecords = fromIndex < filtered.size()
                ? filtered.subList(fromIndex, toIndex)
                : List.of();

        return PageResponse.<ResourcePoolRiskResponse>builder()
                .pageNum(pageNum)
                .pageSize(pageSize)
                .total(total)
                .records(pagedRecords)
                .build();
    }

    private RiskLevel evaluateUsagePercent(String metricType, BigDecimal usagePercent,
                                           BigDecimal warningThreshold, BigDecimal criticalThreshold,
                                           List<CapacityRiskItemResponse> risks) {
        if (usagePercent == null) {
            risks.add(CapacityRiskItemResponse.builder()
                    .metricType(metricType)
                    .currentValue(null)
                    .threshold(null)
                    .riskLevel(RiskLevel.UNKNOWN)
                    .reason(metricType + "使用率数据缺失")
                    .build());
            return RiskLevel.UNKNOWN;
        }

        if (criticalThreshold != null && usagePercent.compareTo(criticalThreshold) >= 0) {
            risks.add(CapacityRiskItemResponse.builder()
                    .metricType(metricType)
                    .currentValue(usagePercent)
                    .threshold(criticalThreshold)
                    .riskLevel(RiskLevel.CRITICAL)
                    .reason(metricType + "使用率 " + usagePercent + "% 达到临界阈值 " + criticalThreshold + "%")
                    .build());
            return RiskLevel.CRITICAL;
        }

        if (warningThreshold != null && usagePercent.compareTo(warningThreshold) >= 0) {
            risks.add(CapacityRiskItemResponse.builder()
                    .metricType(metricType)
                    .currentValue(usagePercent)
                    .threshold(warningThreshold)
                    .riskLevel(RiskLevel.WARNING)
                    .reason(metricType + "使用率 " + usagePercent + "% 达到警告阈值 " + warningThreshold + "%")
                    .build());
            return RiskLevel.WARNING;
        }

        return RiskLevel.NORMAL;
    }

    private RiskLevel evaluateHostCount(String metricType, Long count,
                                        Long warningThreshold, Long criticalThreshold,
                                        List<CapacityRiskItemResponse> risks) {
        if (count == null) {
            count = 0L;
        }

        if (criticalThreshold != null && count >= criticalThreshold) {
            risks.add(CapacityRiskItemResponse.builder()
                    .metricType(metricType)
                    .currentValue(new BigDecimal(count))
                    .threshold(new BigDecimal(criticalThreshold))
                    .riskLevel(RiskLevel.CRITICAL)
                    .reason(metricType + "数量 " + count + " 达到临界阈值 " + criticalThreshold)
                    .build());
            return RiskLevel.CRITICAL;
        }

        if (warningThreshold != null && count >= warningThreshold) {
            risks.add(CapacityRiskItemResponse.builder()
                    .metricType(metricType)
                    .currentValue(new BigDecimal(count))
                    .threshold(new BigDecimal(warningThreshold))
                    .riskLevel(RiskLevel.WARNING)
                    .reason(metricType + "数量 " + count + " 达到警告阈值 " + warningThreshold)
                    .build());
            return RiskLevel.WARNING;
        }

        return RiskLevel.NORMAL;
    }

    private void recordGovernanceEvent(Long resourcePoolId, RiskLevel overallRisk,
                                        List<CapacityRiskItemResponse> risks, OffsetDateTime evaluatedAt) {
        if (overallRisk != RiskLevel.WARNING && overallRisk != RiskLevel.CRITICAL) {
            return;
        }

        try {
            String reason = risks.stream()
                    .filter(r -> r.getRiskLevel() == RiskLevel.WARNING || r.getRiskLevel() == RiskLevel.CRITICAL)
                    .map(CapacityRiskItemResponse::getReason)
                    .reduce((a, b) -> a + "; " + b)
                    .orElse("风险评估完成");

            governanceRecordService.record(
                    "RESOURCE_CAPACITY_RISK_EVALUATED",
                    resourcePoolId,
                    null, // hostId
                    null, // runtimeInstanceId
                    null, // releaseOrderId
                    null, // rollbackOrderId
                    "SYSTEM", // executorType
                    null, // envId
                    null, // serviceId
                    null, // versionId
                    "RESOURCE_POOL", // subjectType
                    resourcePoolId, // subjectId
                    null, // beforePayload
                    java.util.Map.of("riskLevel", overallRisk.name(), "evaluatedAt", evaluatedAt.toString()),
                    "SUCCESS",
                    reason,
                    null // createdBy (system)
            );
        } catch (Exception e) {
            log.warn("Failed to record governance event for resource pool {}: {}", resourcePoolId, e.getMessage());
        }
    }
}
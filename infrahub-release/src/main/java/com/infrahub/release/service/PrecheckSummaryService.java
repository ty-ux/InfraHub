package com.infrahub.release.service;

import com.infrahub.release.entity.AssetHost;
import com.infrahub.base.entity.BaseServiceEntity;
import com.infrahub.base.service.BaseServiceEntityService;
import com.infrahub.release.dto.*;
import com.infrahub.release.entity.ReleaseOrder;
import com.infrahub.release.entity.ReleaseRollbackOrder;
import com.infrahub.release.entity.RuntimeDeployRecord;
import com.infrahub.release.entity.RuntimeServiceInstance;
import com.infrahub.release.mapper.ReleaseOrderMapper;
import com.infrahub.release.mapper.ReleaseRollbackOrderMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class PrecheckSummaryService {

    private final ReleaseOrderMapper releaseOrderMapper;
    private final ReleaseRollbackOrderMapper releaseRollbackOrderMapper;
    private final RuntimeDeployRecordService runtimeDeployRecordService;
    private final AssetHostService assetHostService;
    private final BaseServiceEntityService baseServiceEntityService;
    private final RuntimeServiceInstanceService runtimeServiceInstanceService;
    private final DeployFailureCategorizer deployFailureCategorizer;

    public PrecheckSummaryResponse getReleasePrecheckSummary(Long releaseOrderId) {
        ReleaseOrder order = releaseOrderMapper.selectById(releaseOrderId);
        if (order == null) {
            throw new IllegalArgumentException("发布单不存在: " + releaseOrderId);
        }
        List<RuntimeDeployRecord> records = runtimeDeployRecordService.listByReleaseOrderId(releaseOrderId);
        return buildSummary(releaseOrderId, "RELEASE", order.getServiceId(), order.getEnvId(), records);
    }

    public PrecheckSummaryResponse getRollbackPrecheckSummary(Long rollbackOrderId) {
        ReleaseRollbackOrder rollbackOrder = releaseRollbackOrderMapper.selectById(rollbackOrderId);
        if (rollbackOrder == null) {
            throw new IllegalArgumentException("回滚单不存在: " + rollbackOrderId);
        }
        ReleaseOrder releaseOrder = releaseOrderMapper.selectById(rollbackOrder.getReleaseOrderId());
        Long serviceId = releaseOrder != null ? releaseOrder.getServiceId() : null;
        Long envId = releaseOrder != null ? releaseOrder.getEnvId() : null;
        List<RuntimeDeployRecord> records = runtimeDeployRecordService.listByRollbackOrderId(rollbackOrderId);
        return buildSummary(rollbackOrderId, "ROLLBACK", serviceId, envId, records);
    }

    private PrecheckSummaryResponse buildSummary(Long orderId, String orderType,
                                                  Long serviceId, Long envId,
                                                  List<RuntimeDeployRecord> records) {
        PrecheckSummaryResponse response = new PrecheckSummaryResponse();
        response.setOrderId(orderId);
        response.setOrderType(orderType);
        response.setGeneratedAt(LocalDateTime.now());

        if (records == null || records.isEmpty()) {
            response.setOverallStatus("UNKNOWN");
            response.setTotalHosts(0);
            response.setSuccessHosts(0);
            response.setFailedHosts(0);
            response.setTotalSteps(0);
            response.setFailedSteps(0);
            response.setHostSummaries(Collections.emptyList());
            response.setFailureCategories(Collections.emptyList());
            response.setSuggestions(Collections.emptyList());
            return response;
        }

        Map<Long, List<RuntimeDeployRecord>> recordsByHost = new LinkedHashMap<>();
        for (RuntimeDeployRecord record : records) {
            Long hostId = record.getHostId();
            recordsByHost.computeIfAbsent(hostId != null ? hostId : 0L, k -> new ArrayList<>()).add(record);
        }

        List<PrecheckHostSummaryItem> hostSummaries = new ArrayList<>();
        int totalSuccessHosts = 0;
        int totalFailedHosts = 0;
        int totalSteps = 0;
        int totalFailedSteps = 0;
        Map<String, Integer> failureCategoryCount = new LinkedHashMap<>();
        List<String> errorMessages = new ArrayList<>();

        for (Map.Entry<Long, List<RuntimeDeployRecord>> entry : recordsByHost.entrySet()) {
            Long hostId = entry.getKey();
            List<RuntimeDeployRecord> hostRecords = entry.getValue();

            PrecheckHostSummaryItem hostItem = new PrecheckHostSummaryItem();
            hostItem.setHostId(hostId == 0L ? null : hostId);

            AssetHost host = hostId != 0L ? assetHostService.getById(hostId) : null;
            hostItem.setHostName(host != null ? host.getHostname() : null);

            int hostSuccess = 0;
            int hostFailed = 0;
            String lastError = null;

            for (RuntimeDeployRecord record : hostRecords) {
                totalSteps++;
                if ("SUCCESS".equals(record.getDeployStatus())) {
                    hostSuccess++;
                } else if ("FAILED".equals(record.getDeployStatus())) {
                    hostFailed++;
                    totalFailedSteps++;
                    if (record.getDeployLog() != null) {
                        lastError = record.getDeployLog();
                        String category = categorizeFailure(record.getDeployLog());
                        failureCategoryCount.merge(category, 1, Integer::sum);
                    }
                }
            }

            hostItem.setSuccessSteps(hostSuccess);
            hostItem.setFailedSteps(hostFailed);
            hostItem.setLastErrorMessage(truncate(lastError, 500));

            if (hostFailed > 0 && hostSuccess > 0) {
                hostItem.setStatus("PARTIAL");
                totalFailedHosts++;
            } else if (hostFailed > 0) {
                hostItem.setStatus("FAILED");
                totalFailedHosts++;
            } else {
                hostItem.setStatus("SUCCESS");
                totalSuccessHosts++;
            }

            hostSummaries.add(hostItem);
        }

        response.setTotalHosts(hostSummaries.size());
        response.setSuccessHosts(totalSuccessHosts);
        response.setFailedHosts(totalFailedHosts);
        response.setTotalSteps(totalSteps);
        response.setFailedSteps(totalFailedSteps);
        response.setHostSummaries(hostSummaries);

        List<PrecheckFailureCategoryItem> failureCategories = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : failureCategoryCount.entrySet()) {
            PrecheckFailureCategoryItem cat = new PrecheckFailureCategoryItem();
            cat.setCategory(entry.getKey());
            cat.setCount(entry.getValue());
            cat.setMessage(getCategoryMessage(entry.getKey()));
            failureCategories.add(cat);
        }
        response.setFailureCategories(failureCategories);

        response.setSuggestions(buildSuggestions(failureCategories, serviceId, envId));

        if (totalFailedSteps == 0 && totalSteps > 0) {
            response.setOverallStatus("SUCCESS");
        } else if (totalFailedSteps > 0 && totalFailedSteps < totalSteps) {
            response.setOverallStatus("PARTIAL");
        } else if (totalFailedSteps > 0) {
            response.setOverallStatus("FAILED");
        } else {
            response.setOverallStatus("UNKNOWN");
        }

        return response;
    }

    private String categorizeFailure(String deployLog) {
        if (deployLog == null) return "UNKNOWN";
        String lower = deployLog.toLowerCase();
        if (lower.contains("config") || lower.contains("配置") || lower.contains("missing")) {
            return "CONFIG";
        }
        if (lower.contains("connection refused") || lower.contains("ssh") || lower.contains("timeout")
                || lower.contains("connect")) {
            return "CONNECTIVITY";
        }
        if (lower.contains("permission denied") || lower.contains("权限")) {
            return "PERMISSION";
        }
        if (lower.contains("disk") || lower.contains("memory") || lower.contains("space")
                || lower.contains("resource")) {
            return "RESOURCE";
        }
        if (lower.contains("dependency") || lower.contains("依赖") || lower.contains("java")
                || lower.contains("not found")) {
            return "DEPENDENCY";
        }
        return "UNKNOWN";
    }

    private String getCategoryMessage(String category) {
        return switch (category) {
            case "CONFIG" -> "部署配置相关问题";
            case "CONNECTIVITY" -> "网络连通性问题";
            case "PERMISSION" -> "权限不足问题";
            case "RESOURCE" -> "资源不足问题";
            case "DEPENDENCY" -> "依赖环境问题";
            default -> "未分类问题";
        };
    }

    private List<PrecheckSuggestionItem> buildSuggestions(List<PrecheckFailureCategoryItem> categories,
                                                           Long serviceId, Long envId) {
        List<PrecheckSuggestionItem> suggestions = new ArrayList<>();
        for (PrecheckFailureCategoryItem cat : categories) {
            PrecheckSuggestionItem item = new PrecheckSuggestionItem();
            Map<String, Object> params = new LinkedHashMap<>();
            if (serviceId != null) params.put("serviceId", serviceId);
            if (envId != null) params.put("envId", envId);

            switch (cat.getCategory()) {
                case "CONFIG" -> {
                    item.setSuggestionType("CONFIG_EDIT");
                    item.setTitle("补全部署配置");
                    item.setDescription("部分部署配置项缺失或不正确，请检查并补全");
                    item.setTargetRoute("/config/env-configs");
                    item.setTargetParams(params);
                }
                case "CONNECTIVITY" -> {
                    item.setSuggestionType("CHECK_INSTANCE");
                    item.setTitle("检查实例连通性");
                    item.setDescription("目标主机连接异常，请检查网络和SSH配置");
                    item.setTargetRoute("/resource/runtime-instances");
                    item.setTargetParams(params);
                }
                case "PERMISSION" -> {
                    item.setSuggestionType("CONTACT_OWNER");
                    item.setTitle("联系管理员授权");
                    item.setDescription("部署操作权限不足，请联系环境管理员");
                }
                case "RESOURCE" -> {
                    item.setSuggestionType("CHECK_INSTANCE");
                    item.setTitle("检查资源使用情况");
                    item.setDescription("目标主机资源不足，请检查磁盘空间或内存");
                    item.setTargetRoute("/resource/runtime-instances");
                    item.setTargetParams(params);
                }
                case "DEPENDENCY" -> {
                    item.setSuggestionType("RETRY_PRECHECK");
                    item.setTitle("检查运行环境依赖");
                    item.setDescription("运行环境依赖不满足，请确认运行时版本");
                }
                default -> {
                    item.setSuggestionType("VIEW_LOG");
                    item.setTitle("查看详细日志");
                    item.setDescription("请查看部署日志定位具体问题");
                }
            }
            suggestions.add(item);
        }

        if (suggestions.isEmpty()) {
            PrecheckSuggestionItem retryItem = new PrecheckSuggestionItem();
            retryItem.setSuggestionType("RETRY_PRECHECK");
            retryItem.setTitle("重新执行预检");
            retryItem.setDescription("当前无预检记录，建议重新执行预检");
            suggestions.add(retryItem);
        }

        return suggestions;
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return null;
        return text.length() > maxLen ? text.substring(0, maxLen) + "..." : text;
    }
}

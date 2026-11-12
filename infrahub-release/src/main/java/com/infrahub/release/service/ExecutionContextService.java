package com.infrahub.release.service;

import com.infrahub.base.entity.BaseApp;
import com.infrahub.base.entity.BaseEnv;
import com.infrahub.base.entity.BaseServiceEntity;
import com.infrahub.base.service.BaseAppService;
import com.infrahub.base.service.BaseEnvService;
import com.infrahub.base.service.BaseServiceEntityService;
import com.infrahub.release.dto.*;
import com.infrahub.release.entity.*;
import com.infrahub.release.mapper.ReleaseOrderMapper;
import com.infrahub.release.mapper.ReleaseRollbackOrderMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class ExecutionContextService {

    private final ReleaseOrderMapper releaseOrderMapper;
    private final ReleaseRollbackOrderMapper releaseRollbackOrderMapper;
    private final ReleaseCurrentVersionService releaseCurrentVersionService;
    private final ReleaseVersionService releaseVersionService;
    private final BaseAppService baseAppService;
    private final BaseEnvService baseEnvService;
    private final BaseServiceEntityService baseServiceEntityService;
    private final DeployConfigCheckService deployConfigCheckService;
    private final RuntimeDeployRecordService runtimeDeployRecordService;
    private final DeployFailureCategorizer deployFailureCategorizer;

    public ExecutionContextResponse getReleaseExecutionContext(Long releaseOrderId) {
        ReleaseOrder order = releaseOrderMapper.selectById(releaseOrderId);
        if (order == null) {
            throw new IllegalArgumentException("发布单不存在: " + releaseOrderId);
        }
        return buildContext(releaseOrderId, "RELEASE",
                order.getAppId(), order.getEnvId(), order.getServiceId(),
                order.getVersionId(), null);
    }

    public ExecutionContextResponse getRollbackExecutionContext(Long rollbackOrderId) {
        ReleaseRollbackOrder rollbackOrder = releaseRollbackOrderMapper.selectById(rollbackOrderId);
        if (rollbackOrder == null) {
            throw new IllegalArgumentException("回滚单不存在: " + rollbackOrderId);
        }
        ReleaseOrder releaseOrder = releaseOrderMapper.selectById(rollbackOrder.getReleaseOrderId());
        Long appId = releaseOrder != null ? releaseOrder.getAppId() : null;
        Long envId = releaseOrder != null ? releaseOrder.getEnvId() : null;
        Long serviceId = releaseOrder != null ? releaseOrder.getServiceId() : null;
        return buildContext(rollbackOrderId, "ROLLBACK",
                appId, envId, serviceId,
                rollbackOrder.getToVersionId(), rollbackOrder.getFromVersionId());
    }

    private ExecutionContextResponse buildContext(Long orderId, String orderType,
                                                  Long appId, Long envId, Long serviceId,
                                                  Long targetVersionId, Long fromVersionId) {
        ExecutionContextResponse response = new ExecutionContextResponse();
        response.setOrderId(orderId);
        response.setOrderType(orderType);
        response.setGeneratedAt(LocalDateTime.now());

        // App info
        response.setAppId(appId);
        BaseApp app = appId != null ? baseAppService.getById(appId) : null;
        response.setAppName(app != null ? app.getAppName() : null);

        // Env info
        response.setEnvId(envId);
        BaseEnv env = envId != null ? baseEnvService.getById(envId) : null;
        response.setEnvName(env != null ? env.getEnvName() : null);

        // Service info
        response.setServiceId(serviceId);
        BaseServiceEntity service = serviceId != null ? baseServiceEntityService.getById(serviceId) : null;
        response.setServiceName(service != null ? service.getServiceName() : null);

        // Current version
        ReleaseCurrentVersion currentVersion = null;
        if (envId != null && serviceId != null) {
            currentVersion = findCurrentVersion(envId, serviceId);
        }
        if (currentVersion != null) {
            response.setCurrentVersionId(currentVersion.getVersionId());
            ReleaseVersion currentVer = releaseVersionService.getById(currentVersion.getVersionId());
            response.setCurrentVersionNo(currentVer != null ? currentVer.getVersionNo() : null);
        }

        // Target version
        response.setTargetVersionId(targetVersionId);
        if (targetVersionId != null) {
            ReleaseVersion targetVer = releaseVersionService.getById(targetVersionId);
            response.setTargetVersionNo(targetVer != null ? targetVer.getVersionNo() : null);
        }

        // Deploy config completeness
        Boolean configComplete = null;
        if (appId != null && envId != null) {
            try {
                DeployConfigCheckResponse configCheck = deployConfigCheckService.checkDeployConfig(appId, envId, serviceId);
                configComplete = configCheck.getOverallComplete();
            } catch (Exception e) {
                configComplete = false;
            }
        }
        response.setDeployConfigComplete(configComplete != null ? configComplete : false);

        // Precheck status from deploy records
        List<RuntimeDeployRecord> records;
        if ("RELEASE".equals(orderType)) {
            records = runtimeDeployRecordService.listByReleaseOrderId(orderId);
        } else {
            records = runtimeDeployRecordService.listByRollbackOrderId(orderId);
        }
        String precheckStatus = derivePrecheckStatus(records);
        response.setPrecheckStatus(precheckStatus);

        // Build risks
        List<ExecutionRiskItem> risks = buildRisks(configComplete, precheckStatus,
                response.getCurrentVersionId(), targetVersionId);
        response.setRisks(risks);

        // Derive risk level from risks
        String riskLevel = "LOW";
        for (ExecutionRiskItem risk : risks) {
            if ("HIGH".equals(risk.getLevel())) {
                riskLevel = "HIGH";
                break;
            } else if ("MEDIUM".equals(risk.getLevel())) {
                riskLevel = "MEDIUM";
            }
        }
        if (risks.isEmpty()) {
            riskLevel = "LOW";
        }
        response.setRiskLevel(riskLevel);

        // Version compare
        List<VersionCompareItem> versionCompare = buildVersionCompare(
                response.getCurrentVersionId(), targetVersionId);
        response.setVersionCompare(versionCompare);

        return response;
    }

    private String derivePrecheckStatus(List<RuntimeDeployRecord> records) {
        if (records == null || records.isEmpty()) {
            return "UNKNOWN";
        }
        boolean hasSuccess = false;
        boolean hasFailed = false;
        for (RuntimeDeployRecord record : records) {
            if ("SUCCESS".equals(record.getDeployStatus())) {
                hasSuccess = true;
            } else if ("FAILED".equals(record.getDeployStatus())) {
                hasFailed = true;
            }
        }
        if (hasSuccess && hasFailed) return "PARTIAL";
        if (hasFailed) return "FAILED";
        if (hasSuccess) return "SUCCESS";
        return "UNKNOWN";
    }

    private List<ExecutionRiskItem> buildRisks(Boolean configComplete, String precheckStatus,
                                                Long currentVersionId, Long targetVersionId) {
        List<ExecutionRiskItem> risks = new ArrayList<>();

        if (configComplete != null && !configComplete) {
            ExecutionRiskItem risk = new ExecutionRiskItem();
            risk.setRiskType("CONFIG_MISSING");
            risk.setLevel("HIGH");
            risk.setMessage("部署配置不完整，部分必要配置项缺失");
            risk.setSuggestion("请先补全部署配置后再执行");
            risks.add(risk);
        }

        if ("FAILED".equals(precheckStatus)) {
            ExecutionRiskItem risk = new ExecutionRiskItem();
            risk.setRiskType("PRECHECK_FAILED");
            risk.setLevel("HIGH");
            risk.setMessage("预检执行失败");
            risk.setSuggestion("请查看预检详情并修复问题后重试");
            risks.add(risk);
        } else if ("PARTIAL".equals(precheckStatus)) {
            ExecutionRiskItem risk = new ExecutionRiskItem();
            risk.setRiskType("PRECHECK_FAILED");
            risk.setLevel("MEDIUM");
            risk.setMessage("部分预检项未通过");
            risk.setSuggestion("请检查未通过的预检项");
            risks.add(risk);
        }

        if (currentVersionId != null && targetVersionId != null
                && currentVersionId.equals(targetVersionId)) {
            ExecutionRiskItem risk = new ExecutionRiskItem();
            risk.setRiskType("VERSION_MISMATCH");
            risk.setLevel("LOW");
            risk.setMessage("目标版本与当前版本相同");
            risk.setSuggestion("确认是否需要执行版本变更");
            risks.add(risk);
        }

        return risks;
    }

    // package-private for testing
    ReleaseCurrentVersion findCurrentVersion(Long envId, Long serviceId) {
        return releaseCurrentVersionService.lambdaQuery()
                .eq(ReleaseCurrentVersion::getEnvId, envId)
                .eq(ReleaseCurrentVersion::getServiceId, serviceId)
                .one();
    }

    private List<VersionCompareItem> buildVersionCompare(Long currentVersionId, Long targetVersionId) {
        List<VersionCompareItem> compareItems = new ArrayList<>();

        ReleaseVersion current = currentVersionId != null ? releaseVersionService.getById(currentVersionId) : null;
        ReleaseVersion target = targetVersionId != null ? releaseVersionService.getById(targetVersionId) : null;

        VersionCompareItem versionNoItem = new VersionCompareItem();
        versionNoItem.setFieldName("version.versionNo");
        versionNoItem.setCurrentValue(current != null ? current.getVersionNo() : null);
        versionNoItem.setTargetValue(target != null ? target.getVersionNo() : null);
        versionNoItem.setChanged(!Objects.equals(
                current != null ? current.getVersionNo() : null,
                target != null ? target.getVersionNo() : null));
        compareItems.add(versionNoItem);

        VersionCompareItem createdAtItem = new VersionCompareItem();
        createdAtItem.setFieldName("version.createdAt");
        createdAtItem.setCurrentValue(current != null && current.getCreatedAt() != null ? current.getCreatedAt().toString() : null);
        createdAtItem.setTargetValue(target != null && target.getCreatedAt() != null ? target.getCreatedAt().toString() : null);
        createdAtItem.setChanged(!Objects.equals(
                current != null ? current.getCreatedAt() : null,
                target != null ? target.getCreatedAt() : null));
        compareItems.add(createdAtItem);

        return compareItems;
    }
}

package com.infrahub.release.service;

import com.infrahub.base.entity.BaseApp;
import com.infrahub.base.entity.BaseEnv;
import com.infrahub.base.entity.BaseServiceEntity;
import com.infrahub.base.entity.ConfigEnvConfig;
import com.infrahub.base.service.BaseAppService;
import com.infrahub.base.service.BaseEnvService;
import com.infrahub.base.service.BaseServiceEntityService;
import com.infrahub.base.service.ConfigEnvConfigService;
import com.infrahub.release.dto.DeployConfigCheckResponse;
import com.infrahub.release.dto.DeployConfigItem;
import com.infrahub.release.dto.DeployConfigServiceCheckItem;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class DeployConfigCheckService {

    private final ConfigEnvConfigService configEnvConfigService;
    private final BaseAppService baseAppService;
    private final BaseEnvService baseEnvService;
    private final BaseServiceEntityService baseServiceEntityService;
    private final SecretValueResolver secretValueResolver;

    /**
     * 与运行期实际读取的键保持一致：SSH 部署路径只强制要求 {@code deploy.command}
     * （{@code SshReleaseExecutor} / {@code ReleaseDeployExecutionService} 缺它就拒绝执行），
     * 其余键都有默认值或可选。
     *
     * <p>历史清单里的 {@code deploy.package.path} / {@code deploy.start.command} 等键
     * 运行期从不读取，按"必需"检查会让页面报出与实际部署无关的缺失项，因此不再列入。
     */
    private static final Set<String> REQUIRED_KEYS = Set.of(
            "deploy.command"
    );

    private static final Set<String> RECOMMENDED_KEYS = Set.of(
            "deploy.root",
            "deploy.port",
            "deploy.pidFile",
            "deploy.logFile",
            "deploy.healthUrl",
            "rollback.command"
    );

    /**
     * 运行期会读取、但缺失时使用空串的键（命令模板渲染变量）。
     *
     * <p>列出来不是为了要求配置，而是为了让"同键多条"的判歧义覆盖到它们：
     * {@code DeployConfigService.RUNTIME_KEYS} 含同样的键，两边对歧义的定义必须一致。
     */
    private static final Set<String> OPTIONAL_KEYS = Set.of(
            "deploy.deploy.path",
            "deploy.backup.path",
            "deploy.package.path"
    );

    private static final Set<String> ALL_CHECK_KEYS = new LinkedHashSet<>();
    static {
        ALL_CHECK_KEYS.addAll(REQUIRED_KEYS);
        ALL_CHECK_KEYS.addAll(RECOMMENDED_KEYS);
        ALL_CHECK_KEYS.addAll(OPTIONAL_KEYS);
    }

    public DeployConfigCheckResponse checkDeployConfig(Long appId, Long envId, Long serviceId) {
        BaseApp app = baseAppService.getById(appId);
        BaseEnv env = baseEnvService.getById(envId);

        String appName = app != null ? app.getAppName() : null;
        String envName = env != null ? env.getEnvName() : null;

        List<BaseServiceEntity> servicesToCheck;
        if (serviceId != null) {
            BaseServiceEntity service = baseServiceEntityService.getById(serviceId);
            if (service == null || !service.getAppId().equals(appId)) {
                servicesToCheck = List.of();
            } else {
                servicesToCheck = List.of(service);
            }
        } else {
            servicesToCheck = baseServiceEntityService.lambdaQuery()
                    .eq(BaseServiceEntity::getAppId, appId)
                    .eq(BaseServiceEntity::getIsDeleted, Boolean.FALSE)
                    .list();
        }

        List<DeployConfigServiceCheckItem> serviceCheckItems = new ArrayList<>();
        List<String> globalMissingKeys = new ArrayList<>();
        List<String> globalWarnings = new ArrayList<>();
        boolean overallComplete = true;

        Map<String, List<ConfigEnvConfig>> appConfigs = loadConfigs(appId, envId);
        Map<String, List<ConfigEnvConfig>> envConfigs = loadEnvironmentLevelConfigs(envId);

        // 环境级(app_id 为空)的键在运行期不被任何部署/诊断路径读取：这里只如实说明它们存在，
        // 既不当作"已配置"，也不阻止页面展示。
        for (String key : ALL_CHECK_KEYS) {
            if (envConfigs.containsKey(key) && !appConfigs.containsKey(key)) {
                globalWarnings.add("配置项 " + key
                        + " 只存在于环境级(app_id 为空)；SSH 部署、回滚与部署诊断按应用级读取，该值不会生效");
            }
        }

        for (BaseServiceEntity service : servicesToCheck) {
            DeployConfigServiceCheckItem item = checkServiceConfig(service.getId(), service.getServiceName(), appConfigs, envConfigs);
            serviceCheckItems.add(item);

            if (!item.getComplete()) {
                overallComplete = false;
                globalMissingKeys.addAll(item.getMissingKeys());
            }
            globalWarnings.addAll(item.getWarnings());
        }

        globalMissingKeys = globalMissingKeys.stream().distinct().sorted().toList();
        globalWarnings = globalWarnings.stream().distinct().sorted().toList();

        DeployConfigCheckResponse response = new DeployConfigCheckResponse();
        response.setAppId(appId);
        response.setAppName(appName);
        response.setEnvId(envId);
        response.setEnvName(envName);
        response.setOverallComplete(overallComplete);
        response.setServices(serviceCheckItems);
        response.setMissingKeys(globalMissingKeys);
        response.setWarnings(globalWarnings);
        response.setCheckedAt(OffsetDateTime.now());

        return response;
    }

    private DeployConfigServiceCheckItem checkServiceConfig(Long serviceId,
                                                            String serviceName,
                                                            Map<String, List<ConfigEnvConfig>> appConfigs,
                                                            Map<String, List<ConfigEnvConfig>> envConfigs) {

        List<DeployConfigItem> configItems = new ArrayList<>();
        List<String> missingKeys = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        boolean complete = true;

        for (String key : ALL_CHECK_KEYS) {
            boolean required = REQUIRED_KEYS.contains(key);
            List<ConfigEnvConfig> appRows = appConfigs.get(key);

            DeployConfigItem item = new DeployConfigItem();
            item.setConfigKey(key);
            item.setRequired(required);

            if (appRows != null && appRows.size() > 1) {
                // 同键多条：运行期取值由数据库返回顺序决定，没有确定优先级。
                // 运行期（DeployConfigService.loadDeployConfig）对同样的键直接拒绝执行；
                // 这里不选一条展示、不猜、也不改库，判为不可用并让完整性判定失败。
                item.setSource("APP");
                item.setValueType(null);
                item.setHasValue(false);
                item.setConfigValue(null);
                item.setAmbiguous(true);
                item.setDuplicateCount(appRows.size());
                complete = false;
                warnings.add("配置项 " + key + " 在同一应用/环境下存在 " + appRows.size()
                        + " 条记录，无法判定哪一条生效；运行期会直接拒绝执行，"
                        + "请只保留一条有效配置后重新检查（本接口不修改数据）");
                missingKeys.add(key);
                configItems.add(item);
                continue;
            }

            ConfigItemInfo info = appRows == null
                    ? null
                    : createItemInfo(appRows.get(0), "APP");
            boolean hasValue = info != null && info.hasValue;
            boolean environmentLevelOnly = info == null && envConfigs.containsKey(key);

            // 只有环境级记录时如实标注来源，但**不计为已配置**：运行期按应用级读取，这个值不会生效。
            item.setSource(info != null ? info.source : (environmentLevelOnly ? "ENV" : null));
            item.setValueType(info != null ? info.valueType : null);
            item.setHasValue(hasValue);
            item.setConfigValue(hasValue ? displayValue(key, info) : null);
            item.setAmbiguous(false);
            configItems.add(item);

            if (required && !hasValue) {
                complete = false;
                missingKeys.add(key);
            } else if (!required && RECOMMENDED_KEYS.contains(key) && !hasValue) {
                warnings.add("推荐配置项缺失: " + key);
            }
        }

        DeployConfigServiceCheckItem result = new DeployConfigServiceCheckItem();
        result.setServiceId(serviceId);
        result.setServiceName(serviceName);
        result.setComplete(complete);
        result.setMissingKeys(missingKeys);
        result.setWarnings(warnings);
        result.setConfigItems(configItems);

        return result;
    }

    /** 应用级配置：运行期唯一实际读取的来源（{@code DeployConfigService} 按 appId + envId 查询）。 */
    private Map<String, List<ConfigEnvConfig>> loadConfigs(Long appId, Long envId) {
        List<ConfigEnvConfig> configs = configEnvConfigService.lambdaQuery()
                .eq(ConfigEnvConfig::getEnvId, envId)
                .eq(ConfigEnvConfig::getAppId, appId)
                .eq(ConfigEnvConfig::getIsDeleted, Boolean.FALSE)
                .and(wrapper -> wrapper.likeRight(ConfigEnvConfig::getConfigKey, "deploy.")
                        .or()
                        .likeRight(ConfigEnvConfig::getConfigKey, "rollback."))
                .list();
        return groupByKey(configs);
    }

    /**
     * 环境级配置（{@code app_id IS NULL}）。
     *
     * <p>用 {@code isNull} 而不是 {@code eq(..., null)}：后者生成 {@code app_id = NULL}，
     * 在 SQL 三值逻辑下恒不成立，会把"有环境级记录"误判成"没有"。
     */
    private Map<String, List<ConfigEnvConfig>> loadEnvironmentLevelConfigs(Long envId) {
        List<ConfigEnvConfig> configs = configEnvConfigService.lambdaQuery()
                .eq(ConfigEnvConfig::getEnvId, envId)
                .isNull(ConfigEnvConfig::getAppId)
                .eq(ConfigEnvConfig::getIsDeleted, Boolean.FALSE)
                .and(wrapper -> wrapper.likeRight(ConfigEnvConfig::getConfigKey, "deploy.")
                        .or()
                        .likeRight(ConfigEnvConfig::getConfigKey, "rollback."))
                .list();
        return groupByKey(configs);
    }

    private static Map<String, List<ConfigEnvConfig>> groupByKey(List<ConfigEnvConfig> configs) {
        Map<String, List<ConfigEnvConfig>> grouped = new LinkedHashMap<>();
        for (ConfigEnvConfig config : configs) {
            if (!ALL_CHECK_KEYS.contains(config.getConfigKey())) {
                continue;
            }
            grouped.computeIfAbsent(config.getConfigKey(), key -> new ArrayList<>()).add(config);
        }
        return grouped;
    }

    private ConfigItemInfo createItemInfo(ConfigEnvConfig config, String source) {
        String valueType = config.getValueType();
        String value;
        boolean hasValue;

        if ("SECRET_REF".equalsIgnoreCase(valueType)) {
            try {
                String resolved = secretValueResolver.resolve(config.getSecretRefId());
                hasValue = resolved != null && !resolved.isBlank();
                value = null;
            } catch (Exception e) {
                hasValue = false;
                value = null;
            }
        } else {
            value = config.getConfigValue();
            hasValue = value != null && !value.isBlank();
        }

        return new ConfigItemInfo(value, valueType, source, hasValue);
    }

    /**
     * 回显给页面的值：命令类与凭据类恒为 {@code ***}。
     *
     * <p>{@code deploy.command} / {@code rollback.command} 是可直接执行的脚本，历史上常内嵌口令、令牌或
     * 数据库连接串；本接口只回答"有没有配"，不需要把内容送到浏览器。
     */
    private static String displayValue(String key, ConfigItemInfo info) {
        if (info == null) {
            return null;
        }
        if ("SECRET_REF".equalsIgnoreCase(info.valueType)) {
            return "***";
        }
        if (isNeverDisplayedKey(key)) {
            return "***";
        }
        return info.value;
    }

    private static boolean isNeverDisplayedKey(String key) {
        if (key == null) {
            return false;
        }
        String lower = key.toLowerCase(Locale.ROOT);
        return lower.endsWith(".command")
                || lower.endsWith(".password")
                || lower.endsWith(".secret")
                || lower.endsWith(".token")
                || lower.endsWith(".key");
    }

    private static class ConfigItemInfo {
        final String value;
        final String valueType;
        final String source;
        final boolean hasValue;

        ConfigItemInfo(String value, String valueType, String source, boolean hasValue) {
            this.value = value;
            this.valueType = valueType;
            this.source = source;
            this.hasValue = hasValue;
        }
    }
}

package com.infrahub.release.service;

import com.infrahub.base.entity.ConfigEnvConfig;
import com.infrahub.base.service.ConfigEnvConfigService;
import com.infrahub.common.error.ErrorCode;
import com.infrahub.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 部署配置的运行期读取。
 *
 * <p><b>歧义即拒绝</b>：同一应用、同一环境下，同一个**运行期部署键**如果有多条未删除记录，
 * 数据库返回顺序决定"最后一条生效"，这是不确定的。本服务不在运行期猜优先级，也不改库，
 * 而是在产生任何外部执行动作之前抛出受控错误（消息只含键名、应用/环境标识与条数，
 * 不含任何配置值、命令原文或凭据）。
 *
 * <p><b>与检查页一致</b>：判歧义的键集合就是 {@link #RUNTIME_KEYS}（运行期真正读取的键），
 * {@code DeployConfigCheckService} 用同一组键判定"同键多条"。两边不会出现
 * "页面提示不完整、后台仍然执行"或反过来的情形。
 *
 * <p>非运行期键（其它 {@code deploy.*} / {@code rollback.*}）任何读取路径都不使用，
 * 出现多条不影响执行，因此不参与判歧义。
 */
@Service
@RequiredArgsConstructor
public class DeployConfigService {

    /**
     * 运行期真正会读取的部署键：发布执行、回滚执行、运行诊断、采集、制品上传共用。
     *
     * <p>{@code deploy.deploy.path} / {@code deploy.backup.path} / {@code deploy.package.path}
     * 由命令模板渲染读取（缺省为空串）。这份集合必须与 {@code DeployConfigCheckService}
     * 的检查键集合保持一致。
     */
    public static final Set<String> RUNTIME_KEYS = Set.of(
            "deploy.command",
            "rollback.command",
            "deploy.root",
            "deploy.port",
            "deploy.pidFile",
            "deploy.logFile",
            "deploy.healthUrl",
            "deploy.deploy.path",
            "deploy.backup.path",
            "deploy.package.path"
    );

    /** 歧义错误消息的固定前缀：采集链路据此归类为"配置歧义"而不是探测失败。 */
    public static final String AMBIGUITY_MESSAGE_PREFIX = "部署配置存在歧义";

    private final ConfigEnvConfigService configEnvConfigService;
    private final SecretValueResolver secretValueResolver;

    /**
     * 读取应用级部署配置。
     *
     * @throws BizException 任一运行期键在同一应用/环境下存在多条有效记录
     */
    public Map<String, String> loadDeployConfig(Long appId, Long envId) {
        List<ConfigEnvConfig> configs = configEnvConfigService.lambdaQuery()
                .eq(ConfigEnvConfig::getAppId, appId)
                .eq(ConfigEnvConfig::getEnvId, envId)
                .eq(ConfigEnvConfig::getIsDeleted, Boolean.FALSE)
                .and(wrapper -> wrapper.likeRight(ConfigEnvConfig::getConfigKey, "deploy.")
                        .or()
                        .likeRight(ConfigEnvConfig::getConfigKey, "rollback."))
                .list();

        requireNoAmbiguousRuntimeKey(appId, envId, configs);

        Map<String, String> result = new LinkedHashMap<>();
        for (ConfigEnvConfig config : configs) {
            String value = "SECRET_REF".equalsIgnoreCase(config.getValueType())
                    ? secretValueResolver.resolve(config.getSecretRefId())
                    : config.getConfigValue();
            result.put(config.getConfigKey(), value);
        }
        return result;
    }

    /**
     * 同一个运行期键出现多条有效记录时拒绝读取。
     *
     * <p>错误文案不含配置值：配置键、应用 ID、环境 ID 与条数足以定位，值可能是口令或完整命令。
     *
     * <p>{@code protected}：受限采集提供器覆盖了 {@link #loadDeployConfig}（它按自己的作用域
     * SQL 重写同一查询），必须能复用同一个判定，否则那条读取路径会退回"最后一条生效"。
     */
    protected static void requireNoAmbiguousRuntimeKey(Long appId, Long envId, List<ConfigEnvConfig> configs) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (ConfigEnvConfig config : configs) {
            if (!RUNTIME_KEYS.contains(config.getConfigKey())) {
                continue;
            }
            counts.merge(config.getConfigKey(), 1, Integer::sum);
        }
        List<String> ambiguousKeys = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > 1) {
                ambiguousKeys.add(entry.getKey() + "(" + entry.getValue() + " 条)");
            }
        }
        if (!ambiguousKeys.isEmpty()) {
            throw new BizException(ErrorCode.VALIDATION_ERROR,
                    AMBIGUITY_MESSAGE_PREFIX + "：应用(id=" + appId + ")、环境(id=" + envId
                            + ") 的运行期配置键 " + String.join("、", ambiguousKeys)
                            + " 存在多条有效记录，无法确定使用哪一条；请只保留一条有效配置后重试"
                            + "（本操作不会修改任何配置数据）");
        }
    }
}

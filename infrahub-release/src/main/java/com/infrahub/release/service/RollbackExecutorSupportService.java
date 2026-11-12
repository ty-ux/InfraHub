package com.infrahub.release.service;

import com.infrahub.common.error.ErrorCode;
import com.infrahub.common.exception.BizException;
import com.infrahub.release.entity.ReleaseExecutorConfig;
import com.infrahub.release.entity.ReleaseOrder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 回滚执行器支持判定：产品回滚当前只走既有 SSH 路径。
 *
 * <p>判定来源为原发布单的执行器类型：发布单声明了类型就按声明判定；未声明但带执行器配置 ID 时
 * 必须回查配置，配置或类型都查不到就拒绝；两者都缺失才是既有 SSH 行为。DOCKER、KUBERNETES
 * 以及其他未知类型一律不允许进入回滚路径，避免非 SSH 发布被按 SSH 命令误回滚。
 */
@Service
@RequiredArgsConstructor
public class RollbackExecutorSupportService {

    private static final String SSH = "SSH";

    private final ReleaseExecutorConfigService releaseExecutorConfigService;

    /**
     * 回滚支持判定结果。
     *
     * @param supported    true 表示可按既有 SSH 回滚路径执行
     * @param executorType 解析出的执行器类型；无法确定时为 null
     * @param message      supported=false 时的拒绝原因
     */
    public record RollbackSupport(boolean supported, String executorType, String message) {
    }

    /**
     * 判定原发布单的回滚支持类型，只读、不改任何状态。
     */
    public RollbackSupport resolve(ReleaseOrder releaseOrder) {
        if (releaseOrder == null) {
            // 发布单缺失由各入口既有校验拒绝；这里无法判定类型，按不支持返回。
            return new RollbackSupport(false, null, "发布单不存在，无法确定回滚执行器类型");
        }

        String declaredType = normalize(releaseOrder.getExecutorType());
        if (declaredType != null) {
            return fromType(declaredType, "发布单执行器类型");
        }

        if (releaseOrder.getExecutorConfigId() != null) {
            ReleaseExecutorConfig config = releaseExecutorConfigService.getById(releaseOrder.getExecutorConfigId());
            if (config == null) {
                return new RollbackSupport(false, null,
                        "无法确定回滚执行器类型：执行器配置不存在: " + releaseOrder.getExecutorConfigId());
            }
            String configType = normalize(config.getExecutorType());
            if (configType == null) {
                return new RollbackSupport(false, null,
                        "无法确定回滚执行器类型：执行器配置未设置执行器类型: " + config.getId());
            }
            return fromType(configType, "执行器配置声明的执行器类型");
        }

        // 类型与配置 ID 都缺失：既有 SSH 行为（按 rollback.command/deploy.command 直连 SSH）保持兼容。
        return new RollbackSupport(true, SSH, null);
    }

    /**
     * 回滚边界门禁：非 SSH 或类型无法确定的发布单一律拒绝。
     * 在创建、候选查询、预检、审批前执行与审批回调执行处调用。
     */
    public void assertRollbackSupported(ReleaseOrder releaseOrder) {
        RollbackSupport support = resolve(releaseOrder);
        if (!support.supported()) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, support.message());
        }
    }

    private RollbackSupport fromType(String type, String source) {
        if (SSH.equals(type)) {
            return new RollbackSupport(true, type, null);
        }
        return new RollbackSupport(false, type,
                "当前不支持该类型回滚：" + source + "为 " + type + "，回滚仅支持 SSH 路径");
    }

    private String normalize(String executorType) {
        return StringUtils.hasText(executorType) ? executorType.trim().toUpperCase() : null;
    }
}
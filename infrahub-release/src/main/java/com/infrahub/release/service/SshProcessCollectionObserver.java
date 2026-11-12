package com.infrahub.release.service;

import com.infrahub.common.exception.BizException;
import com.infrahub.infra.ssh.SshExecutionResult;
import com.infrahub.infra.ssh.SshExecutorService;
import com.infrahub.release.config.HostInstanceCollectionProperties;
import com.infrahub.release.dto.CollectionObservation;
import com.infrahub.release.dto.CollectionTargetRef;
import com.infrahub.release.dto.RuntimeServiceInstanceStatusResponse;
import com.infrahub.release.support.CollectionAdmissionChangedException;
import com.infrahub.release.support.CollectionErrorCategory;
import com.infrahub.release.support.CollectionResult;
import com.infrahub.release.support.CollectionTargetType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Set;

/**
 * 基于 SSH 的只读观测器。
 *
 * <p><b>核心安全规则</b>：探测命令没有产出结果标记 = **本次探测失败**，
 * 绝不能据此判定"进程不在"。既有 {@code RuntimeServiceDiagnosticsService.getStatus} 在 SSH 失败时
 * 会让 {@code running=false}、{@code healthStatus=DOWN}，若直接采信就会把连不上的主机报成 STOPPED ——
 * 这正是本功能要避免的错误。因此这里以"标记是否出现"作为观测有效性的唯一依据。
 *
 * <p>实例类型采用**失败关闭**：只对支持的进程和 Compose 实例类型探测；
 * Docker/K8s 以及任何未知类型一律判为不支持，绝不用 Spring Boot 的 PID 文件方式去猜。
 *
 * @see docs/release/v2-host-instance-status-collection-contract.md 第 2.3 / 4.1 节
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SshProcessCollectionObserver implements CollectionTargetObserver {

    public static final String OBSERVER_TYPE = "SSH_PROCESS";

    private static final String RUNNING_MARKER = "__INFRAHUB_RUNNING__=";
    private static final String REACHABLE_MARKER = "__INFRAHUB_HOST_REACHABLE__=";
    private static final String HOST_PROBE_COMMAND =
            "echo \"" + REACHABLE_MARKER + "true\"\nexit 0\n";

    /** 已知的进程型实例类型；其余（含 Docker/K8s/未知）一律不支持 */
    private static final Set<String> SUPPORTED_INSTANCE_TYPES = Set.of("SPRING_BOOT", "PROCESS", "DOCKER_COMPOSE");

    private static final int MAX_ERROR_SUMMARY = 200;

    private final CollectionDialResolver collectionDialResolver;
    private final SshExecutorService sshExecutorService;
    private final RuntimeServiceDiagnosticsService runtimeServiceDiagnosticsService;
    private final HostInstanceCollectionProperties properties;
    private final Clock collectionClock;

    @Override
    public String observerType() {
        return OBSERVER_TYPE;
    }

    @Override
    public boolean supports(CollectionTargetRef target) {
        if (CollectionTargetType.HOST.equals(target.targetType())) {
            return true;
        }
        if (!CollectionTargetType.SERVICE_INSTANCE.equals(target.targetType())) {
            return false;
        }
        String instanceType = target.instanceType();
        return instanceType != null && SUPPORTED_INSTANCE_TYPES.contains(instanceType.toUpperCase(Locale.ROOT));
    }

    @Override
    public CollectionObservation observe(CollectionTargetRef target) {
        OffsetDateTime observedAt = OffsetDateTime.now(collectionClock);
        if (!target.hasConfig()) {
            return CollectionObservation.unconfigured(OBSERVER_TYPE, observedAt);
        }
        try {
            return CollectionTargetType.HOST.equals(target.targetType())
                    ? observeHost(target, observedAt)
                    : observeInstance(target, observedAt);
        } catch (CollectionAdmissionChangedException exception) {
            // 采样之后、拨号之前目标身份或连接配置被改动：本轮**取消**。
            // 这与"没配 SSH"（未配置）、"连不上"（失败）都不同：既没有发出探测，
            // 也不能说明目标不可达，因此不产生 FAILED、不推进连续失败计数、不刷新成功观测时间。
            return CollectionObservation.admissionChanged(OBSERVER_TYPE, observedAt, summarize(exception));
        } catch (BizException exception) {
            // 到这一步时"目标/主机/SSH 配置"都已在编排层确认存在，
            // 剩下的 BizException 分成两类：**未配置**（SSH 配置没了 / 部署诊断参数不足）与**真失败**。
            // 未配置不是失败：判为跳过，绝不产生 FAILED，也绝不推进连续失败计数，
            // 更不能借失败阈值把目标状态/健康复位掉。
            CollectionErrorCategory category = classify(exception);
            String summary = summarize(exception);
            if (category == CollectionErrorCategory.CONFIG_MISSING) {
                return CollectionObservation.unconfigured(OBSERVER_TYPE, observedAt, summary);
            }
            if (category == CollectionErrorCategory.DIAGNOSTIC_CONFIG_MISSING) {
                return CollectionObservation.diagnosticsUnconfigured(OBSERVER_TYPE, observedAt, summary);
            }
            if (category == CollectionErrorCategory.DIAGNOSTIC_CONFIG_AMBIGUOUS) {
                return CollectionObservation.diagnosticsAmbiguous(OBSERVER_TYPE, observedAt, summary);
            }
            return CollectionObservation.failure(category, summary, OBSERVER_TYPE, observedAt);
        } catch (RuntimeException exception) {
            return CollectionObservation.failure(CollectionErrorCategory.PROBE_ERROR,
                    summarize(exception), OBSERVER_TYPE, observedAt);
        }
    }

    /**
     * 主机拨号：请求**全部由快照构造**（地址、端口取自 {@code target}，认证材料取自快照里那条配置），
     * 快照与当前库状态有任何差异时 {@link CollectionDialResolver} 抛异常，本轮在拨号前取消。
     */
    private CollectionObservation observeHost(CollectionTargetRef target, OffsetDateTime observedAt) {
        SshExecutionResult result = sshExecutorService.execute(collectionDialResolver.buildRequest(
                target, HOST_PROBE_COMMAND, properties.getSingleTargetTimeoutMs()));

        String stdout = result.getStdout() == null ? "" : result.getStdout();
        if (!stdout.contains(REACHABLE_MARKER)) {
            // 命令没有产出标记 = 探测无效，不得判定为"主机离线"
            return CollectionObservation.failure(classifyResult(result), resultSummary(result),
                    OBSERVER_TYPE, observedAt);
        }
        return new CollectionObservation(CollectionResult.SUCCESS, null, null,
                "ONLINE", null, observedAt, OBSERVER_TYPE, true);
    }

    /**
     * 实例拨号：走**采集专用**的诊断入口，它按快照校验实例身份、按快照解析诊断参数、
     * 并按快照拨号；不使用只接受 {@code instanceId} 的手工诊断接口 —— 那条路径会自由重读，
     * 可能把本次拨号改签到另一个主机或另一份服务/环境配置上。
     */
    private CollectionObservation observeInstance(CollectionTargetRef target, OffsetDateTime observedAt) {
        RuntimeServiceInstanceStatusResponse response = runtimeServiceDiagnosticsService
                .getStatusForCollection(target, properties.getSingleTargetTimeoutMs());
        String stdout = response.getStdout() == null ? "" : response.getStdout();
        if ("UNKNOWN".equals(response.getInstanceStatus()) || !stdout.contains(RUNNING_MARKER)) {
            // 没有 __INFRAHUB_RUNNING__ 标记 = 命令没跑成，属于探测失败，
            // 绝不能把 running=false 当成"进程不在"。
            return CollectionObservation.failure(CollectionErrorCategory.PROBE_ERROR,
                    "探测命令未返回运行状态标记，本次观测无效", OBSERVER_TYPE, observedAt);
        }
        boolean running = response.isRunning();
        String health = StringUtils.hasText(response.getHealthStatus()) ? response.getHealthStatus() : "UNKNOWN";
        if (!running) {
            // 进程确实不在：这是成功观测，结论 STOPPED，不是失败
            return CollectionObservation.processAbsent(OBSERVER_TYPE, observedAt);
        }
        CollectionErrorCategory category = "UP".equals(health) ? null : CollectionErrorCategory.HEALTH_ENDPOINT_ERROR;
        return new CollectionObservation(CollectionResult.SUCCESS, category, null,
                "RUNNING", health, observedAt, OBSERVER_TYPE, true);
    }

    private static CollectionErrorCategory classifyResult(SshExecutionResult result) {
        String stderr = result.getStderr() == null ? "" : result.getStderr();
        if (stderr.toLowerCase(Locale.ROOT).contains("timed out")) {
            return CollectionErrorCategory.NETWORK_TIMEOUT;
        }
        return CollectionErrorCategory.PROBE_ERROR;
    }

    private static String resultSummary(SshExecutionResult result) {
        String stderr = result.getStderr() == null ? "" : result.getStderr();
        return truncate("SSH 探测未返回结果标记" + (stderr.isBlank() ? "" : "：" + firstLine(stderr)));
    }

    /**
     * 从底层 SSH 客户端的异常文案归类。这是**尽力而为**的推断：底层没有结构化的错误类型，
     * 只能匹配其消息文本。无论归类是否精确，都不影响"失败绝不产生 STOPPED/OFFLINE"这一保证。
     */
    static CollectionErrorCategory classify(Throwable throwable) {
        String message = throwable.getMessage() == null ? "" : throwable.getMessage().toLowerCase(Locale.ROOT);
        // 先判"未配置"的两类：诊断服务在 SSH 配置缺失、或部署诊断参数不足时也是抛 BizException，
        // 若不先拦住会被后面的通用规则误判成探测故障。
        // 两类必须分开，它们的处置建议不同，且只有一个能如实说"目标没有连接配置"：
        //   - 未配置 ssh 连接信息 → 主机上没有可用 SSH 配置，configPresent=false；
        //   - 未配置部署诊断参数   → SSH 配置就在那儿，只是运行时参数没配齐，configPresent 仍为 true。
        if (message.contains("未配置 ssh 连接信息")) {
            return CollectionErrorCategory.CONFIG_MISSING;
        }
        if (message.contains("未配置部署诊断参数")) {
            return CollectionErrorCategory.DIAGNOSTIC_CONFIG_MISSING;
        }
        // 部署配置歧义：配置存在但无法确定取值。必须与"真失败"分开，
        // 否则单个目标的配置问题会被累计成运行故障并推进失败计数。
        if (message.contains(DeployConfigService.AMBIGUITY_MESSAGE_PREFIX.toLowerCase(Locale.ROOT))) {
            return CollectionErrorCategory.DIAGNOSTIC_CONFIG_AMBIGUOUS;
        }
        if (message.contains("auth fail") || message.contains("auth cancel")
                || message.contains("authentication") || message.contains("invalid password")
                || message.contains("私钥") || message.contains("密码")) {
            return CollectionErrorCategory.AUTH_FAILED;
        }
        if (message.contains("timed out") || message.contains("timeout")) {
            return CollectionErrorCategory.NETWORK_TIMEOUT;
        }
        if (message.contains("connection refused") || message.contains("no route to host")
                || message.contains("unknownhost") || message.contains("unreachable")
                || message.contains("connectexception")) {
            return CollectionErrorCategory.TARGET_UNREACHABLE;
        }
        return CollectionErrorCategory.PROBE_ERROR;
    }

    /** 脱敏 + 截断。绝不写入密码 / 私钥 / passphrase / Secret 值，也不整段写入远端响应。 */
    static String summarize(Throwable throwable) {
        return truncate(firstLine(throwable.getMessage() == null ? throwable.getClass().getSimpleName()
                : throwable.getMessage()));
    }

    private static String firstLine(String value) {
        String text = value == null ? "" : value.strip();
        int newline = text.indexOf('\n');
        return newline >= 0 ? text.substring(0, newline) : text;
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= MAX_ERROR_SUMMARY ? value : value.substring(0, MAX_ERROR_SUMMARY);
    }
}

package com.infrahub.release.service;

import com.infrahub.common.error.ErrorCode;
import com.infrahub.common.exception.BizException;
import com.infrahub.release.dto.RuntimeInstanceStopResponse;
import com.infrahub.release.entity.RuntimeServiceInstance;
import com.infrahub.release.support.RuntimeInstanceStartIdentity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 单实例停止：**当前一律拒绝，不发送任何信号**。
 *
 * <p>本类只做一件事：在产生任何外部动作之前，按固定顺序评估"能否证明目标进程就是该实例"，
 * 并把**第一个不满足的条件**作为结论返回。所有分支都是失败关闭的；没有任何分支会放宽拒绝。
 *
 * <p><b>评估顺序与结论</b>
 * <ol>
 *   <li>登记缺少目标主机或服务/环境 → {@code REFUSED}（无法定位停止对象）；</li>
 *   <li>实例类型缺失或不在可停止类型集合 → {@code UNSUPPORTED}；</li>
 *   <li>没有生效的启动身份 → {@code IDENTITY_UNCONFIRMED}（当前唯一可达的身份结论）；</li>
 *   <li>身份字段不完整 → {@code REFUSED}；</li>
 *   <li>身份不属于该实例/该主机 → {@code REFUSED}；</li>
 *   <li>身份完整且匹配，但平台侧仍无法排除"目标就是平台自身"，且没有可在目标机本地复核的
 *       独立执行者 → {@code REFUSED}（自停不可排除）。</li>
 * </ol>
 *
 * <p><b>为什么第 6 步仍然拒绝</b>：仅凭主机名、DNS 解析、网卡地址或是否 loopback 都不能否定"这是自身"——
 * 容器网络、NAT、别名与解析失败都会给出错误答案。因此"无法确认"必须按"可能是自身"处理。
 * 真正的放行只能发生在目标机本地：执行者用自己的 cgroup/unit 归属与 pidfd 证明目标不是它自己和平台，
 * 而本仓库还没有这个执行者。
 *
 * <p><b>为什么类型判定在前</b>：容器编排类实例（DOCKER/KUBERNETES 等）需要容器 ID / Pod UID 一类的
 * 运行时身份，本设计不覆盖；此时给出 {@code UNSUPPORTED} 比"身份缺失"更准确，不会误导使用者去补进程身份。
 *
 * <p><b>没有远端输出</b>：本实现不建立 SSH 连接、不执行命令、不读写目标机，因此不存在把远端
 * stdout/stderr、异常原文或命令文本带进响应、日志或审计的路径。响应只含实例身份、结论、原因码与固定文案。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RuntimeInstanceStopService {

    /** 身份无法确认：没有生效的启动身份，未发送任何信号。 */
    static final String OUTCOME_IDENTITY_UNCONFIRMED = "IDENTITY_UNCONFIRMED";
    /** 类型不支持：本设计不覆盖该实例类型的停止，未发送任何信号。 */
    static final String OUTCOME_UNSUPPORTED = "UNSUPPORTED";
    /** 前置条件拒绝：登记不完整、身份不可信或无法排除自停，未发送任何信号。 */
    static final String OUTCOME_REFUSED = "REFUSED";

    /**
     * 可进入身份校验的实例类型：与采集侧 {@code SshProcessCollectionObserver} 支持的进程型实例保持一致。
     * 容器编排类实例需要运行时身份（容器 ID / Pod UID），不在本设计范围内。
     */
    static final Set<String> STOP_CAPABLE_INSTANCE_TYPES = Set.of("PROCESS", "SPRING_BOOT");

    static final String REASON_REGISTRATION_INCOMPLETE = "REGISTRATION_INCOMPLETE";
    static final String REASON_TARGET_TYPE_UNKNOWN = "TARGET_TYPE_UNKNOWN";
    static final String REASON_TARGET_TYPE_UNSUPPORTED = "TARGET_TYPE_UNSUPPORTED";
    static final String REASON_IDENTITY_NOT_REGISTERED = "IDENTITY_NOT_REGISTERED";
    static final String REASON_IDENTITY_UNAVAILABLE = "IDENTITY_UNAVAILABLE";
    static final String REASON_IDENTITY_INCOMPLETE = "IDENTITY_INCOMPLETE";
    static final String REASON_IDENTITY_MISMATCH = "IDENTITY_MISMATCH";
    static final String REASON_SELF_STOP_UNVERIFIABLE = "SELF_STOP_UNVERIFIABLE";

    private static final String MESSAGE_REGISTRATION_INCOMPLETE =
            "运行实例登记缺少目标主机或服务/环境关联，无法定位停止对象；未发送任何停止信号。";
    private static final String MESSAGE_TARGET_TYPE_UNKNOWN =
            "运行实例未登记实例类型，无法判断停止方式；未发送任何停止信号。";
    private static final String MESSAGE_TARGET_TYPE_UNSUPPORTED =
            "该实例类型不由平台执行停止（当前只设计了 SSH 进程型实例的执行者）；未发送任何停止信号。";
    private static final String MESSAGE_IDENTITY_NOT_REGISTERED =
            "未发送任何停止信号：平台当前无法证明目标进程就是该实例。"
                    + "实例登记中没有启动代次、进程起始时间或制品身份，部署参数(PID 文件、端口)按服务+环境共享，"
                    + "命令行匹配也不能排除同主机的其他进程。"
                    + "要真正支持停止，需要先补齐按实例记录的启动身份(启动时生成并由平台登记)，"
                    + "并由平台之外的独立执行者在目标机本地复核后用内核级句柄发送信号。";
    private static final String MESSAGE_IDENTITY_UNAVAILABLE =
            "读取启动身份时发生错误，无法证明目标进程就是该实例；未发送任何停止信号。";
    private static final String MESSAGE_IDENTITY_INCOMPLETE =
            "平台登记的启动身份字段不完整，无法证明目标进程就是该实例；未发送任何停止信号。";
    private static final String MESSAGE_IDENTITY_MISMATCH =
            "平台登记的启动身份不属于该实例或该主机，已拒绝；未发送任何停止信号。";
    private static final String MESSAGE_SELF_STOP_UNVERIFIABLE =
            "平台无法排除目标就是平台自身（地址解析、容器网络或别名都不能作为否定证据），"
                    + "且当前没有可在目标机本地完成校验的独立执行者；未发送任何停止信号。";

    private final RuntimeServiceInstanceService runtimeServiceInstanceService;
    private final RuntimeInstanceStartIdentitySource identitySource;
    private final RuntimeInstanceAuditLogService auditLogService;

    public RuntimeInstanceStopResponse stop(Long instanceId) {
        RuntimeServiceInstance instance = runtimeServiceInstanceService.getById(instanceId);
        if (instance == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR,
                    "运行实例不存在(id=" + instanceId + ")，请确认登记是否已被移除");
        }
        Assessment assessment = assess(instance);
        RuntimeInstanceStopResponse response = buildResponse(instance, assessment);
        auditStop(instance, response, assessment);
        return response;
    }

    /**
     * 失败关闭的身份评估。任何判断都只依据库中登记与身份读取结果，不连接目标主机。
     */
    private Assessment assess(RuntimeServiceInstance instance) {
        if (instance.getHostId() == null || instance.getServiceId() == null || instance.getEnvId() == null) {
            return Assessment.refused(REASON_REGISTRATION_INCOMPLETE, MESSAGE_REGISTRATION_INCOMPLETE, false);
        }
        String instanceType = normalize(instance.getInstanceType());
        if (instanceType.isEmpty()) {
            return Assessment.unsupported(REASON_TARGET_TYPE_UNKNOWN, MESSAGE_TARGET_TYPE_UNKNOWN);
        }
        if (!STOP_CAPABLE_INSTANCE_TYPES.contains(instanceType)) {
            return Assessment.unsupported(REASON_TARGET_TYPE_UNSUPPORTED, MESSAGE_TARGET_TYPE_UNSUPPORTED);
        }

        Optional<RuntimeInstanceStartIdentity> found;
        try {
            found = identitySource.findActive(instance.getId());
        } catch (RuntimeException exception) {
            // 读取失败时不能把"未知"当成"无进程"或"可停止"；结论固定为身份无法确认
            log.error("读取运行实例({})启动身份失败", instance.getId());
            return Assessment.identityUnconfirmed(REASON_IDENTITY_UNAVAILABLE, MESSAGE_IDENTITY_UNAVAILABLE);
        }
        if (found == null || found.isEmpty()) {
            return Assessment.identityUnconfirmed(REASON_IDENTITY_NOT_REGISTERED, MESSAGE_IDENTITY_NOT_REGISTERED);
        }

        RuntimeInstanceStartIdentity identity = found.get();
        if (!identity.isComplete()) {
            return Assessment.refused(REASON_IDENTITY_INCOMPLETE, MESSAGE_IDENTITY_INCOMPLETE, true);
        }
        if (!identity.belongsTo(instance.getId(), instance.getHostId())) {
            return Assessment.refused(REASON_IDENTITY_MISMATCH, MESSAGE_IDENTITY_MISMATCH, true);
        }
        // 身份完整且绑定正确，也只能交给目标机本地的独立执行者；本仓库没有该执行者，
        // 平台侧也无法排除自停，因此仍然拒绝。
        return Assessment.refused(REASON_SELF_STOP_UNVERIFIABLE, MESSAGE_SELF_STOP_UNVERIFIABLE, true);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private RuntimeInstanceStopResponse buildResponse(RuntimeServiceInstance instance, Assessment assessment) {
        RuntimeInstanceStopResponse response = new RuntimeInstanceStopResponse();
        response.setInstanceId(instance.getId());
        response.setInstanceName(instance.getInstanceName());
        response.setServiceId(instance.getServiceId());
        response.setEnvId(instance.getEnvId());
        response.setHostId(instance.getHostId());
        response.setInstanceType(instance.getInstanceType());
        response.setOutcome(assessment.outcome());
        response.setReasonCode(assessment.reasonCode());
        response.setMessage(assessment.message());
        response.setCheckedAt(OffsetDateTime.now());
        return response;
    }

    private void auditStop(RuntimeServiceInstance instance, RuntimeInstanceStopResponse response,
                           Assessment assessment) {
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("instanceStatus", instance.getInstanceStatus());
        before.put("healthStatus", instance.getHealthStatus());
        before.put("hostId", instance.getHostId());
        before.put("serviceId", instance.getServiceId());
        before.put("envId", instance.getEnvId());
        before.put("instanceType", instance.getInstanceType());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("outcome", response.getOutcome());
        after.put("reasonCode", response.getReasonCode());
        after.put("identityPresent", assessment.identityPresent());
        after.put("signalSent", false);
        after.put("executorAttempted", false);
        try {
            auditLogService.record(instance.getId(),
                    RuntimeInstanceAuditLogService.ACTION_STOP_INSTANCE,
                    response.getOutcome(), before, after);
        } catch (RuntimeException exception) {
            // 结论不因审计失败而改变：这里没有任何进程动作，失败只影响留痕
            log.error("运行实例({})停止请求的审计写入失败", instance.getId());
            response.setMessage(response.getMessage() + "（审计记录写入失败，请人工补记）");
        }
    }

    /** 一次评估的固定结论；不携带任何配置值、命令或远端输出。 */
    private record Assessment(String outcome, String reasonCode, String message, boolean identityPresent) {

        static Assessment refused(String reasonCode, String message, boolean identityPresent) {
            return new Assessment(OUTCOME_REFUSED, reasonCode, message, identityPresent);
        }

        static Assessment unsupported(String reasonCode, String message) {
            return new Assessment(OUTCOME_UNSUPPORTED, reasonCode, message, false);
        }

        static Assessment identityUnconfirmed(String reasonCode, String message) {
            return new Assessment(OUTCOME_IDENTITY_UNCONFIRMED, reasonCode, message, false);
        }
    }
}

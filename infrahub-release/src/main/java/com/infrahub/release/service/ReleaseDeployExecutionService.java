package com.infrahub.release.service;

import com.infrahub.base.entity.BaseEnv;
import com.infrahub.base.entity.BaseServiceEntity;
import com.infrahub.common.error.ErrorCode;
import com.infrahub.common.exception.BizException;
import com.infrahub.infra.ssh.SshExecutionRequest;
import com.infrahub.infra.ssh.SshExecutionResult;
import com.infrahub.infra.ssh.SshExecutorService;
import com.infrahub.release.entity.AssetHost;
import com.infrahub.release.entity.AssetHostSshConfig;
import com.infrahub.release.entity.ReleaseArtifact;
import com.infrahub.release.entity.ReleaseExecutorConfig;
import com.infrahub.release.entity.ReleaseOrder;
import com.infrahub.release.entity.ReleaseVersion;
import com.infrahub.release.entity.RuntimeDeployRecord;
import com.infrahub.release.entity.RuntimeExecutionUnknown;
import com.infrahub.release.entity.RuntimeServiceInstance;
import com.infrahub.release.service.ReleaseDeployExecutionResult.InstanceOutcome;
import com.infrahub.release.service.executor.ReleaseExecutionContext;
import com.infrahub.release.service.executor.ReleaseExecutionResult;
import com.infrahub.release.service.executor.ReleaseExecutor;
import com.infrahub.release.service.executor.ReleaseExecutorRegistry;
import com.infrahub.release.support.DeployExecutionOutcome;
import com.infrahub.release.support.DeployExecutionUnknownException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 部署执行：逐实例调用 SSH / 执行器，并给出**可证明**的结果类别。
 *
 * <p>证据口径：
 * <ul>
 *   <li>预检失败：部署命令没有发出 → {@link DeployExecutionOutcome#NOT_STARTED}；</li>
 *   <li>SSH 退出码 0 → 成功；拿到非零退出码 → 确定失败；超时、断连、没有退出状态 →
 *       {@link DeployExecutionOutcome#UNKNOWN}；</li>
 *   <li>执行器显式给出的未知结果原样保留，不从文本里猜；</li>
 *   <li>整批按实例结果聚合：未知优先，其次部分成功、全部成功、未开始、失败。</li>
 * </ul>
 *
 * <p>返回给调用方的 {@code message} 只包含固定类别、计数与实例 ID；stdout/stderr、命令与异常细节
 * 只写入部署记录的 deployLog（既有详细证据边界），不进入面向用户的摘要。
 */
@Service
@RequiredArgsConstructor
public class ReleaseDeployExecutionService {

    private static final String RECORD_SUCCESS = "SUCCESS";
    private static final String RECORD_FAILED = "FAILED";
    private static final String RECORD_UNKNOWN = "UNKNOWN";

    private static final String CODE_PRECHECK_FAILED = "PRECHECK_FAILED";
    private static final String CODE_COMMAND_FAILED = "COMMAND_FAILED";
    private static final String CODE_COMMAND_UNKNOWN = "COMMAND_RESULT_UNKNOWN";
    private static final String CODE_EXECUTOR_FAILED = "EXECUTOR_FAILED";
    private static final String CODE_EXECUTOR_UNKNOWN = "EXECUTOR_RESULT_UNKNOWN";
    private static final String CODE_EXECUTOR_NOT_STARTED = "EXECUTOR_NOT_STARTED";

    private final RuntimeDeployRecordService runtimeDeployRecordService;
    private final AssetHostSshConfigService assetHostSshConfigService;
    private final AssetHostService assetHostService;
    private final SecretValueResolver secretValueResolver;
    private final SshExecutorService sshExecutorService;
    private final ReleasePrecheckService releasePrecheckService;
    private final ReleaseExecutorRegistry releaseExecutorRegistry;
    private final ReleaseExecutorConfigService releaseExecutorConfigService;
    private final ResourceGovernanceRecordService resourceGovernanceRecordService;
    private final ReleaseExecutorConfigParser releaseExecutorConfigParser;

    /**
     * 整单发布执行，**没有**执行认领的调用方走这里（凭证是订单的条件更新）。
     * 它写出的部署记录不携带认领归属。
     */
    public ReleaseDeployExecutionResult executeReleaseDeploy(
            ReleaseOrder order,
            BaseServiceEntity service,
            BaseEnv env,
            ReleaseVersion version,
            ReleaseArtifact artifact,
            List<RuntimeServiceInstance> instances,
            Map<String, String> deployConfig
    ) {
        return execute(order, service, env, version, artifact, instances, deployConfig, 1, false, null);
    }

    /**
     * 整单发布执行，且这次执行持有一行持久认领。
     *
     * <p>{@code executionClaimId} 沿调用链一路传到记录创建处，让**每条新记录**在插入的同一个短事务里
     * 就带上产生它的认领。这样“这次执行产生了哪些部署记录”有唯一答案，不需要、也不允许事后按发布单
     * 批量补写（同一发布单下可能有历史记录、回滚记录，或有别的执行者写下的记录）。
     *
     * @param executionClaimId 本次执行的一次性认领 ID；为 {@code null} 表示这次执行没有认领，
     *                         记录不带归属（合法，不是错误）
     */
    public ReleaseDeployExecutionResult executeReleaseDeploy(
            ReleaseOrder order,
            BaseServiceEntity service,
            BaseEnv env,
            ReleaseVersion version,
            ReleaseArtifact artifact,
            List<RuntimeServiceInstance> instances,
            Map<String, String> deployConfig,
            Long executionClaimId
    ) {
        return execute(order, service, env, version, artifact, instances, deployConfig, 1, false,
                executionClaimId);
    }

    public ReleaseDeployExecutionResult executeReleaseDeployForBatch(
            ReleaseOrder order,
            BaseServiceEntity service,
            BaseEnv env,
            ReleaseVersion version,
            ReleaseArtifact artifact,
            List<RuntimeServiceInstance> instances,
            Map<String, String> deployConfig,
            int deployBatch
    ) {
        // 灰度批次的凭证是批次行的 PENDING → RUNNING 条件更新，不是持久认领，因此不带认领归属。
        return execute(order, service, env, version, artifact, instances, deployConfig, deployBatch, true, null);
    }

    private ReleaseDeployExecutionResult execute(
            ReleaseOrder order,
            BaseServiceEntity service,
            BaseEnv env,
            ReleaseVersion version,
            ReleaseArtifact artifact,
            List<RuntimeServiceInstance> instances,
            Map<String, String> deployConfig,
            int deployBatch,
            boolean batch,
            Long executionClaimId
    ) {
        if (order.getExecutorConfigId() != null) {
            ReleaseExecutorConfig executorConfig = releaseExecutorConfigService.findEnabledById(order.getExecutorConfigId());
            if (executorConfig == null) {
                throw new BizException(ErrorCode.VALIDATION_ERROR,
                        "执行器配置不存在或未启用: " + order.getExecutorConfigId());
            }
            if (!executorConfig.getEnvId().equals(order.getEnvId())) {
                throw new BizException(ErrorCode.VALIDATION_ERROR, "执行器配置不属于发布目标环境");
            }
            return executeWithExecutor(order, service, env, version, artifact, instances, deployConfig,
                    deployBatch, batch, executorConfig, executionClaimId);
        }

        String commandTemplate = deployConfig.get("deploy.command");
        if (!StringUtils.hasText(commandTemplate)) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "缺少 deploy.command 部署命令配置");
        }

        List<Long> successInstanceIds = new ArrayList<>();
        List<InstanceOutcome> outcomes = new ArrayList<>();
        int failed = 0;
        int unknown = 0;
        int notStarted = 0;
        String firstFailureCode = null;
        boolean anyStarted = false;

        // 先把所有实例的主机登记与 SSH 配置校验完：任何一项缺失都属于“确定未开始”。
        // 放在循环之前是为了避免“前几个实例已执行、到后面才发现配置缺失”这种混合状态——
        // 那种状态既不是未开始，也不该被粗略报成整批结果未知。
        Map<Long, AssetHost> hostById = new LinkedHashMap<>();
        Map<Long, AssetHostSshConfig> sshConfigById = new LinkedHashMap<>();
        for (RuntimeServiceInstance instance : instances) {
            if (instance.getHostId() == null) {
                throw new BizException(ErrorCode.VALIDATION_ERROR, "存在未绑定主机的服务实例，无法执行发布");
            }
            AssetHost host = getRequiredHost(instance.getHostId());
            AssetHostSshConfig sshConfig = assetHostSshConfigService.getEnabledByHostId(instance.getHostId());
            if (sshConfig == null) {
                throw new BizException(ErrorCode.VALIDATION_ERROR, "目标主机缺少启用中的 SSH 配置");
            }
            hostById.put(instance.getId(), host);
            sshConfigById.put(instance.getId(), sshConfig);
        }

        try {
            for (RuntimeServiceInstance instance : instances) {
                AssetHost host = hostById.get(instance.getId());
                AssetHostSshConfig sshConfig = sshConfigById.get(instance.getId());

                RuntimeDeployRecord record = buildRunningRecord(order, instance, version.getId(), deployBatch,
                        batch, executionClaimId);
                // RUNNING 记录以独立短事务先提交：外部执行器被调用之前，部署记录已经对外可查
                runtimeDeployRecordService.startRunning(record);
                anyStarted = true;

                ReleasePrecheckResult precheckResult = releasePrecheckService.check(
                        host,
                        sshConfig,
                        DeployRuntimeConfig.from(deployConfig),
                        artifact.getArtifactUri()
                );
                if (!precheckResult.isSuccess()) {
                    // 预检失败：部署命令没有发出，确定属于“未开始”
                    finishRecord(record, DeployExecutionOutcome.NOT_STARTED,
                            precheckResult.executionResult().getStartedAt(),
                            precheckResult.executionResult().getEndedAt(),
                            precheckResult.fullLog() + "\n\n前置检查失败");
                    outcomes.add(new InstanceOutcome(instance.getId(), DeployExecutionOutcome.NOT_STARTED));
                    notStarted++;
                    if (firstFailureCode == null) {
                        firstFailureCode = CODE_PRECHECK_FAILED;
                    }
                    continue;
                }

                SshExecutionResult executionResult;
                try {
                    executionResult = sshExecutorService.execute(
                            SshExecutionRequest.builder()
                                    .host(StringUtils.hasText(host.getIp()) ? host.getIp() : host.getHostname())
                                    .port(sshConfig.getSshPort())
                                    .username(sshConfig.getUsername())
                                    .authType(sshConfig.getAuthType())
                                    .password(secretValueResolver.resolve(sshConfig.getPasswordSecretRefId()))
                                    .privateKeyPath(secretValueResolver.resolve(sshConfig.getPrivateKeySecretRefId()))
                                    .passphrase(secretValueResolver.resolve(sshConfig.getPassphraseSecretRefId()))
                                    .command(renderCommand(commandTemplate, service, env, version, artifact, instance, deployConfig))
                                    .build()
                    );
                } catch (RuntimeException sshFailure) {
                    // 命令可能已经发出（也可能在连接阶段失败），两者无法在文本之外区分：一律按结果未知处理。
                    // 该实例的部署记录直接落 UNKNOWN，循环随后中止，不再继续执行后续实例。
                    finishRecord(record, DeployExecutionOutcome.UNKNOWN, null, null,
                            "执行中断，结果未知；详见服务端日志");
                    throw new DeployExecutionUnknownException(
                            "实例 " + instance.getId() + " 的 SSH 执行中断，结果未知", sshFailure);
                }

                DeployExecutionOutcome instanceOutcome = sshOutcome(executionResult);
                finishRecord(record, instanceOutcome, executionResult.getStartedAt(), executionResult.getEndedAt(),
                        precheckResult.fullLog() + "\n\n" + executionResult.fullLog());

                if (instanceOutcome == DeployExecutionOutcome.SUCCEEDED) {
                    successInstanceIds.add(instance.getId());
                    recordGovernanceSuccess(order, host, instance, version, deployBatch, batch);
                } else {
                    if (instanceOutcome == DeployExecutionOutcome.FAILED) {
                        failed++;
                        if (firstFailureCode == null) {
                            firstFailureCode = CODE_COMMAND_FAILED;
                        }
                    } else {
                        unknown++;
                        if (firstFailureCode == null) {
                            firstFailureCode = CODE_COMMAND_UNKNOWN;
                        }
                    }
                    recordGovernanceFailure(order, host, instance, version, record, instanceOutcome, batch);
                }
                outcomes.add(new InstanceOutcome(instance.getId(), instanceOutcome));
                if (instanceOutcome == DeployExecutionOutcome.UNKNOWN) {
                    for (int i = outcomes.size(); i < instances.size(); i++) {
                        outcomes.add(new InstanceOutcome(instances.get(i).getId(), DeployExecutionOutcome.NOT_STARTED));
                        notStarted++;
                    }
                    break;
                }
            }
        } catch (DeployExecutionUnknownException unknownFailure) {
            throw unknownFailure;
        } catch (RuntimeException loopFailure) {
            if (anyStarted) {
                // 已经有实例开始执行过：后续校验/写入失败时，整批不能报成“未开始”或“确定失败”
                throw new DeployExecutionUnknownException(
                        "批次执行中断，已执行实例见部署记录，整体结果未知", loopFailure);
            }
            throw loopFailure;
        }

        return summarize(batch, deployBatch, instances.size(), successInstanceIds, failed, unknown, notStarted,
                firstFailureCode, outcomes, targetLabel(batch, deployBatch));
    }

    // ------------------------------------------------------------------
    // 结果分类与落库
    // ------------------------------------------------------------------

    private static DeployExecutionOutcome sshOutcome(SshExecutionResult result) {
        return switch (result.resolvedEvidence()) {
            case SUCCEEDED -> DeployExecutionOutcome.SUCCEEDED;
            case FAILED -> DeployExecutionOutcome.FAILED;
            case UNKNOWN -> DeployExecutionOutcome.UNKNOWN;
        };
    }

    /**
     * 单实例终态：部署记录状态与结果类别一一对应。UNKNOWN 必须落成 UNKNOWN，
     * 不能写成 FAILED——否则操作者会把“可能仍在运行”读成“确定失败、可以安全回滚”。
     */
    private void finishRecord(RuntimeDeployRecord record, DeployExecutionOutcome outcome,
                              OffsetDateTime startedAt, OffsetDateTime endedAt, String log) {
        record.setDeployStatus(recordStatusOf(outcome));
        record.setDeployLog(log);
        if (startedAt != null) {
            record.setStartedAt(startedAt);
        }
        if (endedAt != null) {
            record.setEndedAt(endedAt);
        } else if (record.getEndedAt() == null) {
            record.setEndedAt(OffsetDateTime.now());
        }
        if (RECORD_UNKNOWN.equals(record.getDeployStatus())) {
            // UNKNOWN 与“未解除的未知事实”必须同一事务提交，否则两者之间存在可穿越的阻断空窗。
            runtimeDeployRecordService.finishUnknown(record,
                    RuntimeExecutionUnknown.DETECTION_EVIDENCE,
                    "部署命令结果无法证明：" + outcome.name());
        } else {
            runtimeDeployRecordService.finish(record);
        }
    }

    private static String recordStatusOf(DeployExecutionOutcome outcome) {
        return switch (outcome) {
            case SUCCEEDED -> RECORD_SUCCESS;
            case UNKNOWN, PARTIAL -> RECORD_UNKNOWN;
            case FAILED, NOT_STARTED -> RECORD_FAILED;
        };
    }

    private ReleaseDeployExecutionResult summarize(boolean batch, int deployBatch, int attempted,
                                                   List<Long> successInstanceIds, int failed, int unknown,
                                                   int notStarted, String failureCode,
                                                   List<InstanceOutcome> outcomes, String label) {
        int succeeded = successInstanceIds.size();
        DeployExecutionOutcome overall = DeployExecutionOutcome.aggregate(attempted, succeeded, failed, unknown, notStarted);
        boolean success = overall == DeployExecutionOutcome.SUCCEEDED;
        String message = success
                ? label + "部署执行成功，成功实例数=" + succeeded
                : label + "部署未确认成功：" + countSummary(succeeded, failed, unknown, notStarted, attempted)
                + "；详见部署记录";
        return new ReleaseDeployExecutionResult(success, successInstanceIds, message, overall,
                attempted, succeeded, failed, unknown, notStarted, failureCode, outcomes);
    }

    private static String countSummary(int succeeded, int failed, int unknown, int notStarted, int attempted) {
        return "成功 " + succeeded + " 个、确定失败 " + failed + " 个、结果未知 " + unknown
                + " 个、未开始 " + notStarted + " 个（共 " + attempted + " 个）";
    }

    private static String targetLabel(boolean batch, int deployBatch) {
        return batch ? "批次" + deployBatch : "发布单";
    }

    private void recordGovernanceSuccess(ReleaseOrder order, AssetHost host, RuntimeServiceInstance instance,
                                         ReleaseVersion version, int deployBatch, boolean batch) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("versionId", version.getId());
        extra.put("versionNo", version.getVersionNo());
        if (batch) {
            extra.put("batchNo", deployBatch);
        }
        resourceGovernanceRecordService.record(
                batch ? GovernanceEventType.CANARY_BATCH_RESOURCE_ALLOCATED : GovernanceEventType.RELEASE_RESOURCE_ALLOCATED,
                host.getResourcePoolId(),
                host.getId(),
                instance.getId(),
                order.getId(),
                null,
                order.getExecutorType() != null ? order.getExecutorType() : "SSH",
                order.getEnvId(),
                order.getServiceId(),
                version.getId(),
                "RUNTIME_INSTANCE",
                instance.getId(),
                null,
                extra,
                "SUCCESS",
                null,
                order.getCreatedBy()
        );
    }

    private void recordGovernanceFailure(ReleaseOrder order, AssetHost host, RuntimeServiceInstance instance,
                                         ReleaseVersion version, RuntimeDeployRecord record,
                                         DeployExecutionOutcome outcome, boolean batch) {
        // 治理记录里只放固定类别与部署记录 ID；远端输出留在 deployLog，不复制到这里
        String detail = "部署结果 " + outcome.code() + "，详见部署记录 id=" + record.getId();
        resourceGovernanceRecordService.record(
                batch ? GovernanceEventType.CANARY_BATCH_RESOURCE_FAILED : GovernanceEventType.RELEASE_RESOURCE_FAILED,
                host.getResourcePoolId(),
                host.getId(),
                instance.getId(),
                order.getId(),
                null,
                order.getExecutorType() != null ? order.getExecutorType() : "SSH",
                order.getEnvId(),
                order.getServiceId(),
                version.getId(),
                "RUNTIME_INSTANCE",
                instance.getId(),
                null,
                null,
                outcome.code(),
                detail,
                order.getCreatedBy()
        );
    }

    /**
     * 构造一条待执行的部署记录。
     *
     * <p>{@code executionClaimId} 在创建处就写入，由 {@code startRunning} 在插入的同一个短事务里
     * 落成 {@code runtime_deploy_record.execution_claim_id}。归属因此是“创建时就确定”的事实，
     * 不是事后按发布单推断的结果。
     */
    private RuntimeDeployRecord buildRunningRecord(ReleaseOrder order, RuntimeServiceInstance instance,
                                                   Long versionId, int deployBatch, boolean batch,
                                                   Long executionClaimId) {
        RuntimeDeployRecord record = new RuntimeDeployRecord();
        record.setReleaseOrderId(order.getId());
        record.setOperationType("RELEASE");
        record.setServiceId(order.getServiceId());
        record.setEnvId(order.getEnvId());
        record.setHostId(instance.getHostId());
        record.setVersionId(versionId);
        record.setDeployBatch(deployBatch);
        record.setDeployStatus("RUNNING");
        record.setStartedAt(OffsetDateTime.now());
        record.setExecutionClaimId(executionClaimId);
        record.setInstanceId(instance.getId());
        record.setInstanceName(instance.getInstanceName());
        record.setInstanceType(instance.getInstanceType());
        return record;
    }

    private AssetHost getRequiredHost(Long hostId) {
        AssetHost host = assetHostService.getById(hostId);
        if (host == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "目标主机不存在");
        }
        return host;
    }

    private static String renderCommand(
            String template,
            BaseServiceEntity service,
            BaseEnv env,
            ReleaseVersion version,
            ReleaseArtifact artifact,
            RuntimeServiceInstance instance,
            Map<String, String> deployConfig
    ) {
        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("serviceCode", service.getServiceCode());
        variables.put("serviceName", service.getServiceName());
        variables.put("envCode", env.getEnvCode());
        variables.put("envName", env.getEnvName());
        variables.put("versionNo", version.getVersionNo());
        variables.put("artifactUri", artifact.getArtifactUri());
        variables.put("artifactChecksum", artifact.getChecksum());
        variables.put("artifactUriShell", com.infrahub.release.pipeline.ComposeRemoteCommand.quote(artifact.getArtifactUri()));
        variables.put("artifactChecksumShell", com.infrahub.release.pipeline.ComposeRemoteCommand.quote(artifact.getChecksum()));
        variables.put("instanceNameShell", com.infrahub.release.pipeline.ComposeRemoteCommand.quote(instance.getInstanceName()));
        DeployRuntimeConfig runtimeConfig = DeployRuntimeConfig.from(deployConfig);
        variables.put("deployRoot", runtimeConfig.root());
        variables.put("deployPort", String.valueOf(runtimeConfig.port()));
        variables.put("deployPidFile", runtimeConfig.pidFile());
        variables.put("deployLogFile", runtimeConfig.logFile());
        variables.put("deployHealthUrl", runtimeConfig.healthUrl());
        variables.put("deployPath", deployConfig.getOrDefault("deploy.deploy.path", ""));
        variables.put("backupPath", deployConfig.getOrDefault("deploy.backup.path", ""));
        variables.put("packagePath", deployConfig.getOrDefault("deploy.package.path", ""));

        String command = template;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            command = command.replace("${" + entry.getKey() + "}", entry.getValue() == null ? "" : entry.getValue());
        }
        return command;
    }

    /**
     * 执行器结果落记录。保留原有“失败记录一定有日志与结束时间”的兜底，
     * 但状态按结果类别写入 SUCCESS / FAILED / UNKNOWN。
     */
    private static void applyExecutionResultToRecord(RuntimeDeployRecord record, ReleaseExecutionResult executionResult) {
        DeployExecutionOutcome outcome = executionResult.resolvedOutcome();
        record.setDeployStatus(recordStatusOf(outcome));
        if (executionResult.getStartedAt() != null) {
            record.setStartedAt(executionResult.getStartedAt());
        }
        if (executionResult.getEndedAt() != null) {
            record.setEndedAt(executionResult.getEndedAt());
        } else {
            record.setEndedAt(OffsetDateTime.now());
        }
        String fullLog = StringUtils.hasText(executionResult.getDeployLog())
                ? executionResult.getDeployLog()
                : executionResult.fullLog();
        if (StringUtils.hasText(fullLog)) {
            record.setDeployLog(fullLog);
        } else if (StringUtils.hasText(executionResult.getMessage())) {
            record.setDeployLog(executionResult.getMessage());
        } else {
            record.setDeployLog("执行完成");
        }
    }

    private ReleaseDeployExecutionResult executeWithExecutor(
            ReleaseOrder order,
            BaseServiceEntity service,
            BaseEnv env,
            ReleaseVersion version,
            ReleaseArtifact artifact,
            List<RuntimeServiceInstance> instances,
            Map<String, String> deployConfig,
            int deployBatch,
            boolean batch,
            ReleaseExecutorConfig executorConfig,
            Long executionClaimId
    ) {
        // P1-R D1: 执行前先解析 config_payload；解析失败不创建任何部署记录，也没有外部动作
        ReleaseExecutorConfigParser.ParseResult parseResult = releaseExecutorConfigParser.parse(executorConfig);
        if (!parseResult.success()) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, parseResult.errorMessage());
        }

        ReleaseExecutor executor = releaseExecutorRegistry.getExecutor(executorConfig.getExecutorType());

        List<Long> successInstanceIds = new ArrayList<>();
        List<InstanceOutcome> outcomes = new ArrayList<>();
        int failed = 0;
        int unknown = 0;
        int notStarted = 0;
        String firstFailureCode = null;
        boolean anyStarted = false;

        // 与 SSH 分支一致：先把主机登记校验完，任何缺失都发生在创建记录与外部调用之前
        Map<Long, AssetHost> hostById = new LinkedHashMap<>();
        for (RuntimeServiceInstance instance : instances) {
            hostById.put(instance.getId(), getRequiredHost(instance.getHostId()));
        }

        try {
            for (RuntimeServiceInstance instance : instances) {
                AssetHost host = hostById.get(instance.getId());
                RuntimeDeployRecord record = buildRunningRecord(order, instance, version.getId(), deployBatch,
                        batch, executionClaimId);
                runtimeDeployRecordService.startRunning(record);
                anyStarted = true;

                ReleaseExecutionContext context = ReleaseExecutionContext.builder()
                        .order(order)
                        .service(service)
                        .env(env)
                        .version(version)
                        .artifact(artifact)
                        .deployConfig(deployConfig)
                        .executorConfig(parseResult.config())
                        .hostId(instance.getHostId())
                        .instanceId(instance.getId())
                        .build();

                ReleaseExecutionResult executionResult;
                try {
                    executionResult = executor.execute(context);
                } catch (RuntimeException executorFailure) {
                    // 执行器已经拿到上下文并开始执行：抛异常时无法证明远端动作没发生
                    record.setDeployStatus(RECORD_UNKNOWN);
                    record.setDeployLog("执行器异常，结果未知；详见服务端日志");
                    record.setEndedAt(OffsetDateTime.now());
                    runtimeDeployRecordService.finishUnknown(record,
                            RuntimeExecutionUnknown.DETECTION_EXCEPTION,
                            "执行器抛出异常，结果未知");
                    throw new DeployExecutionUnknownException(
                            "实例 " + instance.getId() + " 的执行器异常，结果未知", executorFailure);
                }
                DeployExecutionOutcome instanceOutcome = executionResult.resolvedOutcome();

                applyExecutionResultToRecord(record, executionResult);
                if (RECORD_UNKNOWN.equals(record.getDeployStatus())) {
                    runtimeDeployRecordService.finishUnknown(record,
                            RuntimeExecutionUnknown.DETECTION_EVIDENCE,
                            "执行器结果无法证明：" + instanceOutcome.name());
                } else {
                    runtimeDeployRecordService.finish(record);
                }

                switch (instanceOutcome) {
                    case SUCCEEDED -> {
                        successInstanceIds.add(instance.getId());
                        recordGovernanceSuccess(order, host, instance, version, deployBatch, batch);
                    }
                    case UNKNOWN, PARTIAL -> {
                        unknown++;
                        if (firstFailureCode == null) {
                            firstFailureCode = CODE_EXECUTOR_UNKNOWN;
                        }
                        recordGovernanceFailure(order, host, instance, version, record, instanceOutcome, batch);
                    }
                    case NOT_STARTED -> {
                        notStarted++;
                        if (firstFailureCode == null) {
                            firstFailureCode = CODE_EXECUTOR_NOT_STARTED;
                        }
                        recordGovernanceFailure(order, host, instance, version, record, instanceOutcome, batch);
                    }
                    case FAILED -> {
                        failed++;
                        if (firstFailureCode == null) {
                            firstFailureCode = CODE_EXECUTOR_FAILED;
                        }
                        recordGovernanceFailure(order, host, instance, version, record, instanceOutcome, batch);
                    }
                }
                outcomes.add(new InstanceOutcome(instance.getId(), instanceOutcome));
                if (instanceOutcome == DeployExecutionOutcome.UNKNOWN || instanceOutcome == DeployExecutionOutcome.PARTIAL) {
                    for (int i = outcomes.size(); i < instances.size(); i++) {
                        outcomes.add(new InstanceOutcome(instances.get(i).getId(), DeployExecutionOutcome.NOT_STARTED));
                        notStarted++;
                    }
                    break;
                }
            }
        } catch (DeployExecutionUnknownException unknownFailure) {
            throw unknownFailure;
        } catch (RuntimeException loopFailure) {
            if (anyStarted) {
                throw new DeployExecutionUnknownException(
                        "执行器批次中断，已执行实例见部署记录，整体结果未知", loopFailure);
            }
            throw loopFailure;
        }

        return summarize(batch, deployBatch, instances.size(), successInstanceIds, failed, unknown, notStarted,
                firstFailureCode, outcomes, targetLabel(batch, deployBatch));
    }
}

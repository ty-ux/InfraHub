package com.infrahub.release.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.infrahub.base.entity.BaseApp;
import com.infrahub.base.entity.BaseEnv;
import com.infrahub.base.entity.BaseServiceEntity;
import com.infrahub.base.service.BaseAppService;
import com.infrahub.base.service.BaseEnvService;
import com.infrahub.base.service.BaseServiceEntityService;
import com.infrahub.common.context.UserContextHolder;
import com.infrahub.common.error.ErrorCode;
import com.infrahub.common.exception.BizException;
import com.infrahub.infra.ssh.SshExecutionRequest;
import com.infrahub.infra.ssh.SshExecutionResult;
import com.infrahub.infra.ssh.SshExecutorService;
import com.infrahub.pipeline.entity.PipelineRun;
import com.infrahub.pipeline.service.PipelineRunService;
import com.infrahub.release.dto.ReleaseOrderDetailResponse;
import com.infrahub.release.dto.ReleasePrecheckInstanceResponse;
import com.infrahub.release.dto.AdmissionDecision;
import com.infrahub.release.dto.ExecutionHistoryItem;
import com.infrahub.release.dto.TimelineNode;
import com.infrahub.release.dto.ExecuteResult;
import com.infrahub.release.entity.AssetHost;
import com.infrahub.release.entity.AssetHostSshConfig;
import com.infrahub.release.entity.ReleaseArtifact;
import com.infrahub.release.entity.ReleaseCurrentVersion;
import com.infrahub.release.entity.ReleaseOrder;
import com.infrahub.release.entity.ReleaseExecutorConfig;
import com.infrahub.release.entity.ReleaseRollbackOrder;
import com.infrahub.release.entity.ReleaseVersion;
import com.infrahub.release.entity.RuntimeDeployRecord;
import com.infrahub.release.entity.RuntimeServiceInstance;
import com.infrahub.release.entity.ApprovalRequest;
import com.infrahub.release.mapper.ReleaseOrderMapper;
import com.infrahub.release.mapper.ReleaseRollbackOrderMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.infrahub.release.dto.TimelineNode.*;

@Slf4j
@Service
public class ReleaseOrderService extends ServiceImpl<ReleaseOrderMapper, ReleaseOrder> {

    private final BaseAppService baseAppService;
    private final BaseServiceEntityService baseServiceEntityService;
    private final BaseEnvService baseEnvService;
    private final ReleaseVersionService releaseVersionService;
    private final ReleaseArtifactService releaseArtifactService;
    private final ReleaseCurrentVersionService releaseCurrentVersionService;
    private final RuntimeDeployRecordService runtimeDeployRecordService;
    private final RuntimeServiceInstanceService runtimeServiceInstanceService;
    private final AssetHostSshConfigService assetHostSshConfigService;
    private final AssetHostService assetHostService;
    private final DeployConfigService deployConfigService;
    private final SecretValueResolver secretValueResolver;
    private final SshExecutorService sshExecutorService;
    private final ReleasePrecheckService releasePrecheckService;
    private final ReleaseDeployExecutionService releaseDeployExecutionService;
    private final ReleaseRollbackOrderMapper releaseRollbackOrderMapper;
    private final DeployFailureCategorizer deployFailureCategorizer;
    private final PipelineRunService pipelineRunService;
    private final ApprovalPolicyService approvalPolicyService;
    private final ApprovalRequestService approvalRequestService;
    private final ReleaseAdmissionService admissionService;
    private final ReleaseExecutorConfigService releaseExecutorConfigService;
    private final ReleasePrecheckRecordService releasePrecheckRecordService;
    /**
     * 发布执行认领协调：直接发布、审批回调、流水线触发共用同一套数据库短事务认领规则。
     * 只依赖 Mapper 与查询/写入服务，与发布服务不形成循环依赖，因此无需 @Lazy。
     */
    private final ReleaseExecutionClaimService releaseExecutionClaimService;
    /**
     * 实例覆盖范围：订单结果为 SUCCESS 不等于“该服务/环境的当前实例全部已部署到该版本”，
     * 详情页需要一个能证明覆盖范围的只读结论。
     */
    private final ReleaseDeployCoverageService releaseDeployCoverageService;
    /** 当前实例登记版本与目标版本的一致性：与覆盖范围是两件事，必须单独计算。 */
    private final VersionRegistrationConsistencyService versionRegistrationConsistencyService;

    @Autowired
    public ReleaseOrderService(
            BaseAppService baseAppService,
            BaseServiceEntityService baseServiceEntityService,
            BaseEnvService baseEnvService,
            ReleaseVersionService releaseVersionService,
            ReleaseArtifactService releaseArtifactService,
            ReleaseCurrentVersionService releaseCurrentVersionService,
            RuntimeDeployRecordService runtimeDeployRecordService,
            RuntimeServiceInstanceService runtimeServiceInstanceService,
            AssetHostSshConfigService assetHostSshConfigService,
            AssetHostService assetHostService,
            DeployConfigService deployConfigService,
            SecretValueResolver secretValueResolver,
            SshExecutorService sshExecutorService,
            ReleasePrecheckService releasePrecheckService,
            ReleaseDeployExecutionService releaseDeployExecutionService,
            ReleaseRollbackOrderMapper releaseRollbackOrderMapper,
            DeployFailureCategorizer deployFailureCategorizer,
            @Lazy PipelineRunService pipelineRunService,
            ApprovalPolicyService approvalPolicyService,
            @Lazy ApprovalRequestService approvalRequestService,
            ReleaseAdmissionService admissionService,
            ReleaseExecutorConfigService releaseExecutorConfigService,
            ReleasePrecheckRecordService releasePrecheckRecordService,
            ReleaseExecutionClaimService releaseExecutionClaimService,
            ReleaseDeployCoverageService releaseDeployCoverageService,
            VersionRegistrationConsistencyService versionRegistrationConsistencyService
    ) {
        this.baseAppService = baseAppService;
        this.baseServiceEntityService = baseServiceEntityService;
        this.baseEnvService = baseEnvService;
        this.releaseVersionService = releaseVersionService;
        this.releaseArtifactService = releaseArtifactService;
        this.releaseCurrentVersionService = releaseCurrentVersionService;
        this.runtimeDeployRecordService = runtimeDeployRecordService;
        this.runtimeServiceInstanceService = runtimeServiceInstanceService;
        this.assetHostSshConfigService = assetHostSshConfigService;
        this.assetHostService = assetHostService;
        this.deployConfigService = deployConfigService;
        this.secretValueResolver = secretValueResolver;
        this.sshExecutorService = sshExecutorService;
        this.releasePrecheckService = releasePrecheckService;
        this.releaseDeployExecutionService = releaseDeployExecutionService;
        this.releaseRollbackOrderMapper = releaseRollbackOrderMapper;
        this.deployFailureCategorizer = deployFailureCategorizer;
        this.pipelineRunService = pipelineRunService;
        this.approvalPolicyService = approvalPolicyService;
        this.approvalRequestService = approvalRequestService;
        this.admissionService = admissionService;
        this.releaseExecutorConfigService = releaseExecutorConfigService;
        this.releasePrecheckRecordService = releasePrecheckRecordService;
        this.releaseExecutionClaimService = releaseExecutionClaimService;
        this.releaseDeployCoverageService = releaseDeployCoverageService;
        this.versionRegistrationConsistencyService = versionRegistrationConsistencyService;
    }

    public boolean createReleaseOrder(ReleaseOrder body) {
        validateRequiredIds(body);

        BaseApp app = getRequiredApp(body.getAppId(), "发布单关联的应用不存在");
        BaseServiceEntity service = getRequiredService(body.getServiceId(), "发布单关联的服务不存在");
        if (!body.getAppId().equals(service.getAppId())) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "所选服务不属于当前应用");
        }

        getRequiredEnv(body.getEnvId(), "发布单关联的环境不存在");
        ReleaseVersion version = getRequiredVersion(body.getVersionId(), "发布单关联的版本不存在");
        if (!app.getId().equals(version.getAppId())) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "所选版本不属于当前应用");
        }

        // F+1-A: 校验执行器配置环境一致性
        if (body.getExecutorConfigId() != null) {
            ReleaseExecutorConfig executorConfig = releaseExecutorConfigService.getRequiredEnabledById(body.getExecutorConfigId());
            if (!executorConfig.getEnvId().equals(body.getEnvId())) {
                throw new BizException(ErrorCode.VALIDATION_ERROR, "执行器配置不属于发布目标环境");
            }
            body.setExecutorType(executorConfig.getExecutorType());
        }

        body.setId(null);
        if (!StringUtils.hasText(body.getStatus())) {
            body.setStatus("CREATED");
        }
        if (!StringUtils.hasText(body.getReleaseType())) {
            body.setReleaseType("FULL");
        }
        if (!StringUtils.hasText(body.getRollbackStatus())) {
            body.setRollbackStatus("NOT_ROLLED_BACK");
        }
        if (body.getRollbackCount() == null) {
            body.setRollbackCount(0);
        }

        return save(body);
    }

    /**
     * Preflight validation for release order execution.
     * Checks all conditions without modifying any state.
     * Used before creating approval request to ensure the order is executable.
     */
    private void preflightExecuteReleaseOrder(Long releaseOrderId) {
        ReleaseOrder order = getRequiredOrder(releaseOrderId);
        ensureExecutableStatus(order.getStatus());
        ensureNoRunningOperation(order.getServiceId(), order.getEnvId());

        getRequiredService(order.getServiceId(), "发布单关联的服务不存在");
        getRequiredEnv(order.getEnvId(), "发布单关联的环境不存在");
        ReleaseVersion version = getRequiredVersion(order.getVersionId(), "发布单关联的版本不存在");
        getRequiredArtifact(version.getArtifactId());

        List<RuntimeServiceInstance> instances = runtimeServiceInstanceService.listByServiceAndEnv(order.getServiceId(), order.getEnvId());
        if (instances.isEmpty()) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "当前服务在目标环境下没有可发布的实例");
        }
    }

    /**
     * Execute release order with approval check.
     * If approval policy is enabled, creates an approval request and returns approvalId.
     * If approval policy is disabled, executes directly and returns directExecuted=true.
     */
    public void checkAutomaticPipelineAdmission(Long releaseOrderId) {
        preflightExecuteReleaseOrder(releaseOrderId);
        ReleaseOrder order = getRequiredOrder(releaseOrderId);
        List<ReleasePrecheckInstanceResponse> checks = precheckReleaseOrder(releaseOrderId, true);
        if (checks.stream().anyMatch(check -> !check.isSuccess())) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "流水线部署前置检查失败，请查看部署记录和制品地址");
        }
        AdmissionDecision admission = admissionService.decide(order.getEnvId(), "EXECUTE_RELEASE", "LOW", "RELEASE_ORDER", releaseOrderId);
        if (!admission.isAllowed()) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "发布准入阻断：" + admission.getBlockedReason());
        }
        if (admission.isApprovalRequired() || approvalPolicyService.isApprovalRequired(
                ApprovalRequest.BIZ_TYPE_RELEASE_ORDER, ApprovalRequest.ACTION_EXECUTE_RELEASE)) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "目标环境要求发布审批，流水线自动部署已阻止");
        }
    }

    public ExecuteResult executeReleaseOrderWithApproval(Long releaseOrderId) {
        // Preflight validation before any action
        preflightExecuteReleaseOrder(releaseOrderId);

        // 准入判定
        ReleaseOrder order = getById(releaseOrderId);
        AdmissionDecision admission = admissionService.decide(
                order.getEnvId(),
                "EXECUTE_RELEASE",
                order.getRiskLevel() != null ? order.getRiskLevel() : "LOW",
                "RELEASE_ORDER",
                releaseOrderId
        );

        if (!admission.isAllowed()) {
            // 阻断时不创建审批、不执行发布
            throw new BizException(ErrorCode.VALIDATION_ERROR, "发布准入阻断：" + admission.getBlockedReason());
        }

        // D2 准入策略要求审批时，直接创建审批单（跳过 ApprovalPolicyService 检查）
        if (admission.isApprovalRequired()) {
            Long userId = UserContextHolder.getUserIdOrDefault(1L);
            String payload = "{\"orderNo\":\"" + order.getOrderNo() + "\",\"action\":\"EXECUTE_RELEASE\"}";
            Long approvalId = approvalRequestService.createRequestUnchecked(
                    ApprovalRequest.BIZ_TYPE_RELEASE_ORDER, releaseOrderId, ApprovalRequest.ACTION_EXECUTE_RELEASE,
                    userId, "执行发布需审批（准入策略）", payload);
            return ExecuteResult.pendingApproval(approvalId);
        }

        // C 阶段 ApprovalPolicyService 要求审批时，使用原有逻辑
        if (approvalPolicyService.isApprovalRequired(ApprovalRequest.BIZ_TYPE_RELEASE_ORDER, ApprovalRequest.ACTION_EXECUTE_RELEASE)) {
            Long userId = UserContextHolder.getUserIdOrDefault(1L);
            String payload = "{\"orderNo\":\"" + order.getOrderNo() + "\",\"action\":\"EXECUTE_RELEASE\"}";
            Long approvalId = approvalRequestService.createRequest(
                    ApprovalRequest.BIZ_TYPE_RELEASE_ORDER, releaseOrderId, ApprovalRequest.ACTION_EXECUTE_RELEASE,
                    userId, "执行发布需审批", payload);
            if (approvalId != null) {
                return ExecuteResult.pendingApproval(approvalId);
            }
        }

        // No approval required, execute directly
        doExecuteReleaseOrder(releaseOrderId);
        return ExecuteResult.directExecuted();
    }

    /**
     * Execute release order directly (no approval request creation).
     *
     * <p>与 {@link #doExecuteReleaseOrder(Long)} 一样是事务外入口：认领与终态各自走短事务。
     */
    public boolean executeReleaseOrder(Long releaseOrderId) {
        return doExecuteReleaseOrder(releaseOrderId);
    }

    /**
     * Direct execute release order without approval check.
     *
     * <p>直接发布 / 流水线触发入口：本方法负责认领，是一整套执行的事务外入口。
     * 执行审批批准回调不走这里，而是由审批服务先完成“批准 + 认领”的同一短事务，
     * 再把认领结果交给 {@link #executeClaimedReleaseOrder}，避免同一次执行被认领两次。
     *
     * <p>明确三段，本方法自身没有事务边界：
     * <ol>
     *   <li>短事务认领：{@link ReleaseExecutionClaimService#claimForExecution} 取服务行锁 → 发布单行锁，
     *       重读事实并条件更新 RUNNING，提交后锁释放；</li>
     *   <li>事务外调用外部执行器（kubectl / SSH / Docker 等待期间不持有任何数据库事务）；</li>
     *   <li>短事务记录终态：SUCCESS（含实例与当前版本更新）或 FAILED（含失败证据）。</li>
     * </ol>
     *
     * <p>到达 {@code executeReleaseDeploy} 之前，审批批准事实、发布单 RUNNING、部署记录 RUNNING
     * 都已经各自提交，外部连接与既有查询 API 能看到运行状态；执行失败或异常时，
     * 失败终态与部署记录证据也会落库，不随任何外围事务回滚消失。
     */
    public boolean doExecuteReleaseOrder(Long releaseOrderId) {
        // 认领在读取执行输入之前完成：认领事务持服务行锁，移除登记必须等它提交；
        // 提交后再枚举实例，读到的是“不会被同时移除”的当前集合。反过来先读实例再认领，
        // 会让一份包含已移除实例的旧快照带进外部执行。
        ReleaseExecutionClaimService.ClaimedExecution claimed =
                releaseExecutionClaimService.claimForExecution(releaseOrderId);
        return executeClaimedReleaseOrder(claimed);
    }

    /**
     * 执行“已经认领成功”的发布单：执行审批批准回调走这里。
     *
     * <p>入参类型是 {@link ReleaseExecutionClaimService.ClaimedExecution}，只能由认领服务产出，
     * 因此本方法天然不会重复认领，也不会把“订单当前是 RUNNING”这种观察式判定当成自己的认领：
     * 别人的认领结果在类型上就传不进来，审批回调与流水线触发不会互相抢同一次执行。
     *
     * <p>执行需要的基础数据在认领之后才加载。加载失败时订单已是 RUNNING，必须把它收敛成
     * FAILED 并补齐部署记录失败证据，不能让 RUNNING 悬挂；原始异常继续抛出，错误分类不变。
     */
    public boolean executeClaimedReleaseOrder(ReleaseExecutionClaimService.ClaimedExecution claimedExecution) {
        if (claimedExecution == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "发布执行缺少本次成功认领的结果，已拒绝执行");
        }
        ReleaseOrder claimed = claimedExecution.order();
        if (claimed == null || claimed.getId() == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "发布执行认领结果不完整，已拒绝执行");
        }

        ExecutionInputs inputs;
        try {
            inputs = loadExecutionInputs(claimed);
        } catch (RuntimeException exception) {
            log.error("发布单 {} 执行前校验失败，未调用远端，errorType={}",
                    claimed.getId(), exception.getClass().getSimpleName());
            markFailedQuietly(claimed.getId(), "执行前校验失败（PRE_EXECUTION_VALIDATION），未调用远端", exception);
            throw exception;
        }

        return runClaimedExecution(claimedExecution, inputs);
    }

    /**
     * 加载执行所需的基础数据。认领之前只在这里读一次，认领之后只使用本次认领返回的订单对象。
     */
    private ExecutionInputs loadExecutionInputs(ReleaseOrder order) {
        BaseServiceEntity service = getRequiredService(order.getServiceId(), "发布单关联的服务不存在");
        BaseEnv env = getRequiredEnv(order.getEnvId(), "发布单关联的环境不存在");
        ReleaseVersion version = getRequiredVersion(order.getVersionId(), "发布单关联的版本不存在");
        ReleaseArtifact artifact = getRequiredArtifact(version.getArtifactId());

        List<RuntimeServiceInstance> instances = runtimeServiceInstanceService.listByServiceAndEnv(order.getServiceId(), order.getEnvId());
        if (instances.isEmpty()) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "当前服务在目标环境下没有可发布的实例");
        }

        Map<String, String> deployConfig = deployConfigService.loadDeployConfig(order.getAppId(), order.getEnvId());

        return new ExecutionInputs(service, env, version, artifact, instances, deployConfig);
    }

    /**
     * 认领成功之后的执行：事务外调用外部执行器，再按结果落终态。
     *
     * <p>本方法自身无事务边界：外部执行器（kubectl / SSH / Docker）等待期间不持有任何数据库事务，
     * 终态各走自己的短事务。竞争失败者根本走不到这里（认领已抛异常），因此只有认领赢家会写终态。
     */
    private boolean runClaimedExecution(ReleaseExecutionClaimService.ClaimedExecution claimedExecution,
                                        ExecutionInputs inputs) {
        ReleaseOrder claimed = claimedExecution.order();

        // 2) 事务外调用外部执行器
        ReleaseDeployExecutionResult deployResult;
        try {
            deployResult = releaseDeployExecutionService.executeReleaseDeploy(
                    claimed,
                    inputs.service(),
                    inputs.env(),
                    inputs.version(),
                    inputs.artifact(),
                    inputs.instances(),
                    inputs.deployConfig()
            );
        } catch (com.infrahub.release.support.DeployExecutionUnknownException unknownFailure) {
            // 结果未知：部署记录收敛为 UNKNOWN，不谎报确定失败；只记录异常类型，不输出原始异常链
            log.error("发布单 {} 外部执行中断，结果未知，errorType={}",
                    claimed.getId(), unknownFailure.getClass().getSimpleName());
            markFailedUnknownQuietly(claimed.getId(),
                    "外部执行中断（EXECUTION_EXCEPTION），远端结果未知；禁止自动重试", unknownFailure);
            throw new BizException(ErrorCode.INTERNAL_ERROR,
                    "发布单 " + claimed.getId()
                            + " 外部执行中断，结果未知；禁止自动重试；请人工核对部署记录与实例状态",
                    unknownFailure);
        } catch (RuntimeException exception) {
            // 开始任何实例之前就失败（配置/主机校验）：确定未开始，原始异常可安全返回
            log.error("发布单 {} 执行前校验失败，未调用远端，errorType={}",
                    claimed.getId(), exception.getClass().getSimpleName());
            markFailedQuietly(claimed.getId(), "执行前校验失败（PRE_EXECUTION_VALIDATION），未调用远端", exception);
            throw exception;
        }

        // 3) 短事务记录终态
        if (!deployResult.success()) {
            // 结果未知/部分成功时，残留为 RUNNING 的记录同样只能收敛成 UNKNOWN（发布单本身仍是 FAILED）
            if (deployResult.outcome().requiresManualVerification()) {
                releaseExecutionClaimService.markFailedUnknown(claimed.getId(), deployResult.message());
            } else {
                releaseExecutionClaimService.markFailed(claimed.getId(), deployResult.message());
            }
            return false;
        }

        releaseExecutionClaimService.markSucceeded(
                claimed.getId(), claimed.getEnvId(), claimed.getServiceId(),
                deployResult.successInstanceIds(), inputs.version().getId());
        return true;
    }

    /** 一次执行所需的基础数据快照，只在本次认领的执行内传递。 */
    private record ExecutionInputs(BaseServiceEntity service,
                                   BaseEnv env,
                                   ReleaseVersion version,
                                   ReleaseArtifact artifact,
                                   List<RuntimeServiceInstance> instances,
                                   Map<String, String> deployConfig) {
    }

    /** 失败终态不允许覆盖原始异常：记录失败原因时出错只告警，原始异常照原样抛出。 */
    private void markFailedQuietly(Long releaseOrderId, String failureReason, RuntimeException original) {
        try {
            releaseExecutionClaimService.markFailed(releaseOrderId, failureReason);
        } catch (RuntimeException recordFailure) {
            log.error("发布单 {} 失败终态记录失败，原始异常 {} 继续抛出，errorType={}",
                    releaseOrderId, original.getClass().getSimpleName(), recordFailure.getClass().getSimpleName());
        }
    }

    /** 结果未知的失败终态：部署记录收敛为 UNKNOWN；记录失败时同样不覆盖原始异常。 */
    private void markFailedUnknownQuietly(Long releaseOrderId, String failureReason, RuntimeException original) {
        try {
            releaseExecutionClaimService.markFailedUnknown(releaseOrderId, failureReason);
        } catch (RuntimeException recordFailure) {
            log.error("发布单 {} 未知结果终态记录失败，原始异常 {} 继续抛出，errorType={}",
                    releaseOrderId, original.getClass().getSimpleName(), recordFailure.getClass().getSimpleName());
        }
    }

    public List<ReleasePrecheckInstanceResponse> precheckReleaseOrder(Long releaseOrderId) {
        return precheckReleaseOrder(releaseOrderId, false);
    }

    private List<ReleasePrecheckInstanceResponse> precheckReleaseOrder(Long releaseOrderId, boolean verifyChecksum) {
        ReleaseOrder order = getRequiredOrder(releaseOrderId);
        ensureExecutableStatus(order.getStatus());

        ReleaseVersion version = getRequiredVersion(order.getVersionId(), "发布单关联的版本不存在");
        ReleaseArtifact artifact = getRequiredArtifact(version.getArtifactId());
        List<RuntimeServiceInstance> instances = runtimeServiceInstanceService.listByServiceAndEnv(order.getServiceId(), order.getEnvId());
        if (instances.isEmpty()) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "当前服务在目标环境下没有可发布的实例");
        }

        Map<String, String> deployConfig = deployConfigService.loadDeployConfig(order.getAppId(), order.getEnvId());
        List<ReleasePrecheckInstanceResponse> responses = new ArrayList<>();
        List<ReleasePrecheckRecordService.PrecheckInstanceOutcome> outcomes = new ArrayList<>();
        for (RuntimeServiceInstance instance : instances) {
            if (instance.getHostId() == null) {
                throw new BizException(ErrorCode.VALIDATION_ERROR, "存在未绑定主机的服务实例，无法执行发布");
            }
            AssetHost host = getRequiredHost(instance.getHostId());
            AssetHostSshConfig sshConfig = assetHostSshConfigService.getEnabledByHostId(instance.getHostId());
            if (sshConfig == null) {
                throw new BizException(ErrorCode.VALIDATION_ERROR, "目标主机缺少启用中的 SSH 配置");
            }

            ReleasePrecheckResult result;
            try {
                DeployRuntimeConfig config = DeployRuntimeConfig.from(deployConfig);
                result = verifyChecksum
                        ? releasePrecheckService.check(host, sshConfig, config, artifact.getArtifactUri(), artifact.getChecksum())
                        : releasePrecheckService.check(host, sshConfig, config, artifact.getArtifactUri());
            } catch (Exception e) {
                String failureReason = "SSH 执行异常: " + e.getClass().getSimpleName();
                outcomes.add(new ReleasePrecheckRecordService.PrecheckInstanceOutcome(false, failureReason));
                responses.add(precheckFailureResponse(instance, artifact.getArtifactUri(), failureReason));
                continue;
            }
            outcomes.add(new ReleasePrecheckRecordService.PrecheckInstanceOutcome(result.isSuccess(), result.errorMessage()));
            responses.add(toPrecheckResponse(instance, artifact.getArtifactUri(), result));
        }
        try {
            releasePrecheckRecordService.recordPrecheck(
                    "RELEASE_ORDER", order.getId(), order.getEnvId(), order.getServiceId(), outcomes);
        } catch (Exception e) {
            log.warn("前置检查记录写入失败: bizType=RELEASE_ORDER, bizId={}, cause={}",
                    order.getId(), e.getClass().getSimpleName());
        }
        return responses;
    }

    @Transactional
    public boolean cancelReleaseOrder(Long releaseOrderId) {
        ReleaseOrder order = getRequiredOrder(releaseOrderId);
        if (!"CREATED".equalsIgnoreCase(order.getStatus())) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "仅 CREATED 状态的发布单允许取消");
        }
        order.setStatus("REJECTED");
        return updateById(order);
    }

    private ReleaseOrder getRequiredOrder(Long releaseOrderId) {
        ReleaseOrder order = getById(releaseOrderId);
        if (order == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "发布单不存在");
        }
        return order;
    }

    private void ensureExecutableStatus(String status) {
        if (!"CREATED".equalsIgnoreCase(status) && !"APPROVED".equalsIgnoreCase(status)) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "当前发布单状态不允许执行");
        }
    }

    private void ensureNoRunningOperation(Long serviceId, Long envId) {
        long runningReleaseCount = baseMapper.countRunningByServiceAndEnv(serviceId, envId);
        if (runningReleaseCount > 0 || releaseRollbackOrderMapper.countRunningByServiceAndEnv(serviceId, envId) > 0) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "当前服务在该环境下已有执行中的发布或回滚任务");
        }
    }

    private RuntimeDeployRecord buildRunningRecord(
            ReleaseOrder order,
            Long rollbackOrderId,
            RuntimeServiceInstance instance,
            Long versionId,
            String operationType
    ) {
        RuntimeDeployRecord record = new RuntimeDeployRecord();
        record.setReleaseOrderId(order.getId());
        record.setRollbackOrderId(rollbackOrderId);
        record.setOperationType(operationType);
        record.setServiceId(order.getServiceId());
        record.setEnvId(order.getEnvId());
        record.setHostId(instance.getHostId());
        record.setVersionId(versionId);
        record.setDeployBatch(1);
        record.setDeployStatus("RUNNING");
        record.setStartedAt(OffsetDateTime.now());
        return record;
    }

    private static String renderCommand(
            String template,
            BaseServiceEntity service,
            BaseEnv env,
            ReleaseVersion version,
            ReleaseArtifact artifact,
            Map<String, String> deployConfig
    ) {
        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("serviceCode", service.getServiceCode());
        variables.put("serviceName", service.getServiceName());
        variables.put("envCode", env.getEnvCode());
        variables.put("envName", env.getEnvName());
        variables.put("versionNo", version.getVersionNo());
        variables.put("artifactUri", artifact.getArtifactUri());
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

    private BaseApp getRequiredApp(Long appId, String message) {
        BaseApp app = baseAppService.getById(appId);
        if (app == null || Boolean.TRUE.equals(app.getIsDeleted())) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, message);
        }
        return app;
    }

    private BaseServiceEntity getRequiredService(Long serviceId, String message) {
        BaseServiceEntity service = baseServiceEntityService.getById(serviceId);
        if (service == null || Boolean.TRUE.equals(service.getIsDeleted())) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, message);
        }
        return service;
    }

    private BaseEnv getRequiredEnv(Long envId, String message) {
        BaseEnv env = baseEnvService.getById(envId);
        if (env == null || Boolean.TRUE.equals(env.getIsDeleted())) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, message);
        }
        return env;
    }

    private ReleaseVersion getRequiredVersion(Long versionId, String message) {
        ReleaseVersion version = releaseVersionService.getById(versionId);
        if (version == null || Boolean.TRUE.equals(version.getIsDeleted())) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, message);
        }
        return version;
    }

    private ReleaseArtifact getRequiredArtifact(Long artifactId) {
        ReleaseArtifact artifact = releaseArtifactService.getById(artifactId);
        if (artifact == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "发布版本缺少制品信息");
        }
        return artifact;
    }

    private AssetHost getRequiredHost(Long hostId) {
        AssetHost host = assetHostService.getById(hostId);
        if (host == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "目标主机不存在");
        }
        return host;
    }

    private static void validateRequiredIds(ReleaseOrder body) {
        if (body.getAppId() == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "应用 ID 不能为空");
        }
        if (body.getServiceId() == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "服务 ID 不能为空");
        }
        if (body.getEnvId() == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "环境 ID 不能为空");
        }
        if (body.getVersionId() == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "版本 ID 不能为空");
        }
        if (!StringUtils.hasText(body.getOrderNo())) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "发布单号不能为空");
        }
    }

    private static ReleasePrecheckInstanceResponse precheckFailureResponse(
            RuntimeServiceInstance instance,
            String artifactUri,
            String failureReason
    ) {
        ReleasePrecheckInstanceResponse response = new ReleasePrecheckInstanceResponse();
        response.setSuccess(false);
        response.setHostId(instance.getHostId());
        response.setInstanceId(instance.getId());
        response.setArtifactUri(artifactUri);
        response.setErrorMessage(failureReason);
        return response;
    }

    private static ReleasePrecheckInstanceResponse toPrecheckResponse(
            RuntimeServiceInstance instance,
            String artifactUri,
            ReleasePrecheckResult result
    ) {
        ReleasePrecheckInstanceResponse response = new ReleasePrecheckInstanceResponse();
        response.setSuccess(result.isSuccess());
        response.setHostId(instance.getHostId());
        response.setInstanceId(instance.getId());
        response.setArtifactUri(artifactUri);
        response.setStdout(result.executionResult().getStdout());
        response.setStderr(result.executionResult().getStderr());
        response.setErrorMessage(result.errorMessage());
        return response;
    }

    public ReleaseOrderDetailResponse getDetail(Long releaseOrderId) {
        ReleaseOrder order = getRequiredOrder(releaseOrderId);

        ReleaseOrderDetailResponse response = new ReleaseOrderDetailResponse();
        response.setId(order.getId());
        response.setOrderNo(order.getOrderNo());
        response.setStatus(order.getStatus());
        if (order.getCreatedAt() != null) {
            response.setCreatedAt(order.getCreatedAt());
        }

        BaseApp app = baseAppService.getById(order.getAppId());
        if (app != null) {
            response.setAppName(app.getAppName());
        }

        BaseServiceEntity service = baseServiceEntityService.getById(order.getServiceId());
        if (service != null) {
            response.setServiceName(service.getServiceName());
        }

        BaseEnv env = baseEnvService.getById(order.getEnvId());
        if (env != null) {
            response.setEnvName(env.getEnvName());
        }

        ReleaseVersion version = releaseVersionService.getById(order.getVersionId());
        if (version != null) {
            response.setVersionNo(version.getVersionNo());
        }

        RuntimeDeployRecord latestRecord = runtimeDeployRecordService.getLatestByReleaseOrderId(releaseOrderId);
        if (latestRecord != null) {
            response.setLatestDeployStatus(latestRecord.getDeployStatus());
            if ("FAILED".equals(latestRecord.getDeployStatus()) && latestRecord.getDeployLog() != null) {
                response.setLatestFailureHint(deployFailureCategorizer.categorize(latestRecord.getDeployLog()));
            }
        }
        response.setHasDeployRecords(runtimeDeployRecordService.existsByReleaseOrderId(releaseOrderId));

        PipelineRun pipelineRun = pipelineRunService.lambdaQuery()
                .eq(PipelineRun::getReleaseId, releaseOrderId)
                .orderByDesc(PipelineRun::getCreatedAt)
                .last("LIMIT 1")
                .one();
        if (pipelineRun != null) {
            response.setPipelineRunId(pipelineRun.getId());
        }

        ReleaseCurrentVersion currentVersion = releaseCurrentVersionService.lambdaQuery()
                .eq(ReleaseCurrentVersion::getEnvId, order.getEnvId())
                .eq(ReleaseCurrentVersion::getServiceId, order.getServiceId())
                .one();
        if (currentVersion != null) {
            ReleaseVersion currentVersionEntity = releaseVersionService.getById(currentVersion.getVersionId());
            if (currentVersionEntity != null) {
                response.setCurrentVersionNo(currentVersionEntity.getVersionNo());
            }
            if (currentVersion.getSourceReleaseOrderId() != null) {
                ReleaseOrder sourceOrder = getById(currentVersion.getSourceReleaseOrderId());
                if (sourceOrder != null) {
                    response.setCurrentVersionSourceReleaseOrderNo(sourceOrder.getOrderNo());
                }
            }
            if (currentVersion.getSourceRollbackOrderId() != null) {
                ReleaseRollbackOrder sourceRollback = releaseRollbackOrderMapper.selectById(currentVersion.getSourceRollbackOrderId());
                if (sourceRollback != null) {
                    response.setCurrentVersionSourceRollbackOrderNo(sourceRollback.getRollbackNo());
                }
            }
        }

        // 本单这次执行覆盖了哪些实例：订单 SUCCESS 只说明本次执行产出的记录全部成功，
        // 不说明该服务/环境当前的实例全都在该版本上。
        response.setDeploymentCoverage(releaseDeployCoverageService.forReleaseOrder(
                order.getId(), order.getServiceId(), order.getEnvId(), order.getVersionId()));

        // 当前实例的登记版本是否等于本单目标版本。与覆盖范围分开表达：历史成功不代表现在仍一致。
        response.setRegistrationConsistency(
                versionRegistrationConsistencyService.forReleaseOrder(order));

        // Executor fields
        response.setExecutorType(order.getExecutorType() != null ? order.getExecutorType() : "SSH");
        response.setExecutorConfigId(order.getExecutorConfigId());
        if (order.getExecutorConfigId() != null) {
            ReleaseExecutorConfig executorConfig = releaseExecutorConfigService.getById(order.getExecutorConfigId());
            if (executorConfig != null) {
                response.setExecutorConfigName(executorConfig.getExecutorName());
            }
        }

        // Build timeline
        response.setTimeline(buildReleaseTimeline(order, latestRecord, pipelineRun));

        // Build execution history
        response.setExecutionHistory(buildExecutionHistory(releaseOrderId));

        return response;
    }

    private List<ExecutionHistoryItem> buildExecutionHistory(Long releaseOrderId) {
        List<RuntimeDeployRecord> records = runtimeDeployRecordService.listByReleaseOrderId(releaseOrderId);
        int totalAttempts = records.size();
        List<RuntimeDeployRecord> limited = records.stream().limit(5).toList();
        List<ExecutionHistoryItem> result = new java.util.ArrayList<>();
        for (int i = 0; i < limited.size(); i++) {
            result.add(toExecutionHistoryItem(limited.get(i), totalAttempts - i));
        }
        return result;
    }

    private ExecutionHistoryItem toExecutionHistoryItem(RuntimeDeployRecord record, int attemptNumber) {
        String summary = getSummaryForHistory(record);
        String failureHint = "FAILED".equals(record.getDeployStatus()) && record.getDeployLog() != null
                ? deployFailureCategorizer.categorize(record.getDeployLog())
                : null;

        return ExecutionHistoryItem.builder()
                .attemptNumber(attemptNumber)
                .deployRecordId(record.getId())
                .operationType(record.getOperationType())
                .status(record.getDeployStatus())
                .startedAt(record.getStartedAt())
                .endedAt(record.getEndedAt())
                .summary(summary)
                .failureHint(failureHint)
                .build();
    }

    private String getSummaryForHistory(RuntimeDeployRecord record) {
        if (record == null) return "";
        if ("RUNNING".equals(record.getDeployStatus())) return "执行中";
        if ("SUCCESS".equals(record.getDeployStatus())) return "执行成功";
        if ("FAILED".equals(record.getDeployStatus())) {
            return record.getDeployLog() != null
                    ? deployFailureCategorizer.categorize(record.getDeployLog())
                    : "执行失败";
        }
        return record.getDeployStatus() != null ? record.getDeployStatus() : "";
    }

    private List<TimelineNode> buildReleaseTimeline(ReleaseOrder order, RuntimeDeployRecord latestRecord, PipelineRun pipelineRun) {
        List<TimelineNode> timeline = new ArrayList<>();

        // 1. Created node
        timeline.add(TimelineNode.builder()
                .key(NodeKey.CREATED.name())
                .title("单据创建")
                .status(NodeStatus.SUCCESS.name())
                .time(order.getCreatedAt())
                .summary("发布单已创建")
                .actionType(ActionType.NONE.name())
                .build());

        // 2. Precheck node - if there are deploy records, we can infer precheck was done
        if (latestRecord != null) {
            timeline.add(TimelineNode.builder()
                    .key(NodeKey.PRECHECK.name())
                    .title("前置检查")
                    .status(NodeStatus.SUCCESS.name())
                    .time(latestRecord.getStartedAt())
                    .summary("前置检查已通过")
                    .actionType(ActionType.PRECHECK.name())
                    .build());
        }

        // 3. Pipeline node (only for release orders with pipeline)
        if (pipelineRun != null) {
            NodeStatus pipelineStatus = mapPipelineStatusToNodeStatus(pipelineRun.getRunStatus());
            timeline.add(TimelineNode.builder()
                    .key(NodeKey.PIPELINE.name())
                    .title("流水线触发")
                    .status(pipelineStatus.name())
                    .time(pipelineRun.getStartTime() != null ? pipelineRun.getStartTime() : pipelineRun.getCreatedAt())
                    .summary(getPipelineSummary(pipelineRun))
                    .actionType(ActionType.PIPELINE_RUN.name())
                    .build());
        }

        // 4. Deploy node
        if (latestRecord != null && latestRecord.getStartedAt() != null) {
            NodeStatus deployStatus = mapDeployStatusToNodeStatus(latestRecord.getDeployStatus());
            timeline.add(TimelineNode.builder()
                    .key(NodeKey.DEPLOY.name())
                    .title("部署")
                    .status(deployStatus.name())
                    .time(latestRecord.getStartedAt())
                    .summary(getDeploySummary(latestRecord))
                    .actionType(ActionType.DEPLOY_RECORDS.name())
                    .build());
        }

        // 5. Health check node - derived from deploy record
        if (latestRecord != null && "SUCCESS".equals(latestRecord.getDeployStatus())) {
            timeline.add(TimelineNode.builder()
                    .key(NodeKey.HEALTH_CHECK.name())
                    .title("健康检查")
                    .status(NodeStatus.SUCCESS.name())
                    .time(latestRecord.getEndedAt())
                    .summary("健康检查通过")
                    .actionType(ActionType.DEPLOY_RECORDS.name())
                    .build());
        } else if (latestRecord != null && "FAILED".equals(latestRecord.getDeployStatus())) {
            String healthSummary = latestRecord.getDeployLog() != null && latestRecord.getDeployLog().contains("Health check")
                    ? "健康检查失败"
                    : "部署过程中断";
            timeline.add(TimelineNode.builder()
                    .key(NodeKey.HEALTH_CHECK.name())
                    .title("健康检查")
                    .status(NodeStatus.FAILED.name())
                    .time(latestRecord.getEndedAt())
                    .summary(healthSummary)
                    .actionType(ActionType.DEPLOY_RECORDS.name())
                    .build());
        }

        // 6. Final result node
        NodeStatus finalStatus;
        String finalSummary;
        OffsetDateTime finalTime;
        if (latestRecord == null) {
            finalStatus = NodeStatus.PENDING;
            finalSummary = "尚未执行";
            finalTime = null;
        } else if ("RUNNING".equals(latestRecord.getDeployStatus())) {
            finalStatus = NodeStatus.RUNNING;
            finalSummary = "执行中";
            finalTime = latestRecord.getStartedAt();
        } else if ("SUCCESS".equals(latestRecord.getDeployStatus())) {
            finalStatus = NodeStatus.SUCCESS;
            finalSummary = "发布成功";
            finalTime = latestRecord.getEndedAt();
        } else {
            finalStatus = NodeStatus.FAILED;
            finalSummary = latestRecord.getDeployLog() != null
                    ? deployFailureCategorizer.categorize(latestRecord.getDeployLog())
                    : "发布失败";
            finalTime = latestRecord.getEndedAt();
        }
        timeline.add(TimelineNode.builder()
                .key(NodeKey.FINAL.name())
                .title("最终结果")
                .status(finalStatus.name())
                .time(finalTime)
                .summary(finalSummary)
                .actionType(ActionType.NONE.name())
                .build());

        return timeline;
    }

    private NodeStatus mapDeployStatusToNodeStatus(String deployStatus) {
        if (deployStatus == null) return NodeStatus.PENDING;
        switch (deployStatus) {
            case "RUNNING": return NodeStatus.RUNNING;
            case "SUCCESS": return NodeStatus.SUCCESS;
            case "FAILED": return NodeStatus.FAILED;
            default: return NodeStatus.PENDING;
        }
    }

    private NodeStatus mapPipelineStatusToNodeStatus(String runStatus) {
        if (runStatus == null) return NodeStatus.PENDING;
        switch (runStatus) {
            case "RUNNING": return NodeStatus.RUNNING;
            case "SUCCESS": return NodeStatus.SUCCESS;
            case "FAILED":
            case "TIMEOUT":
            case "CANCELLED": return NodeStatus.FAILED;
            default: return NodeStatus.PENDING;
        }
    }

    private String getDeploySummary(RuntimeDeployRecord record) {
        if (record == null) return "";
        if ("RUNNING".equals(record.getDeployStatus())) return "部署进行中";
        if ("SUCCESS".equals(record.getDeployStatus())) return "部署成功";
        if ("FAILED".equals(record.getDeployStatus())) {
            return record.getDeployLog() != null
                    ? deployFailureCategorizer.categorize(record.getDeployLog())
                    : "部署失败";
        }
        return "";
    }

    private String getPipelineSummary(PipelineRun run) {
        if (run == null) return "";
        if ("RUNNING".equals(run.getRunStatus())) return "流水线执行中";
        if ("SUCCESS".equals(run.getRunStatus())) return "流水线执行成功";
        if ("FAILED".equals(run.getRunStatus()) || "TIMEOUT".equals(run.getRunStatus())) {
            return run.getErrorMessage() != null ? run.getErrorMessage() : "流水线执行失败";
        }
        if ("CANCELLED".equals(run.getRunStatus())) return "流水线已取消";
        return "流水线已触发";
    }
}

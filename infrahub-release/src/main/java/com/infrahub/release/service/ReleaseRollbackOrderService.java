package com.infrahub.release.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.infrahub.base.entity.BaseApp;
import com.infrahub.base.entity.BaseEnv;
import com.infrahub.base.entity.BaseServiceEntity;
import com.infrahub.base.service.BaseAppService;
import com.infrahub.base.service.BaseEnvService;
import com.infrahub.base.service.BaseServiceEntityService;
import com.infrahub.common.error.ErrorCode;
import com.infrahub.common.exception.BizException;
import com.infrahub.infra.ssh.SshExecutionRequest;
import com.infrahub.infra.ssh.SshExecutionResult;
import com.infrahub.infra.ssh.SshExecutorService;
import com.infrahub.release.dto.ReleasePrecheckInstanceResponse;
import com.infrahub.release.dto.ApplicationRollbackRequest;
import com.infrahub.release.dto.AdmissionDecision;
import com.infrahub.release.dto.RollbackOrderDetailResponse;
import com.infrahub.release.dto.ExecutionHistoryItem;
import com.infrahub.release.dto.TimelineNode;
import com.infrahub.release.dto.ExecuteResult;
import com.infrahub.release.entity.AssetHost;
import com.infrahub.release.entity.AssetHostSshConfig;
import com.infrahub.release.entity.ReleaseArtifact;
import com.infrahub.release.entity.ReleaseCurrentVersion;
import com.infrahub.release.entity.ReleaseOrder;
import com.infrahub.release.entity.ReleaseRollbackOrder;
import com.infrahub.release.entity.ReleaseVersion;
import com.infrahub.release.entity.RuntimeDeployRecord;
import com.infrahub.release.entity.RuntimeExecutionUnknown;
import com.infrahub.release.entity.RuntimeServiceInstance;
import com.infrahub.release.entity.ApprovalRequest;
import com.infrahub.release.mapper.ReleaseRollbackOrderMapper;
import com.infrahub.release.mapper.RuntimeDeployRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import com.infrahub.common.context.UserContextHolder;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.infrahub.release.dto.TimelineNode.*;

/**
 * 回滚单服务：负责目标修改、预检、审批前校验与执行的业务判据（状态、发布单、执行器支持、版本归属）。
 *
 * <p>并发协调不在本类实现：目标修改 / 预检 / 审批申请 / 执行四个入口统一向
 * {@link RollbackTargetCoordinator} 索取回滚单行锁，检查与写入都落在锁内。其中**执行**入口
 * （预检与直接执行）还先取 {@code base_service} 行锁，与发布执行认领、实例移除登记共用同一把锁；
 * 加锁顺序固定为 {@code base_service → 回滚单}。本类只保证"先加锁、再读取目标"，
 * 不自行加锁、不构建通用锁机制。
 */
@Slf4j
@Service
public class ReleaseRollbackOrderService extends ServiceImpl<ReleaseRollbackOrderMapper, ReleaseRollbackOrder> {

    private final ReleaseOrderService releaseOrderService;
    private final BaseAppService baseAppService;
    private final ReleaseVersionService releaseVersionService;
    private final ReleaseArtifactService releaseArtifactService;
    private final ReleaseCurrentVersionService releaseCurrentVersionService;
    private final RuntimeDeployRecordService runtimeDeployRecordService;
    private final RuntimeServiceInstanceService runtimeServiceInstanceService;
    private final AssetHostSshConfigService assetHostSshConfigService;
    private final AssetHostService assetHostService;
    private final BaseServiceEntityService baseServiceEntityService;
    private final BaseEnvService baseEnvService;
    private final DeployConfigService deployConfigService;
    private final SecretValueResolver secretValueResolver;
    private final SshExecutorService sshExecutorService;
    private final ReleasePrecheckService releasePrecheckService;
    private final DeployFailureCategorizer deployFailureCategorizer;
    private final ApprovalPolicyService approvalPolicyService;
    private final ApprovalRequestService approvalRequestService;
    private final ReleaseAdmissionService admissionService;
    private final ReleasePrecheckRecordService releasePrecheckRecordService;
    private final RollbackExecutorSupportService rollbackExecutorSupportService;
    /**
     * 回滚目标协调器：目标修改、预检、审批申请与执行共用同一回滚单行锁。
     * 放在构造器末位，保证既有单测的按位置传参不受影响。
     */
    private final RollbackTargetCoordinator rollbackTargetCoordinator;
    /**
     * 结果未知阻断：同服务/环境存在未解除的未知执行时，回滚预检与执行都要拒绝。
     * 放在构造器末位，既有按位置传参的调用只需在末尾补一个参数。
     */
    private final RuntimeExecutionUnknownService runtimeExecutionUnknownService;
    private final RuntimeDeployRecordMapper runtimeDeployRecordMapper;

    @Autowired
    public ReleaseRollbackOrderService(
            @Lazy ReleaseOrderService releaseOrderService,
            BaseAppService baseAppService,
            ReleaseVersionService releaseVersionService,
            ReleaseArtifactService releaseArtifactService,
            ReleaseCurrentVersionService releaseCurrentVersionService,
            RuntimeDeployRecordService runtimeDeployRecordService,
            RuntimeServiceInstanceService runtimeServiceInstanceService,
            AssetHostSshConfigService assetHostSshConfigService,
            AssetHostService assetHostService,
            BaseServiceEntityService baseServiceEntityService,
            BaseEnvService baseEnvService,
            DeployConfigService deployConfigService,
            SecretValueResolver secretValueResolver,
            SshExecutorService sshExecutorService,
            ReleasePrecheckService releasePrecheckService,
            DeployFailureCategorizer deployFailureCategorizer,
            ApprovalPolicyService approvalPolicyService,
            @Lazy ApprovalRequestService approvalRequestService,
            ReleaseAdmissionService admissionService,
            ReleasePrecheckRecordService releasePrecheckRecordService,
            RollbackExecutorSupportService rollbackExecutorSupportService,
            RollbackTargetCoordinator rollbackTargetCoordinator,
            RuntimeExecutionUnknownService runtimeExecutionUnknownService,
            RuntimeDeployRecordMapper runtimeDeployRecordMapper
    ) {
        this.releaseOrderService = releaseOrderService;
        this.baseAppService = baseAppService;
        this.releaseVersionService = releaseVersionService;
        this.releaseArtifactService = releaseArtifactService;
        this.releaseCurrentVersionService = releaseCurrentVersionService;
        this.runtimeDeployRecordService = runtimeDeployRecordService;
        this.runtimeServiceInstanceService = runtimeServiceInstanceService;
        this.assetHostSshConfigService = assetHostSshConfigService;
        this.assetHostService = assetHostService;
        this.baseServiceEntityService = baseServiceEntityService;
        this.baseEnvService = baseEnvService;
        this.deployConfigService = deployConfigService;
        this.secretValueResolver = secretValueResolver;
        this.sshExecutorService = sshExecutorService;
        this.releasePrecheckService = releasePrecheckService;
        this.deployFailureCategorizer = deployFailureCategorizer;
        this.approvalPolicyService = approvalPolicyService;
        this.approvalRequestService = approvalRequestService;
        this.admissionService = admissionService;
        this.releasePrecheckRecordService = releasePrecheckRecordService;
        this.rollbackExecutorSupportService = rollbackExecutorSupportService;
        this.rollbackTargetCoordinator = rollbackTargetCoordinator;
        this.runtimeExecutionUnknownService = runtimeExecutionUnknownService;
        this.runtimeDeployRecordMapper = runtimeDeployRecordMapper;
    }

    public boolean createRollbackOrder(ReleaseRollbackOrder body) {
        body.setServiceId(null);
        body.setEnvId(null);
        body.setTargetInstanceIds(null);
        validateRequiredFields(body);

        ReleaseOrder releaseOrder = releaseOrderService.getById(body.getReleaseOrderId());
        if (releaseOrder == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "回滚单关联的发布单不存在");
        }
        // 回滚边界：非 SSH 发布单不允许创建回滚单（不落库、不产生审批/部署记录）
        rollbackExecutorSupportService.assertRollbackSupported(releaseOrder);

        ReleaseVersion fromVersion = releaseVersionService.getById(body.getFromVersionId());
        if (fromVersion == null || Boolean.TRUE.equals(fromVersion.getIsDeleted())) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "回滚单关联的原版本不存在");
        }
        ReleaseVersion toVersion = releaseVersionService.getById(body.getToVersionId());
        if (toVersion == null || Boolean.TRUE.equals(toVersion.getIsDeleted())) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "回滚单关联的目标版本不存在");
        }
        if (body.getFromVersionId().equals(body.getToVersionId())) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "回滚目标版本不能与原版本相同");
        }
        if (!fromVersion.getAppId().equals(toVersion.getAppId()) || !fromVersion.getAppId().equals(releaseOrder.getAppId())) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "回滚版本与发布单不属于同一应用");
        }

        body.setId(null);
        if (!StringUtils.hasText(body.getStatus())) {
            body.setStatus("CREATED");
        }
        return save(body);
    }

    @Transactional
    public ApplicationRollbackResult rollbackApplication(ApplicationRollbackRequest request) {
        BaseServiceEntity service = baseServiceEntityService.getById(request.serviceId());
        BaseEnv env = baseEnvService.getById(request.envId());
        if (service == null || Boolean.TRUE.equals(service.getIsDeleted()) || !request.appId().equals(service.getAppId())
                || env == null || Boolean.TRUE.equals(env.getIsDeleted())) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "请选择属于该应用的服务和有效的环境");
        }
        ReleaseVersion version = releaseVersionService.getById(request.versionId());
        if (version == null || Boolean.TRUE.equals(version.getIsDeleted()) || !request.appId().equals(version.getAppId())) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "回滚目标版本必须属于当前应用");
        }
        Long buildServiceId = releaseVersionService.findBuildServiceId(version.getBuildPipelineRunId());
        if (buildServiceId != null && !buildServiceId.equals(request.serviceId())) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "回滚目标版本由该应用下的其他服务构建");
        }
        ReleaseArtifact artifact = releaseArtifactService.getById(version.getArtifactId());
        if (artifact == null || (!"JAR".equals(artifact.getArtifactType()) && !"DOCKER_COMPOSE".equals(artifact.getArtifactType()))) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "当前回滚执行器支持已登记的 JAR 或 Docker Compose 清单");
        }
        Set<Long> requested = new HashSet<>(request.instanceIds());
        if (requested.size() != request.instanceIds().size()) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "回滚实例不能重复选择");
        }

        ReleaseRollbackOrder execution = new ReleaseRollbackOrder();
        execution.setRollbackNo("RB-" + UUID.randomUUID());
        execution.setServiceId(request.serviceId());
        execution.setEnvId(request.envId());
        execution.setToVersionId(request.versionId());
        execution.setTargetInstanceIds(requested.stream().sorted().map(String::valueOf).collect(Collectors.joining(",")));
        execution.setStatus("CREATED");
        // Acquire the service lock before INSERT acquires a foreign-key lock on the same row.
        rollbackTargetCoordinator.lockServiceForExecution(request.serviceId());
        if (!save(execution)) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "创建回滚执行记录失败");
        }
        ensureNoRunningOperation(request.serviceId(), request.envId());
        runtimeExecutionUnknownService.assertNoUnresolvedUnknown(request.serviceId(), request.envId());
        List<RuntimeServiceInstance> selected = targetInstances(execution, resolveRollbackScope(execution));
        List<Long> startingVersions = selected.stream().map(RuntimeServiceInstance::getVersionId).distinct().toList();
        if (startingVersions.size() == 1 && startingVersions.get(0) != null) {
            execution.setFromVersionId(startingVersions.get(0));
            updateById(execution);
        }
        List<ReleasePrecheckInstanceResponse> checks = precheckRollbackOrder(execution.getId());
        if (checks.stream().anyMatch(check -> !check.isSuccess())) {
            execution.setStatus("FAILED");
            updateById(execution);
            return new ApplicationRollbackResult(execution.getId(), "FAILED", null, checks);
        }
        ExecuteResult result = executeRollbackOrderWithApproval(execution.getId());
        if (result.getApprovalId() != null) {
            return new ApplicationRollbackResult(execution.getId(), "APPROVING", result.getApprovalId(), checks);
        }
        String status = getRequiredRollbackOrder(execution.getId()).getStatus();
        if ("FAILED".equals(status)) {
            List<RuntimeDeployRecord> records = runtimeDeployRecordService.listByRollbackOrderId(execution.getId());
            if (records.stream().anyMatch(record -> "UNKNOWN".equals(record.getDeployStatus()))) {
                status = "UNKNOWN";
            } else if (records.stream().anyMatch(record -> "SUCCESS".equals(record.getDeployStatus()))) {
                status = "PARTIAL";
            }
        }
        return new ApplicationRollbackResult(execution.getId(), status, null, checks);
    }

    public record ApplicationRollbackResult(Long rollbackId, String status, Long approvalId,
                                            List<ReleasePrecheckInstanceResponse> prechecks) {
    }

    public ApplicationRollbackOptions applicationRollbackOptions() {
        return new ApplicationRollbackOptions(
                baseAppService.lambdaQuery().eq(BaseApp::getIsDeleted, false).list().stream()
                        .map(app -> new RollbackChoice(app.getId(), app.getAppName(), null)).toList(),
                baseEnvService.lambdaQuery().eq(BaseEnv::getIsDeleted, false).list().stream()
                        .map(env -> new RollbackChoice(env.getId(), env.getEnvName(), null)).toList(),
                baseServiceEntityService.lambdaQuery().eq(BaseServiceEntity::getIsDeleted, false).list().stream()
                        .map(service -> new RollbackChoice(service.getId(), service.getServiceName(), service.getAppId())).toList(),
                releaseVersionService.listRollbackVersions().stream()
                        .map(version -> new RollbackVersionChoice(version.getId(), version.getVersionNo(),
                                version.getAppId(), version.getBuildServiceId(),
                                version.getGitCommit(), version.getBuildPipelineRunId())).toList());
    }

    public List<RuntimeServiceInstance> applicationRollbackInstances(Long serviceId, Long envId) {
        return runtimeServiceInstanceService.listByServiceAndEnv(serviceId, envId);
    }

    public record ApplicationRollbackOptions(List<RollbackChoice> apps, List<RollbackChoice> envs,
                                             List<RollbackChoice> services, List<RollbackVersionChoice> versions) {
    }

    public record RollbackChoice(Long id, String name, Long appId) {
    }

    public record RollbackVersionChoice(Long id, String name, Long appId, Long serviceId,
                                        String gitCommit, Long buildPipelineRunId) {
    }

    private ReleaseOrder resolveRollbackScope(ReleaseRollbackOrder rollback) {
        if (rollback.getReleaseOrderId() != null) return releaseOrderService.getById(rollback.getReleaseOrderId());
        BaseServiceEntity service = baseServiceEntityService.getById(rollback.getServiceId());
        if (service == null) return null;
        ReleaseOrder scope = new ReleaseOrder();
        scope.setAppId(service.getAppId());
        scope.setServiceId(service.getId());
        scope.setEnvId(rollback.getEnvId());
        scope.setExecutorType("SSH");
        return scope;
    }

    private List<RuntimeServiceInstance> targetInstances(ReleaseRollbackOrder rollback, ReleaseOrder scope) {
        List<RuntimeServiceInstance> instances = runtimeServiceInstanceService.listByServiceAndEnv(scope.getServiceId(), scope.getEnvId());
        if (rollback.getReleaseOrderId() != null) return instances;
        Set<Long> ids = rollback.selectedInstanceIds();
        List<RuntimeServiceInstance> selected = instances.stream().filter(instance -> ids.contains(instance.getId())).toList();
        if (selected.size() != ids.size()) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "所选实例已变更或不属于该应用环境，请刷新后重试");
        }
        for (RuntimeServiceInstance instance : selected) {
            if (Objects.equals(instance.getVersionId(), rollback.getToVersionId())) {
                throw new BizException(ErrorCode.VALIDATION_ERROR, "所选实例已在目标版本，请刷新并重新选择实例");
            }
            if (instance.getHostId() != null && instances.stream().filter(candidate -> instance.getHostId().equals(candidate.getHostId())).count() > 1) {
                throw new BizException(ErrorCode.VALIDATION_ERROR, "同一主机上的实例共享部署配置，无法安全地按实例回滚");
            }
        }
        return selected;
    }

    /**
     * 更新回滚单目标版本（最小更新面）：仅写 toVersionId，来源发布单、状态、原版本、创建人均不在此处修改。
     * 全部前置检查通过后才执行唯一一次条件更新；任一拒绝分支都不落库、不产生执行副作用。
     *
     * <p>并发协调：先取回滚单行锁并重新读取状态，之后才读取目标版本，所有判断都在锁内完成。
     * 预检、审批申请与执行入口取同一把锁，因此检查与写入不会被并发的预检记录或待审批穿插。
     * 事务由本方法自身的 {@code @Transactional} 提供，锁在方法返回时随事务提交释放。
     */
    @Transactional
    public boolean updateRollbackTarget(Long rollbackOrderId, Long toVersionId) {
        if (toVersionId == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "目标版本不能为空");
        }
        // 锁先于任何目标读取：锁内的状态/预检/审批判定才是权威结果
        rollbackTargetCoordinator.lockRollbackOrder(rollbackOrderId);
        ReleaseRollbackOrder rollbackOrder = getRequiredRollbackOrder(rollbackOrderId);
        if (!"CREATED".equalsIgnoreCase(rollbackOrder.getStatus())) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "仅创建状态（CREATED）的回滚单允许更换回滚目标");
        }
        ReleaseOrder releaseOrder = releaseOrderService.getById(rollbackOrder.getReleaseOrderId());
        if (releaseOrder == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "回滚单关联的发布单不存在");
        }
        // 回滚边界：非 SSH 发布单不允许更换目标（与创建、执行入口同一判据）
        rollbackExecutorSupportService.assertRollbackSupported(releaseOrder);

        // 已有预检记录即形成历史证据：不得让旧预检为新目标背书，拒绝更换并提示新建回滚单
        rollbackTargetCoordinator.assertNoPrecheckEvidence(rollbackOrderId);

        ReleaseVersion toVersion = releaseVersionService.getById(toVersionId);
        if (toVersion == null || Boolean.TRUE.equals(toVersion.getIsDeleted())) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "目标版本不存在或已删除");
        }
        if (toVersion.getAppId() == null || !toVersion.getAppId().equals(releaseOrder.getAppId())) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "目标版本与回滚单不属于同一应用");
        }
        if (toVersionId.equals(rollbackOrder.getFromVersionId())) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "回滚目标版本不能与原版本相同");
        }

        rollbackTargetCoordinator.assertNoPendingApproval(rollbackOrderId);
        ensureNoRunningOperation(releaseOrder.getServiceId(), releaseOrder.getEnvId());

        boolean updated = lambdaUpdate()
                .eq(ReleaseRollbackOrder::getId, rollbackOrderId)
                .eq(ReleaseRollbackOrder::getStatus, "CREATED")
                .set(ReleaseRollbackOrder::getToVersionId, toVersionId)
                .update();
        if (!updated) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "回滚单状态已变化，回滚目标未更新");
        }
        return true;
    }

    /**
     * Preflight validation for rollback order execution.
     * Checks all conditions without modifying any state.
     * Used before creating approval request to ensure the rollback is executable.
     *
     * <p>并发协调：进入即取执行锁组（{@code base_service} → 回滚单），锁先于目标读取；调用方
     * {@link #executeRollbackOrderWithApproval} 自身处于事务中，因此锁会一直持有到审批申请或直接执行结束，
     * 期间目标不会被更换。取 {@code base_service} 行锁使本入口与移除实例登记、发布执行认领互斥。
     */
    private void preflightExecuteRollbackOrder(Long rollbackOrderId) {
        ReleaseRollbackOrder rollbackOrder = rollbackTargetCoordinator.lockForExecution(rollbackOrderId);
        ensureExecutableStatus(rollbackOrder.getStatus());

        ReleaseOrder releaseOrder = resolveRollbackScope(rollbackOrder);
        if (releaseOrder == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "回滚单关联的发布单不存在");
        }
        // 回滚边界：预检阶段即拒绝非 SSH 发布单
        rollbackExecutorSupportService.assertRollbackSupported(releaseOrder);

        BaseServiceEntity service = baseServiceEntityService.getById(releaseOrder.getServiceId());
        BaseEnv env = baseEnvService.getById(releaseOrder.getEnvId());
        if (service == null || env == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "回滚目标的服务或环境不存在");
        }
        ensureNoRunningOperation(releaseOrder.getServiceId(), releaseOrder.getEnvId());
        // 同服务/环境存在未解除的结果未知执行时，回滚会与可能仍在进行的远端动作冲突。
        // lockForExecution 已持有 base_service 行锁，检查与后续审批/执行写入同事务。
        runtimeExecutionUnknownService.assertNoUnresolvedUnknown(
                releaseOrder.getServiceId(), releaseOrder.getEnvId());

        ReleaseVersion targetVersion = releaseVersionService.getById(rollbackOrder.getToVersionId());
        if (targetVersion == null || Boolean.TRUE.equals(targetVersion.getIsDeleted())) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "回滚目标版本不存在");
        }
        ReleaseArtifact artifact = releaseArtifactService.getById(targetVersion.getArtifactId());
        if (artifact == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "回滚目标版本缺少制品信息");
        }

        List<RuntimeServiceInstance> instances = targetInstances(rollbackOrder, releaseOrder);
        if (instances.isEmpty()) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "当前服务在目标环境下没有可回滚的实例");
        }

        Map<String, String> deployConfig = deployConfigService.loadDeployConfig(releaseOrder.getAppId(), releaseOrder.getEnvId());
        String commandTemplate = deployConfig.getOrDefault("rollback.command", deployConfig.get("deploy.command"));
        if (!StringUtils.hasText(commandTemplate)) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "缺少 rollback.command 或 deploy.command 回滚命令配置");
        }
    }

    /**
     * Execute rollback order with approval check.
     * If approval policy is enabled, creates an approval request and returns approvalId.
     * If approval policy is disabled, executes directly and returns directExecuted=true.
     *
     * <p>并发协调：本方法自身开启事务，配合 {@link #preflightExecuteRollbackOrder} 内取得的执行锁组
     * （{@code base_service} → 回滚单），使“读取目标 → 创建待审批 / 置为 RUNNING”整个过程与目标修改、
     * 实例移除登记、发布执行认领互斥；同类内部调用 {@code doExecuteRollbackOrder} 不会新开事务，
     * 锁与状态写入落在同一事务内。
     */
    @Transactional
    public ExecuteResult executeRollbackOrderWithApproval(Long rollbackOrderId) {
        // Preflight validation before any action
        preflightExecuteRollbackOrder(rollbackOrderId);

        // 准入判定
        ReleaseRollbackOrder rollback = getById(rollbackOrderId);
        ReleaseOrder sourceOrder = resolveRollbackScope(rollback);
        // 回滚边界：审批前执行同样校验，非 SSH 发布单不得进入审批/执行流程
        rollbackExecutorSupportService.assertRollbackSupported(sourceOrder);
        // 回滚单风险等级默认继承关联发布单 riskLevel，sourceOrder 或 riskLevel 为空时按 LOW 兜底
        String riskLevel = (sourceOrder != null && sourceOrder.getRiskLevel() != null)
                ? sourceOrder.getRiskLevel() : "LOW";
        Long envId = sourceOrder != null ? sourceOrder.getEnvId() : null;

        AdmissionDecision admission = admissionService.decide(
                envId,
                "EXECUTE_ROLLBACK",
                riskLevel,
                "ROLLBACK_ORDER",
                rollbackOrderId
        );

        if (!admission.isAllowed()) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "回滚准入阻断：" + admission.getBlockedReason());
        }

        // D2 准入策略要求审批时，直接创建审批单（跳过 ApprovalPolicyService 检查）
        if (admission.isApprovalRequired()) {
            Long userId = UserContextHolder.getUserIdOrDefault(1L);
            String payload = "{\"rollbackNo\":\"" + rollback.getRollbackNo() + "\",\"action\":\"EXECUTE_ROLLBACK\"}";
            Long approvalId = approvalRequestService.createRequestUnchecked(
                    ApprovalRequest.BIZ_TYPE_ROLLBACK_ORDER, rollbackOrderId, ApprovalRequest.ACTION_EXECUTE_ROLLBACK,
                    userId, "执行回滚需审批（准入策略）", payload);
            return ExecuteResult.pendingApproval(approvalId);
        }

        // C 阶段 ApprovalPolicyService 要求审批时，使用原有逻辑
        if (approvalPolicyService.isApprovalRequired(ApprovalRequest.BIZ_TYPE_ROLLBACK_ORDER, ApprovalRequest.ACTION_EXECUTE_ROLLBACK)) {
            Long userId = UserContextHolder.getUserIdOrDefault(1L);
            String payload = "{\"rollbackNo\":\"" + rollback.getRollbackNo() + "\",\"action\":\"EXECUTE_ROLLBACK\"}";
            Long approvalId = approvalRequestService.createRequest(
                    ApprovalRequest.BIZ_TYPE_ROLLBACK_ORDER, rollbackOrderId, ApprovalRequest.ACTION_EXECUTE_ROLLBACK,
                    userId, "执行回滚需审批", payload);
            if (approvalId != null) {
                return ExecuteResult.pendingApproval(approvalId);
            }
        }

        // No approval required, execute directly
        doExecuteRollbackOrder(rollbackOrderId);
        return ExecuteResult.directExecuted();
    }

    @Transactional
    public boolean executeRollbackOrder(Long rollbackOrderId) {
        return doExecuteRollbackOrder(rollbackOrderId);
    }

    /**
     * Direct execute rollback order without approval check.
     * Called by approval service after approval is approved.
     *
     * <p>并发协调：先取执行锁组 {@code base_service → release_rollback_order} 并读回锁后整行，
     * 目标版本与状态判定都在锁内完成，随后写入 RUNNING 使 {@code updateRollbackTarget} 的状态判据失效。
     * 审批回调、金丝雀自动回滚都经由此处，与本方法自身的 {@code @Transactional} 一起构成执行入口的事务边界。
     * {@code base_service} 行锁与移除实例登记、发布执行认领共用：移除若先提交，本次读到的实例集合已不含
     * 被删除的行；回滚若先提交（RUNNING 已落库），移除的"存在执行中任务"检查必然看到它并拒绝。
     */
    @Transactional
    public boolean doExecuteRollbackOrder(Long rollbackOrderId) {
        ReleaseRollbackOrder rollbackOrder = rollbackTargetCoordinator.lockForExecution(rollbackOrderId);
        ensureExecutableStatus(rollbackOrder.getStatus());

        ReleaseOrder releaseOrder = resolveRollbackScope(rollbackOrder);
        if (releaseOrder == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "回滚单关联的发布单不存在");
        }
        // 回滚边界：最后一处门禁，位于任何状态写入与 SSH 调用之前（审批回调、金丝雀自动回滚同样经过此处）
        rollbackExecutorSupportService.assertRollbackSupported(releaseOrder);

        BaseServiceEntity service = baseServiceEntityService.getById(releaseOrder.getServiceId());
        BaseEnv env = baseEnvService.getById(releaseOrder.getEnvId());
        if (service == null || env == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "回滚目标的服务或环境不存在");
        }
        ensureNoRunningOperation(releaseOrder.getServiceId(), releaseOrder.getEnvId());
        runtimeExecutionUnknownService.assertNoUnresolvedUnknown(
                releaseOrder.getServiceId(), releaseOrder.getEnvId());

        ReleaseVersion targetVersion = releaseVersionService.getById(rollbackOrder.getToVersionId());
        if (targetVersion == null || Boolean.TRUE.equals(targetVersion.getIsDeleted())) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "回滚目标版本不存在");
        }
        ReleaseArtifact artifact = releaseArtifactService.getById(targetVersion.getArtifactId());
        if (artifact == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "回滚目标版本缺少制品信息");
        }

        List<RuntimeServiceInstance> instances = targetInstances(rollbackOrder, releaseOrder);
        if (instances.isEmpty()) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "当前服务在目标环境下没有可回滚的实例");
        }

        Map<String, String> deployConfig = deployConfigService.loadDeployConfig(releaseOrder.getAppId(), releaseOrder.getEnvId());
        String commandTemplate = deployConfig.getOrDefault("rollback.command", deployConfig.get("deploy.command"));
        if (!StringUtils.hasText(commandTemplate)) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "缺少 rollback.command 或 deploy.command 回滚命令配置");
        }

        List<RollbackSshTarget> targets = instances.stream().map(this::resolveSshTarget).toList();
        rollbackOrder.setStatus("RUNNING");
        updateById(rollbackOrder);
        if (releaseOrder.getId() != null) {
            releaseOrder.setRollbackStatus("ROLLING_BACK");
            releaseOrderService.updateById(releaseOrder);
        }

        List<Long> instanceIds = new ArrayList<>();
        boolean success = true;

        for (RollbackSshTarget target : targets) {
            RuntimeServiceInstance instance = target.instance();
            AssetHost host = target.host();
            AssetHostSshConfig sshConfig = target.sshConfig();

            RuntimeDeployRecord record = new RuntimeDeployRecord();
            record.setReleaseOrderId(releaseOrder.getId());
            record.setRollbackOrderId(rollbackOrder.getId());
            record.setOperationType("ROLLBACK");
            record.setServiceId(releaseOrder.getServiceId());
            record.setEnvId(releaseOrder.getEnvId());
            record.setHostId(instance.getHostId());
            record.setVersionId(targetVersion.getId());
            record.setDeployBatch(1);
            record.setDeployStatus("RUNNING");
            record.setStartedAt(OffsetDateTime.now());
            if (releaseOrder.getId() == null) {
                record.setInstanceId(instance.getId());
                record.setInstanceName(instance.getInstanceName());
                record.setInstanceType(instance.getInstanceType());
                runtimeDeployRecordService.saveWithInstance(record);
            } else {
                runtimeDeployRecordService.save(record);
            }

            ReleasePrecheckResult precheckResult;
            try {
                precheckResult = checkArtifact(host, sshConfig, deployConfig, artifact, rollbackOrder);
            } catch (RuntimeException exception) {
                record.setDeployStatus("FAILED");
                record.setDeployLog("前置检查执行失败: " + exception.getClass().getSimpleName());
                record.setEndedAt(OffsetDateTime.now());
                runtimeDeployRecordService.updateById(record);
                success = false;
                continue;
            }
            if (!precheckResult.isSuccess()) {
                record.setDeployStatus("FAILED");
                record.setDeployLog(precheckResult.fullLog() + "\n\n前置检查失败: " + precheckResult.errorMessage());
                record.setStartedAt(precheckResult.executionResult().getStartedAt());
                record.setEndedAt(precheckResult.executionResult().getEndedAt());
                runtimeDeployRecordService.updateById(record);
                success = false;
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
                                .command(renderCommand(commandTemplate, service, env, targetVersion, artifact, instance, deployConfig))
                                .build()
                );
            } catch (RuntimeException sshFailure) {
                // 记录已在同一事务内创建；异常时无法证明命令没有发出，按结果未知处理并停止后续实例。
                // 不记录原始异常文本（可能含命令/主机细节），只保留类型。
                log.warn("回滚执行中断，结果未知：rollbackOrderId={}, recordId={}, errorType={}",
                        rollbackOrderId, record.getId(), sshFailure.getClass().getSimpleName());
                markRollbackRecordUnknown(record, precheckResult.fullLog() + "\n\n回滚执行中断，结果未知",
                        RuntimeExecutionUnknown.DETECTION_EXCEPTION);
                success = false;
                break;
            }

            record.setDeployLog(precheckResult.fullLog() + "\n\n" + executionResult.fullLog());
            record.setStartedAt(executionResult.getStartedAt());
            if (executionResult.getEndedAt() != null) {
                record.setEndedAt(executionResult.getEndedAt());
            } else if (record.getEndedAt() == null) {
                record.setEndedAt(OffsetDateTime.now());
            }

            switch (executionResult.resolvedEvidence()) {
                case SUCCEEDED -> {
                    record.setDeployStatus("SUCCESS");
                    runtimeDeployRecordService.updateById(record);
                    instanceIds.add(instance.getId());
                }
                case FAILED -> {
                    record.setDeployStatus("FAILED");
                    runtimeDeployRecordService.updateById(record);
                    success = false;
                }
                case UNKNOWN -> {
                    // SSH 255、退出码不可得或超时：远端动作是否生效无法证明，不能写成 FAILED。
                    // UNKNOWN 与未解除登记同事务提交，随后停止后续实例。
                    markRollbackRecordUnknown(record, null, RuntimeExecutionUnknown.DETECTION_EVIDENCE);
                    success = false;
                }
            }
            if (SshExecutionResult.Evidence.UNKNOWN == executionResult.resolvedEvidence()) {
                break;
            }
        }

        if (!success) {
            rollbackOrder.setStatus("FAILED");
            updateById(rollbackOrder);
            if (releaseOrder.getId() == null) {
                if (!instanceIds.isEmpty()) runtimeServiceInstanceService.updateVersion(instanceIds, targetVersion.getId());
            } else {
                releaseOrder.setRollbackStatus("ROLLBACK_FAILED");
                releaseOrderService.updateById(releaseOrder);
            }
            return false;
        }

        rollbackOrder.setStatus("SUCCESS");
        updateById(rollbackOrder);

        if (releaseOrder.getId() != null) {
            releaseOrder.setStatus("ROLLED_BACK");
            releaseOrder.setRollbackStatus("ROLLED_BACK");
            releaseOrder.setRollbackCount((releaseOrder.getRollbackCount() == null ? 0 : releaseOrder.getRollbackCount()) + 1);
            releaseOrder.setLastRollbackAt(OffsetDateTime.now());
            releaseOrderService.updateById(releaseOrder);
        }
        runtimeServiceInstanceService.updateVersion(instanceIds, targetVersion.getId());
        if (releaseOrder.getId() != null || runtimeServiceInstanceService.listByServiceAndEnv(
                releaseOrder.getServiceId(), releaseOrder.getEnvId()).stream().allMatch(instance -> targetVersion.getId().equals(instance.getVersionId()))) {
            releaseCurrentVersionService.markRollbackVersion(releaseOrder.getEnvId(), releaseOrder.getServiceId(), targetVersion.getId(), rollbackOrder.getId());
        }
        return true;
    }

    private RollbackSshTarget resolveSshTarget(RuntimeServiceInstance instance) {
        if (instance.getHostId() == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "存在未绑定主机的服务实例，无法执行回滚");
        }
        AssetHost host = assetHostService.getById(instance.getHostId());
        AssetHostSshConfig sshConfig = assetHostSshConfigService.getEnabledByHostId(instance.getHostId());
        if (host == null || sshConfig == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "回滚目标主机缺少主机信息或 SSH 配置");
        }
        return new RollbackSshTarget(instance, host, sshConfig);
    }

    private record RollbackSshTarget(RuntimeServiceInstance instance, AssetHost host, AssetHostSshConfig sshConfig) {
    }

    /**
     * 回滚单条记录写 UNKNOWN 并在同一事务内登记未解除事实。
     * 调用方必须已持有服务行锁与回滚单行锁，且处于执行事务中。
     */
    private void markRollbackRecordUnknown(RuntimeDeployRecord record, String logText, String detectionSource) {
        String append = StringUtils.hasText(logText)
                ? logText
                : (StringUtils.hasText(record.getDeployLog())
                        ? record.getDeployLog()
                        : "回滚执行结果无法证明，远端动作是否生效未知");
        // 条件更新：只把仍为 RUNNING 的记录改成 UNKNOWN，绝不覆盖并发写入的终态；
        // 日志按追加写入，保留原有证据。
        if (runtimeDeployRecordMapper.markUnknownIfRunning(record.getId(), append) != 1) {
            // 记录已不是 RUNNING：不能覆盖已有结果，但必须留下可阻断事实——否则本单收敛为 FAILED 后，
            // 同一服务/环境就没有任何东西挡住新的发布、回滚或实例移除，而远端动作可能仍在进行。
            log.warn("回滚部署记录 {} 已不是 RUNNING，未写入 UNKNOWN，改登记订单级未知事实", record.getId());
            runtimeExecutionUnknownService.registerOrderLevelIfNoUnknownRecords(
                    record.getReleaseOrderId(), record.getRollbackOrderId(), null, null,
                    detectionSource,
                    "回滚部署记录已不是 RUNNING，无法写入 UNKNOWN；按订单级登记结果未知：rollbackOrderId="
                            + record.getRollbackOrderId());
            return;
        }
        record.setDeployStatus("UNKNOWN");
        if (record.getEndedAt() == null) {
            record.setEndedAt(OffsetDateTime.now());
        }
        record.setDeployLog(append);
        runtimeExecutionUnknownService.registerFromDeployRecord(
                record, detectionSource,
                "回滚执行结果无法证明：rollbackOrderId=" + record.getRollbackOrderId());
    }

    /**
     * 对回滚单执行预检并落库预检记录。
     *
     * <p>并发协调：本方法自身开启事务，进入即取回滚单行锁；目标读取与预检记录写入同处一个事务，
     * 因此并发目标修改会被阻塞到预检记录落库之后，不会出现“预检证据对应 A 目标、回滚单已改成 B 目标”
     * 而旧证据仍被沿用的情形（目标修改侧另有 {@code assertNoPrecheckEvidence} 兜底拒绝）。
     */
    @Transactional
    public List<ReleasePrecheckInstanceResponse> precheckRollbackOrder(Long rollbackOrderId) {
        rollbackTargetCoordinator.lockRollbackOrder(rollbackOrderId);
        ReleaseRollbackOrder rollbackOrder = getRequiredRollbackOrder(rollbackOrderId);
        ensureExecutableStatus(rollbackOrder.getStatus());

        ReleaseOrder releaseOrder = resolveRollbackScope(rollbackOrder);
        if (releaseOrder == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "回滚单关联的发布单不存在");
        }
        // 回滚边界：预检同样只对 SSH 发布单开放，避免非 SSH 发布被按 SSH 命令探测
        rollbackExecutorSupportService.assertRollbackSupported(releaseOrder);

        ReleaseVersion targetVersion = releaseVersionService.getById(rollbackOrder.getToVersionId());
        if (targetVersion == null || Boolean.TRUE.equals(targetVersion.getIsDeleted())) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "回滚目标版本不存在");
        }
        ReleaseArtifact artifact = releaseArtifactService.getById(targetVersion.getArtifactId());
        if (artifact == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "回滚目标版本缺少制品信息");
        }

        List<RuntimeServiceInstance> instances = targetInstances(rollbackOrder, releaseOrder);
        if (instances.isEmpty()) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "当前服务在目标环境下没有可回滚的实例");
        }

        Map<String, String> deployConfig = deployConfigService.loadDeployConfig(releaseOrder.getAppId(), releaseOrder.getEnvId());
        List<ReleasePrecheckInstanceResponse> responses = new ArrayList<>();
        List<ReleasePrecheckRecordService.PrecheckInstanceOutcome> outcomes = new ArrayList<>();
        for (RuntimeServiceInstance instance : instances) {
            if (instance.getHostId() == null) {
                throw new BizException(ErrorCode.VALIDATION_ERROR, "存在未绑定主机的服务实例，无法执行回滚");
            }
            AssetHost host = assetHostService.getById(instance.getHostId());
            AssetHostSshConfig sshConfig = assetHostSshConfigService.getEnabledByHostId(instance.getHostId());
            if (host == null || sshConfig == null) {
                throw new BizException(ErrorCode.VALIDATION_ERROR, "回滚目标主机缺少主机信息或 SSH 配置");
            }

            ReleasePrecheckResult result;
            try {
                result = checkArtifact(host, sshConfig, deployConfig, artifact, rollbackOrder);
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
                    "ROLLBACK_ORDER", rollbackOrder.getId(),
                    releaseOrder.getEnvId(), releaseOrder.getServiceId(), outcomes);
        } catch (Exception e) {
            log.warn("前置检查记录写入失败: bizType=ROLLBACK_ORDER, bizId={}, cause={}",
                    rollbackOrder.getId(), e.getClass().getSimpleName());
        }
        return responses;
    }

    private void ensureExecutableStatus(String status) {
        if (!"CREATED".equalsIgnoreCase(status) && !"APPROVED".equalsIgnoreCase(status)) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "当前回滚单状态不允许执行");
        }
    }

    private ReleasePrecheckResult checkArtifact(AssetHost host, AssetHostSshConfig sshConfig, Map<String, String> deployConfig,
                                                ReleaseArtifact artifact, ReleaseRollbackOrder rollback) {
        DeployRuntimeConfig config = DeployRuntimeConfig.from(deployConfig);
        return rollback.getReleaseOrderId() == null
                ? releasePrecheckService.check(host, sshConfig, config, artifact.getArtifactUri(), artifact.getChecksum())
                : releasePrecheckService.check(host, sshConfig, config, artifact.getArtifactUri());
    }

    private void ensureNoRunningOperation(Long serviceId, Long envId) {
        long runningReleaseCount = releaseOrderService.lambdaQuery()
                .eq(ReleaseOrder::getServiceId, serviceId)
                .eq(ReleaseOrder::getEnvId, envId)
                .eq(ReleaseOrder::getStatus, "RUNNING")
                .count();
        long runningRollbackCount = baseMapper.countRunningByServiceAndEnv(serviceId, envId);
        if (runningReleaseCount > 0 || runningRollbackCount > 0) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "当前服务在该环境下已有执行中的发布或回滚任务");
        }
    }

    private ReleaseRollbackOrder getRequiredRollbackOrder(Long rollbackOrderId) {
        ReleaseRollbackOrder rollbackOrder = getById(rollbackOrderId);
        if (rollbackOrder == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "回滚单不存在");
        }
        return rollbackOrder;
    }

    private static void validateRequiredFields(ReleaseRollbackOrder body) {
        if (!StringUtils.hasText(body.getRollbackNo())) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "回滚单号不能为空");
        }
        if (body.getReleaseOrderId() == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "发布单不能为空");
        }
        if (body.getFromVersionId() == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "原版本不能为空");
        }
        if (body.getToVersionId() == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "目标版本不能为空");
        }
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

    public RollbackOrderDetailResponse getDetail(Long rollbackOrderId) {
        ReleaseRollbackOrder rollbackOrder = getRequiredRollbackOrder(rollbackOrderId);

        RollbackOrderDetailResponse response = new RollbackOrderDetailResponse();
        response.setId(rollbackOrder.getId());
        response.setRollbackNo(rollbackOrder.getRollbackNo());
        response.setStatus(rollbackOrder.getStatus());
        response.setReason(rollbackOrder.getReason());
        if (rollbackOrder.getCreatedAt() != null) {
            response.setCreatedAt(rollbackOrder.getCreatedAt());
        }

        ReleaseOrder releaseOrder = resolveRollbackScope(rollbackOrder);
        if (releaseOrder != null) {
            response.setReleaseOrderNo(releaseOrder.getOrderNo());

            BaseApp app = baseAppService.getById(releaseOrder.getAppId());
            if (app != null) {
                response.setAppName(app.getAppName());
            }

            BaseServiceEntity service = baseServiceEntityService.getById(releaseOrder.getServiceId());
            if (service != null) {
                response.setServiceName(service.getServiceName());
            }

            BaseEnv env = baseEnvService.getById(releaseOrder.getEnvId());
            if (env != null) {
                response.setEnvName(env.getEnvName());
            }
        }

        ReleaseVersion fromVersion = rollbackOrder.getFromVersionId() == null ? null : releaseVersionService.getById(rollbackOrder.getFromVersionId());
        if (fromVersion != null) {
            response.setFromVersionNo(fromVersion.getVersionNo());
        }

        ReleaseVersion toVersion = releaseVersionService.getById(rollbackOrder.getToVersionId());
        if (toVersion != null) {
            response.setToVersionNo(toVersion.getVersionNo());
        }

        RuntimeDeployRecord latestRecord = runtimeDeployRecordService.getLatestByRollbackOrderId(rollbackOrderId);
        if (latestRecord != null) {
            response.setLatestDeployStatus(latestRecord.getDeployStatus());
            if ("FAILED".equals(latestRecord.getDeployStatus()) && latestRecord.getDeployLog() != null) {
                response.setLatestFailureHint(deployFailureCategorizer.categorize(latestRecord.getDeployLog()));
            }
        }
        response.setHasDeployRecords(runtimeDeployRecordService.existsByRollbackOrderId(rollbackOrderId));

        if (releaseOrder != null) {
            ReleaseCurrentVersion currentVersion = releaseCurrentVersionService.lambdaQuery()
                    .eq(ReleaseCurrentVersion::getEnvId, releaseOrder.getEnvId())
                    .eq(ReleaseCurrentVersion::getServiceId, releaseOrder.getServiceId())
                    .one();
            if (currentVersion != null) {
                ReleaseVersion currentVersionEntity = releaseVersionService.getById(currentVersion.getVersionId());
                if (currentVersionEntity != null) {
                    response.setCurrentVersionNo(currentVersionEntity.getVersionNo());
                }
                if (currentVersion.getSourceReleaseOrderId() != null) {
                    ReleaseOrder sourceOrder = releaseOrderService.getById(currentVersion.getSourceReleaseOrderId());
                    if (sourceOrder != null) {
                        response.setCurrentVersionSourceReleaseOrderNo(sourceOrder.getOrderNo());
                    }
                }
                if (currentVersion.getSourceRollbackOrderId() != null) {
                    ReleaseRollbackOrder sourceRollback = getById(currentVersion.getSourceRollbackOrderId());
                    if (sourceRollback != null) {
                        response.setCurrentVersionSourceRollbackOrderNo(sourceRollback.getRollbackNo());
                    }
                }
            }
        }

        // Build timeline
        response.setTimeline(buildRollbackTimeline(rollbackOrder, latestRecord));

        // Build execution history
        response.setExecutionHistory(buildRollbackExecutionHistory(rollbackOrderId));

        return response;
    }

    private List<ExecutionHistoryItem> buildRollbackExecutionHistory(Long rollbackOrderId) {
        List<RuntimeDeployRecord> records = runtimeDeployRecordService.listByRollbackOrderId(rollbackOrderId);
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

    private List<TimelineNode> buildRollbackTimeline(ReleaseRollbackOrder rollbackOrder, RuntimeDeployRecord latestRecord) {
        List<TimelineNode> timeline = new ArrayList<>();

        // 1. Created node
        timeline.add(TimelineNode.builder()
                .key(NodeKey.CREATED.name())
                .title("单据创建")
                .status(NodeStatus.SUCCESS.name())
                .time(rollbackOrder.getCreatedAt())
                .summary("回滚单已创建")
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

        // 3. Rollback deploy node
        if (latestRecord != null && latestRecord.getStartedAt() != null) {
            NodeStatus deployStatus = mapDeployStatusToNodeStatus(latestRecord.getDeployStatus());
            timeline.add(TimelineNode.builder()
                    .key(NodeKey.DEPLOY.name())
                    .title("回滚")
                    .status(deployStatus.name())
                    .time(latestRecord.getStartedAt())
                    .summary(getRollbackDeploySummary(latestRecord))
                    .actionType(ActionType.DEPLOY_RECORDS.name())
                    .build());
        }

        // 4. Health check node - derived from deploy record
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
                    : "回滚过程中断";
            timeline.add(TimelineNode.builder()
                    .key(NodeKey.HEALTH_CHECK.name())
                    .title("健康检查")
                    .status(NodeStatus.FAILED.name())
                    .time(latestRecord.getEndedAt())
                    .summary(healthSummary)
                    .actionType(ActionType.DEPLOY_RECORDS.name())
                    .build());
        }

        // 5. Final result node
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
            finalSummary = "回滚成功";
            finalTime = latestRecord.getEndedAt();
        } else {
            finalStatus = NodeStatus.FAILED;
            finalSummary = latestRecord.getDeployLog() != null
                    ? deployFailureCategorizer.categorize(latestRecord.getDeployLog())
                    : "回滚失败";
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

    private String getRollbackDeploySummary(RuntimeDeployRecord record) {
        if (record == null) return "";
        if ("RUNNING".equals(record.getDeployStatus())) return "回滚进行中";
        if ("SUCCESS".equals(record.getDeployStatus())) return "回滚成功";
        if ("FAILED".equals(record.getDeployStatus())) {
            return record.getDeployLog() != null
                    ? deployFailureCategorizer.categorize(record.getDeployLog())
                    : "回滚失败";
        }
        return "";
    }
}

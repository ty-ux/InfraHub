package com.infrahub.release.pipeline;

import com.infrahub.base.entity.BaseEnv;
import com.infrahub.base.entity.BaseServiceEntity;
import com.infrahub.base.service.BaseEnvService;
import com.infrahub.base.service.BaseServiceEntityService;
import com.infrahub.common.error.ErrorCode;
import com.infrahub.common.exception.BizException;
import com.infrahub.pipeline.entity.PipelineRun;
import com.infrahub.pipeline.entity.PipelineStep;
import com.infrahub.pipeline.entity.PipelineStepRun;
import com.infrahub.pipeline.executor.PipelineStepExecutionResult;
import com.infrahub.pipeline.executor.PipelineStepExecutor;
import com.infrahub.release.entity.ReleaseArtifact;
import com.infrahub.release.entity.ReleaseOrder;
import com.infrahub.release.entity.ReleaseVersion;
import com.infrahub.release.entity.RuntimeServiceInstance;
import com.infrahub.release.service.DeployConfigService;
import com.infrahub.release.service.ReleaseArtifactService;
import com.infrahub.release.service.ReleaseDeployExecutionResult;
import com.infrahub.release.service.ReleaseDeployExecutionService;
import com.infrahub.release.service.ReleaseExecutionClaimService;
import com.infrahub.release.service.ReleaseExecutionRunClaimService;
import com.infrahub.release.service.ReleaseOrderService;
import com.infrahub.release.service.ReleaseVersionService;
import com.infrahub.release.service.RuntimeExecutionUnknownService;
import com.infrahub.release.service.RuntimeServiceInstanceService;
import com.infrahub.release.support.DeployExecutionUnknownException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 流水线里的真实部署步骤。
 *
 * <p><b>执行权从哪来</b>：不再凭“发布单当前是 RUNNING”或“流水线运行是 RUNNING”执行。
 * 步骤先通过 {@link ReleaseExecutionClaimService#claimDirectExecution} 取得一行持久的一次性认领
 * （{@code release_execution_run_claim}，状态 HELD，绑定发布单 + 流水线运行 + 本次步骤尝试），
 * 由唯一索引保证一个发布单只有一次部署执行；取得后它还会在**不持有任何数据库事务**的情况下
 * 跨越整个远端调用，最后无论成败都被释放。
 *
 * <p>取得认领之前，步骤尝试与流水线运行的归属由 {@code ReleaseExecutionClaimService} 从
 * {@code pipeline_step_run} / {@code pipeline_step} / {@code pipeline_run} 读回的行核对，
 * 因此下面三种情形都在远端调用之前被拒绝：同一发布单的并发流水线、同一条运行里的第二个
 * RELEASE_DEPLOY 步骤、以及重复投递的同一次执行。认领只表达“这次执行是谁的”，不表达远端状态。
 *
 * <p><b>认领归属的传递</b>：认领 ID 沿 {@code executeReleaseDeploy} 一路传到部署记录创建处，
 * 每条新记录在插入的同一个短事务里带上它。因此“这次执行产生了哪些部署记录”在库里唯一可查，
 * 不需要事后按发布单回填。
 *
 * <p><b>释放的位置与前提</b>：远端调用返回后立即释放，早于流水线写步骤终态，因此不会出现
 * “步骤终态已写、认领未释放”的中间态。但**只有确认过未知事实已经落成持久化事实之后才释放**：
 * 远端动作可能已经开始而结果无法证明时，先登记未知事实；登记不成功就不释放认领
 * （认领保持 HELD 会继续挡住该发布单与该服务/环境的后续执行）。这条规则保证
 * “释放认领”与“把订单标成普通失败”都不会变成放行新冲突动作的理由。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReleaseDeployPipelineStepExecutor implements PipelineStepExecutor {

    private final ReleaseOrderService releaseOrderService;
    private final BaseServiceEntityService baseServiceEntityService;
    private final BaseEnvService baseEnvService;
    private final ReleaseVersionService releaseVersionService;
    private final ReleaseArtifactService releaseArtifactService;
    private final RuntimeServiceInstanceService runtimeServiceInstanceService;
    private final DeployConfigService deployConfigService;
    private final ReleaseDeployExecutionService releaseDeployExecutionService;
    private final ReleaseExecutionClaimService releaseExecutionClaimService;
    private final ReleaseExecutionRunClaimService releaseExecutionRunClaimService;
    /** 结果无法证明时，把未知事实落成持久化事实；落不成就保持认领 HELD。 */
    private final RuntimeExecutionUnknownService runtimeExecutionUnknownService;
    private final PipelineDeploymentJournal pipelineDeploymentJournal;

    @Override
    public String executorType() {
        return "RELEASE_DEPLOY";
    }

    @Override
    public PipelineStepExecutionResult execute(PipelineRun run, PipelineStep step, PipelineStepRun stepRun) {
        OffsetDateTime startedAt = OffsetDateTime.now();
        if (run == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "缺少流水线运行");
        }
        if (stepRun == null || stepRun.getId() == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR,
                    "真实部署步骤缺少本次步骤尝试的身份，无法取得一次性执行认领，已拒绝");
        }

        if (run.getReleaseId() == null) {
            Long releaseId = pipelineDeploymentJournal.attachDeployment(run);
            run.setReleaseId(releaseId);
            try {
                releaseOrderService.checkAutomaticPipelineAdmission(releaseId);
                releaseExecutionClaimService.claimForExecution(releaseId);
            } catch (RuntimeException exception) {
                releaseOrderService.lambdaUpdate().eq(ReleaseOrder::getId, releaseId)
                        .eq(ReleaseOrder::getStatus, "CREATED").set(ReleaseOrder::getStatus, "FAILED").update();
                throw exception;
            }
        }

        // 一次性执行认领：步骤尝试/运行/发布单的归属核对与写下认领在同一个短事务内完成。
        // 发布单身份由认领服务从 pipeline_run 读回的行推导，不取调用方传入的对象。
        ReleaseExecutionRunClaimService.ExecutionClaimHandle claim =
                releaseExecutionClaimService.claimDirectExecution(run, step, stepRun);

        boolean safeToRelease = false;
        try {
            PipelineStepExecutionResult result = executeWithClaim(claim, startedAt);
            // 正常返回：结果类别与部署记录已在同一批短事务里落库，
            // 未知结果（UNKNOWN / PARTIAL）也已经把未解除事实写成了持久化事实。
            safeToRelease = true;
            return result;
        } catch (DeployExecutionUnknownException unresolved) {
            // 远端动作可能已经开始，但结果无法证明：释放认领之前必须先确保未知事实已经落库。
            safeToRelease = closeUnresolvedExecution(claim, unresolved);
            throw unresolved;
        } catch (RuntimeException failure) {
            // 没有实例开始执行过（部署服务只在开始执行后才把异常包装成 DeployExecutionUnknownException）：
            // 远端确定未开始，不需要未知事实，照常释放认领。
            safeToRelease = true;
            throw failure;
        } finally {
            if (safeToRelease) {
                releaseClaimQuietly(claim);
            } else {
                // 未知事实没有可靠落库：不释放认领。认领会一直 HELD，继续挡住该服务的后续冲突操作，
                // 直到操作人在执行认领页面显式放弃。这一步不声明远端是否还在运行。
                log.error("一次性执行认领 {} 未释放：无法确认未知结果已经登记为持久化事实，"
                                + "认领保持 HELD 并继续阻断该服务/环境的后续冲突操作；"
                                + "请人工核对远端状态并在执行认领页面显式处理",
                        claim.claimId());
            }
        }
    }

    private PipelineStepExecutionResult executeWithClaim(ReleaseExecutionRunClaimService.ExecutionClaimHandle claim,
                                                         OffsetDateTime startedAt) {
        // 用认领携带的发布单身份重新读回订单：执行输入一律取自认领确认过的那一单，
        // 不使用调用方传入的订单对象。
        ReleaseOrder order = releaseOrderService.getById(claim.releaseOrderId());
        if (order == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR,
                    "认领关联的发布单不存在: " + claim.releaseOrderId());
        }

        BaseServiceEntity service = getRequiredService(order.getServiceId());
        BaseEnv env = getRequiredEnv(order.getEnvId());
        ReleaseVersion version = getRequiredVersion(order.getVersionId());
        ReleaseArtifact artifact = getRequiredArtifact(version.getArtifactId());
        List<RuntimeServiceInstance> instances = runtimeServiceInstanceService.listByServiceAndEnv(order.getServiceId(), order.getEnvId());
        if (instances.isEmpty()) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "当前服务在目标环境下没有可发布的实例");
        }
        Map<String, String> deployConfig = deployConfigService.loadDeployConfig(order.getAppId(), order.getEnvId());

        ReleaseDeployExecutionResult result = releaseDeployExecutionService.executeReleaseDeploy(
                order,
                service,
                env,
                version,
                artifact,
                instances,
                deployConfig,
                claim.claimId()
        );
        OffsetDateTime endedAt = OffsetDateTime.now();
        return result.success()
                ? PipelineStepExecutionResult.success(result.message(), startedAt, endedAt)
                : PipelineStepExecutionResult.failure(1, "", result.message(), startedAt, endedAt);
    }

    /**
     * 远端动作可能已经开始、但结果无法证明时，把这次执行的未知事实落成持久化事实。
     *
     * <p>要做的事：把**本次认领产出**且仍停在 RUNNING 的部署记录改成 UNKNOWN 并登记未解除事实；
     * 如果这次认领下没有任何 UNKNOWN 记录，再补一条订单级登记。两者都写不进去时返回 false。
     *
     * <p>返回 false 是调用方**不释放认领**的依据：此时平台既没有“远端已结束”的证据，也没有
     * “这次执行已经收口”的记录，唯一诚实的做法是让这次执行继续处于 HELD，挡住新的冲突操作。
     */
    private boolean closeUnresolvedExecution(ReleaseExecutionRunClaimService.ExecutionClaimHandle claim,
                                             RuntimeException failure) {
        try {
            boolean recorded = runtimeExecutionUnknownService.closeUnresolvedClaimExecution(
                    claim.claimId(),
                    claim.releaseOrderId(),
                    null,
                    null,
                    "流水线部署步骤中断（" + failure.getClass().getSimpleName()
                            + "），远端结果无法证明；禁止自动重试");
            if (!recorded) {
                log.error("一次性执行认领 {} 的未知结果未能登记为持久化事实，认领将保持 HELD：发布单 {}",
                        claim.claimId(), claim.releaseOrderId());
            }
            return recorded;
        } catch (RuntimeException closureFailure) {
            log.error("登记一次性执行认领 {} 的未知结果时失败（{}），认领将保持 HELD：发布单 {}",
                    claim.claimId(), closureFailure.getClass().getSimpleName(), claim.releaseOrderId());
            return false;
        }
    }

    /**
     * 释放失败不覆盖本次执行的原始结果：远端动作已经发生，它的成败由部署记录与步骤结果表达，
     * 不能因为认领释放出错而被改写成另一种结论。释放失败的后果是认领停在 HELD（失败关闭，
     * 会挡住该发布单与该服务/环境的后续执行），这里按错误级别留痕并带上认领 ID，
     * 由操作人在认领页面处理。
     */
    private void releaseClaimQuietly(ReleaseExecutionRunClaimService.ExecutionClaimHandle claim) {
        try {
            releaseExecutionRunClaimService.releaseClaim(claim, "流水线部署步骤结束");
        } catch (RuntimeException releaseFailure) {
            log.error("一次性执行认领 {} 释放失败，认领将保持 HELD 并挡住该发布单的后续执行；"
                            + "远端结果已由部署记录与步骤结果表达，未改写；请人工核对认领页面，errorType={}",
                    claim.claimId(), releaseFailure.getClass().getSimpleName());
        }
    }

    private BaseServiceEntity getRequiredService(Long serviceId) {
        BaseServiceEntity service = baseServiceEntityService.getById(serviceId);
        if (service == null || Boolean.TRUE.equals(service.getIsDeleted())) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "发布单关联的服务不存在");
        }
        return service;
    }

    private BaseEnv getRequiredEnv(Long envId) {
        BaseEnv env = baseEnvService.getById(envId);
        if (env == null || Boolean.TRUE.equals(env.getIsDeleted())) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "发布单关联的环境不存在");
        }
        return env;
    }

    private ReleaseVersion getRequiredVersion(Long versionId) {
        ReleaseVersion version = releaseVersionService.getById(versionId);
        if (version == null || Boolean.TRUE.equals(version.getIsDeleted())) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "发布单关联的版本不存在");
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
}

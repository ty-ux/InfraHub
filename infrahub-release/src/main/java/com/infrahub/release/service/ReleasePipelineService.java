package com.infrahub.release.service;

import com.infrahub.base.mapper.BaseServiceMapper;
import com.infrahub.common.error.ErrorCode;
import com.infrahub.common.exception.BizException;
import com.infrahub.pipeline.dto.RunPipelineResponse;
import com.infrahub.pipeline.entity.PipelineDef;
import com.infrahub.pipeline.entity.PipelineRun;
import com.infrahub.pipeline.event.PipelineCompletedEvent;
import com.infrahub.pipeline.mapper.PipelineRunMapper;
import com.infrahub.pipeline.service.PipelineDefService;
import com.infrahub.pipeline.service.PipelineRunService;
import com.infrahub.release.dto.ExecuteReleasePipelineRequest;
import com.infrahub.release.entity.ReleaseExecutionRunClaim;
import com.infrahub.release.entity.ReleaseOrder;
import com.infrahub.release.entity.RuntimeDeployRecord;
import com.infrahub.release.entity.RuntimeServiceInstance;
import com.infrahub.release.mapper.ReleaseOrderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReleasePipelineService {

    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_SUCCESS = "SUCCESS";

    private final ReleaseOrderService releaseOrderService;
    private final ReleaseOrderMapper releaseOrderMapper;
    private final PipelineDefService pipelineDefService;
    private final PipelineRunService pipelineRunService;
    private final PipelineRunMapper pipelineRunMapper;
    private final RuntimeServiceInstanceService runtimeServiceInstanceService;
    private final RuntimeDeployRecordService runtimeDeployRecordService;
    private final ReleaseCurrentVersionService releaseCurrentVersionService;
    private final ReleaseAuditLogService releaseAuditLogService;
    private final BaseServiceMapper baseServiceMapper;
    /**
     * 未解除的结果未知事实：成功回调必须与它互斥，不能把有未知事实的发布写成成功。
     */
    private final RuntimeExecutionUnknownService runtimeExecutionUnknownService;
    /**
     * 部署认领：核对回调所属的流水线运行是不是真正执行过这次部署的那一次。
     */
    private final ReleaseExecutionRunClaimService releaseExecutionRunClaimService;
    /**
     * 流水线触发不再自行改状态，改用与直接发布/审批回调相同的认领协调：
     * 同一发布单并发触发时最多一个请求拿到 RUNNING，服务/环境互斥规则由认领统一判定。
     */
    private final ReleaseExecutionClaimService releaseExecutionClaimService;
    /**
     * 本次执行的实例级证据：成功方向只按它认定的实例集合更新版本，
     * 不按“服务 + 环境”重新枚举（会带上执行期间新增、实际并未部署的实例）。
     */
    private final ReleaseDeployEvidenceService releaseDeployEvidenceService;

    @Transactional
    public RunPipelineResponse executeReleaseViaPipeline(Long releaseOrderId, ExecuteReleasePipelineRequest request) {
        if (request == null || request.getPipelineId() == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "流水线 ID 不能为空");
        }

        ReleaseOrder order = getRequiredOrder(releaseOrderId);
        ensureExecutableStatus(order.getStatus());

        PipelineDef pipeline = pipelineDefService.getById(request.getPipelineId());
        if (pipeline == null || Boolean.FALSE.equals(pipeline.getEnabled()) || Boolean.TRUE.equals(pipeline.getIsDeleted())) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "流水线不存在或未启用");
        }
        if (!order.getAppId().equals(pipeline.getAppId())) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "发布单与流水线所属应用不一致");
        }

        Long envId = request.getEnvId() == null ? order.getEnvId() : request.getEnvId();
        Long versionId = request.getVersionId() == null ? order.getVersionId() : request.getVersionId();
        if (!order.getEnvId().equals(envId) || !order.getVersionId().equals(versionId)) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "流水线发布必须使用发布单自身环境和版本");
        }

        List<RuntimeServiceInstance> instances = runtimeServiceInstanceService.listByServiceAndEnv(order.getServiceId(), order.getEnvId());
        if (instances.isEmpty()) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "当前服务在目标环境下没有可发布的实例");
        }

        // 统一认领：与直接发布、审批回调同一套数据库短事务认领规则（服务/环境互斥 + 条件更新 RUNNING）
        releaseExecutionClaimService.claimForExecution(releaseOrderId);

        try {
            PipelineRun run = pipelineRunService.startPipeline(request.getPipelineId(), envId, versionId, releaseOrderId);
            releaseAuditLogService.recordPipelineReleaseTriggered(releaseOrderId, request.getPipelineId(), run.getId());
            return RunPipelineResponse.builder()
                    .pipelineRunId(run.getId())
                    .runStatus(run.getRunStatus())
                    .build();
        } catch (RuntimeException exception) {
            // 只收敛仍为 RUNNING 的订单，不用无条件整行状态覆盖已经落下的终态。
            // 注意：本方法自身是事务方法，这里抛出的异常会让本次触发（含认领）整体回滚，
            // 订单退回认领前的状态，流水线任务也不会提交（提交注册在 afterCommit 上）；
            // 因此这条 FAILED 只在事务真的提交时才会生效。
            if (releaseOrderMapper.markFailedIfRunning(releaseOrderId) == 1) {
                releaseAuditLogService.recordPipelineReleaseFailed(releaseOrderId, null, exception.getMessage());
            } else {
                releaseAuditLogService.recordPipelineReleaseTerminalIgnored(
                        releaseOrderId, null, "FAILED", null, exception.getMessage());
            }
            throw exception;
        }
    }

    /**
     * 流水线结束回调：把一次流水线执行的最终结果收敛到发布单。
     *
     * <p><b>为什么不能只按“订单是否 RUNNING”决定</b>：这个事件是外部事实的投递，可能迟到、可能重复、
     * 也可能来自一条早已被取代的运行。仅凭订单状态收敛，会把两种情况写成成功：
     * 一种是一条旧的运行在人工恢复之后才报 SUCCESS，另一种是同一服务/环境里还有未解除的结果未知事实
     * （另一次执行可能仍在远端进行）。前者用“订单是否仍 RUNNING”挡住了，后者没有。
     *
     * <p>因此本方法在一个短事务内按固定锁序 {@code base_service → release_order → 认领行} 重新读取当前事实，
     * 并依次核对：本次事件所属的流水线运行确实属于该发布单、确实是执行过部署的那一次（有部署认领时以认领
     * 的持有者为唯一可信来源）、订单仍在 RUNNING、成功结局不伴随任何未收口的执行事实。任一不成立即
     * **不采纳**，只写审计（{@code PIPELINE_RELEASE_TERMINAL_IGNORED}，{@code applied=false}），
     * 未解除事实保持未解除，不自动放行，也不自动解除。
     *
     * <p><b>成功方向的证据要求</b>：认领状态 {@code RELEASED} 只表示执行线程结束了，不表示部署成功——
     * 允许失败的步骤、被跳过的部署步骤、部署失败后仍走完的流水线都会留下这样的认领。因此成功回调还要求
     * 认领产出的部署记录非空且全部为 SUCCESS；拿不到这份持久事实就不采纳成功，不改用文案或默认值推断。
     * 反过来说，平台不会仅凭“流水线整体成功”或“认领已 RELEASED”宣布部署成功。
     *
     * <p>幂等：同一回调重复投递时，第一次已经把订单写成终态，后续投递都会在“订单仍在 RUNNING”这一步
     * 不成立而不被采纳，只多写一条不采纳审计，不会重复写终态、不会重复更新实例版本或当前版本。
     *
     * <p>本方法只做短事务协调：不持锁等待远端、不连接目标主机、不重试、不回滚。
     * 它**不**判断远端是否已停止，任何“结果未知”的结论仍然只能由人工核对给出。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handlePipelineCompleted(PipelineCompletedEvent event) {
        if (event.releaseId() == null) {
            return;
        }
        ReleaseOrder snapshot = releaseOrderService.getById(event.releaseId());
        if (snapshot == null) {
            return;
        }
        if (snapshot.getServiceId() == null) {
            log.warn("发布单 {} 缺少服务归属，流水线终态无法核对未知事实，不采纳", snapshot.getId());
            releaseAuditLogService.recordPipelineReleaseTerminalIgnored(snapshot.getId(), event.pipelineRunId(),
                    event.runStatus(), snapshot.getStatus(), "发布单缺少服务归属，未采纳本次流水线结果");
            return;
        }

        // 锁序与发布认领、认领释放、人工恢复完全一致：base_service → release_order。
        // 取锁后必须用 selectForUpdate 读回当前整行，不能用加锁前的一级缓存快照做判定。
        if (baseServiceMapper.lockById(snapshot.getServiceId()) == null) {
            log.warn("发布单 {} 关联的服务不存在，流水线终态不采纳", snapshot.getId());
            releaseAuditLogService.recordPipelineReleaseTerminalIgnored(snapshot.getId(), event.pipelineRunId(),
                    event.runStatus(), snapshot.getStatus(), "发布单关联的服务不存在，未采纳本次流水线结果");
            return;
        }
        ReleaseOrder order = releaseOrderMapper.selectForUpdate(event.releaseId());
        if (order == null) {
            return;
        }

        // (a) 可信运行归属：事件必须来自属于该发布单的、真正执行过部署的那一次运行
        String ownershipRejection = rejectUntrustworthyRun(order, event.pipelineRunId(), event.runStatus());
        if (ownershipRejection != null) {
            log.warn("发布单 {} 的流水线终态归属不可信，不采纳：{}", order.getId(), ownershipRejection);
            releaseAuditLogService.recordPipelineReleaseTerminalIgnored(order.getId(), event.pipelineRunId(),
                    event.runStatus(), order.getStatus(), ownershipRejection);
            return;
        }

        // (b) 订单必须仍在执行中：人工恢复/回滚已把它收敛为终态时，迟到的流水线结果不能覆盖
        if (!STATUS_RUNNING.equalsIgnoreCase(order.getStatus())) {
            log.warn("发布单 {} 不在执行中，迟到的流水线结果未采纳，仅记录审计", order.getId());
            releaseAuditLogService.recordPipelineReleaseTerminalIgnored(order.getId(), event.pipelineRunId(),
                    event.runStatus(), order.getStatus(), event.errorMessage());
            return;
        }

        boolean success = "SUCCESS".equalsIgnoreCase(event.runStatus());

        // (c) 成功结局不得与未收口的执行事实并存。流水线自述成功不是“远端状态安全”的证据：
        // 同一服务/环境里另一次执行可能仍未收口（未解除的未知事实，或一行没有正常释放、
        // 也没有被操作人放弃的 HELD 认领）。此时不收敛终态（订单保持 RUNNING，继续挡住新操作），
        // 只写审计说明实际事实，由人工核对后走恢复/解除路径。
        if (success && runtimeExecutionUnknownService.hasUnresolvedUnknown(order.getServiceId(), order.getEnvId())) {
            log.warn("发布单 {} 收到流水线成功结果，但同一服务/环境存在未收口的执行事实（未解除的未知事实"
                    + "或未释放的执行认领），不采纳成功", order.getId());
            releaseAuditLogService.recordPipelineReleaseTerminalIgnored(order.getId(), event.pipelineRunId(),
                    event.runStatus(), order.getStatus(),
                    "同一服务/环境存在未收口的执行事实（未解除的结果未知执行或未释放的执行认领）；"
                            + "平台不据此判断远端状态，订单保持执行中待人工核对");
            return;
        }

        if (success) {
            ReleaseExecutionRunClaim claim = releaseExecutionRunClaimService.findDeployClaim(order.getId());
            if (claim == null) {
                // rejectUntrustworthyRun 对成功方向已要求存在认领；这里只是兜底，仍然失败关闭。
                log.warn("发布单 {} 没有部署认领，无法证明部署落在哪些实例上，不采纳成功", order.getId());
                releaseAuditLogService.recordPipelineReleaseEvidenceRejected(order.getId(), event.pipelineRunId(), null,
                        "缺少部署认领，无法证明这次运行真正执行过部署");
                return;
            }
            // (d) 实例级证据：这次执行自己产出的部署记录 + 创建时写下的实例归属，
            // 实例行按 ID 升序加锁读回并比对身份。证据不足一律不采纳成功，订单保持执行中。
            ReleaseDeployEvidenceService.DeployEvidence evidence = releaseDeployEvidenceService
                    .collectUnderLock(claim.getId(), order.getServiceId(), order.getEnvId());
            if (!evidence.provable()) {
                log.warn("发布单 {} 的成功结果缺少可证明的实例归属，不采纳：{}", order.getId(),
                        evidence.rejectionReason());
                releaseAuditLogService.recordPipelineReleaseEvidenceRejected(order.getId(), event.pipelineRunId(),
                        claim.getId(), evidence.rejectionReason());
                return;
            }

            if (releaseOrderMapper.markSuccessIfRunning(order.getId()) != 1) {
                log.warn("发布单 {} 状态在核对期间变化，迟到的流水线成功结果未采纳，仅记录审计", order.getId());
                releaseAuditLogService.recordPipelineReleaseTerminalIgnored(order.getId(), event.pipelineRunId(),
                        event.runStatus(), order.getStatus(), event.errorMessage());
                return;
            }
            // 只更新被这次执行证明部署成功的实例：执行期间新增的实例不在其中，保持原版本。
            runtimeServiceInstanceService.updateVersion(evidence.provenInstanceIds(), order.getVersionId());
            releaseCurrentVersionService.markReleaseVersion(order.getEnvId(), order.getServiceId(), order.getVersionId(), order.getId());
            releaseAuditLogService.recordPipelineReleaseSuccess(order.getId(), event.pipelineRunId(), claim.getId(),
                    evidence.provenInstanceIds(), evidence.currentInstanceIdsNotProven());
            return;
        }

        // 失败方向不宣称安全，条件更新即可；同时把实际残留事实写进审计（不改写任何状态）
        if (releaseOrderMapper.markFailedIfRunning(order.getId()) != 1) {
            log.warn("发布单 {} 不在执行中，迟到的流水线失败结果未采纳，仅记录审计", order.getId());
            releaseAuditLogService.recordPipelineReleaseTerminalIgnored(order.getId(), event.pipelineRunId(),
                    event.runStatus(), order.getStatus(), event.errorMessage());
            return;
        }
        long stillRunningRecords = runtimeDeployRecordService.listByReleaseOrderId(order.getId()).stream()
                .filter(record -> STATUS_RUNNING.equalsIgnoreCase(record.getDeployStatus()))
                .count();
        releaseAuditLogService.recordPipelineReleaseFailed(order.getId(), event.pipelineRunId(), event.errorMessage(),
                stillRunningRecords,
                runtimeExecutionUnknownService.hasUnresolvedUnknown(order.getServiceId(), order.getEnvId()));
    }

    /**
     * 核对事件所属的流水线运行是否可信。
     *
     * <p>规则：运行必须存在、必须属于该发布单、必须是终态；并且当该发布单存在部署认领行时，
     * **只有认领的持有者**才是可信来源。认领是一次性凭证，一个发布单最多一行，因此
     * “这条运行执行过这次部署”在数据库里有唯一答案：不是持有者的运行，其终态一律不采纳。
     *
     * <p>没有认领行时（流水线里没有真实部署步骤，或 V32 未应用）退回既有的“运行属于该发布单”判定，
     * 不因为缺少认领行就把整条路径封死。这个残留面已知：两条同时为 RUNNING 的残留运行之间，
     * 只有认领行能区分谁真正执行过部署。
     *
     * @return null 表示可信；否则返回不可信的原因（用于审计，不包含命令、输出或凭据）
     */
    private String rejectUntrustworthyRun(ReleaseOrder order, Long pipelineRunId, String eventStatus) {
        if (pipelineRunId == null) {
            return "流水线终态缺少流水线运行标识，无法确认归属，未采纳";
        }
        PipelineRun run = pipelineRunMapper.selectById(pipelineRunId);
        if (run == null) {
            return "流水线运行不存在，未采纳: " + pipelineRunId;
        }
        if (!order.getId().equals(run.getReleaseId())) {
            return "流水线运行不属于该发布单，未采纳: " + pipelineRunId;
        }
        if (eventStatus == null || !eventStatus.equalsIgnoreCase(run.getRunStatus())) {
            return "流水线事件状态与运行记录不一致，未采纳: " + pipelineRunId;
        }
        ReleaseExecutionRunClaim claim = releaseExecutionRunClaimService.findDeployClaim(order.getId());
        if (claim != null && !pipelineRunId.equals(claim.getPipelineRunId())) {
            return "流水线运行不是该发布单部署认领的持有者（认领 " + claim.getId()
                    + " 属于运行 " + claim.getPipelineRunId() + "），未采纳";
        }
        if (!STATUS_SUCCESS.equalsIgnoreCase(eventStatus)) {
            return null;
        }
        // 成功方向的判定必须落到“部署本身成功了”这份持久事实上，认领状态只是必要条件。
        if (claim == null) {
            return "流水线成功缺少部署认领，无法证明这次运行真正执行过部署，未采纳: " + pipelineRunId;
        }
        if (!"RELEASED".equals(claim.getStatus())) {
            return "部署认领 " + claim.getId() + " 仍为 " + claim.getStatus() + "（未正常收口），"
                    + "流水线成功未采纳: " + pipelineRunId;
        }
        if (!"COMPLETED".equals(claim.getReleaseOutcome())) {
            return "部署认领 " + claim.getId() + " 的释放结果是 " + claim.getReleaseOutcome()
                    + "（不是正常收口），流水线成功未采纳: " + pipelineRunId;
        }
        // 认领正常释放只说明“执行线程结束了”，**不**说明部署成功：允许失败的步骤
        // （continueOnFail）、被跳过而没有执行的部署步骤、以及部署失败后仍走完的流水线，
        // 都会留下 RELEASED + COMPLETED 的认领。唯一能证明这次部署结果的事实是
        // 认领产出的部署记录本身，因此这里逐条核对，缺记录一律不采纳。
        List<RuntimeDeployRecord> claimedRecords =
                runtimeDeployRecordService.listByExecutionClaimId(claim.getId());
        if (claimedRecords.isEmpty()) {
            return "部署认领 " + claim.getId() + " 没有产出任何部署记录，无法证明部署成功，未采纳: " + pipelineRunId;
        }
        List<String> notSucceeded = claimedRecords.stream()
                .filter(record -> !STATUS_SUCCESS.equalsIgnoreCase(record.getDeployStatus()))
                .map(record -> record.getId() + "(" + record.getDeployStatus() + ")")
                .toList();
        if (!notSucceeded.isEmpty()) {
            return "部署认领 " + claim.getId() + " 产出的部署记录并非全部成功，不采纳流水线成功: "
                    + String.join(", ", notSucceeded);
        }
        return null;
    }

    private ReleaseOrder getRequiredOrder(Long releaseOrderId) {
        ReleaseOrder order = releaseOrderService.getById(releaseOrderId);
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
}

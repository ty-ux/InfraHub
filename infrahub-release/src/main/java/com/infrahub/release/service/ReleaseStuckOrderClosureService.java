package com.infrahub.release.service;

import com.infrahub.base.mapper.BaseServiceMapper;
import com.infrahub.common.error.ErrorCode;
import com.infrahub.common.exception.BizException;
import com.infrahub.pipeline.entity.PipelineRun;
import com.infrahub.pipeline.mapper.PipelineRunMapper;
import com.infrahub.release.dto.ReleaseClosureRouteView;
import com.infrahub.release.dto.StuckReleaseClosurePreviewResponse;
import com.infrahub.release.dto.StuckReleaseClosureResponse;
import com.infrahub.release.entity.ReleaseExecutionRunClaim;
import com.infrahub.release.entity.ReleaseOrder;
import com.infrahub.release.entity.RuntimeDeployRecord;
import com.infrahub.release.entity.RuntimeDeployRecordInstance;
import com.infrahub.release.mapper.ReleaseCanaryPlanMapper;
import com.infrahub.release.mapper.ReleaseOrderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 卡住发布单的受控人工收口。
 *
 * <p>针对的状态是：**发布单仍是 RUNNING，但认领已经释放、部署记录都已是终态、流水线回调没有到**
 * （或到了但没被采纳）。既有的人工恢复路径只处理“结果未知”（仍有 RUNNING 记录或已有 UNKNOWN 记录），
 * 对这种“记录全是终态却没人收口订单”的中间态明确拒绝，于是订单卡在 RUNNING：既不能成功也不能失败，
 * 同一服务/环境还被“执行中”挡住。本服务给出结束它的受控路径。
 *
 * <p><b>三条纪律</b>：
 * <ol>
 *   <li>不推断远端：超时、进程无响应都不是“远端已停止”的证据；平台不连接远端、不重试、不回滚。</li>
 *   <li>不伪造成功：{@code SUCCEEDED} 只在本平台能证明“这次执行产出的部署记录全部成功、
 *       且每条记录的实例归属都在本库可验证”时被接受；实例被移除或被改绑一律拒绝。</li>
 *   <li>不顺手解除阻断：收口订单不解除任何未解除的未知事实。{@code FAILED} 之后如果仍有未知事实，
 *       响应里明确写 {@code blockingRemains=true}。</li>
 * </ol>
 *
 * <p><b>锁序</b>：{@code base_service → release_order → runtime_service_instance（ID 升序）}，
 * 与流水线成功回调、人工恢复、灰度批次完全一致。
 *
 * <p><b>重复操作</b>：订单状态不是 RUNNING 时直接拒绝（不覆盖终态）。判定用的是
 * {@code markSuccessIfRunning / markFailedIfRunning} 这类条件更新，并发或重复调用最多有一个生效。
 *
 * <p><b>迟到的流水线回调</b>：收口把订单写成终态之后，回调进入成功/失败分支时都会因为
 * “订单不在执行中”而只写 {@code PIPELINE_RELEASE_TERMINAL_IGNORED} 审计，不会覆盖人工结论。
 *
 * <p><b>范围</b>：只处理发布单，且要求该单没有未收敛的灰度计划 —— 灰度发布的收口由灰度计划与
 * 批次自己的终态语义决定，人工收口不去替它下结论。
 *
 * <p><b>允许的路径与拒绝文案同源</b>：{@link ReleaseClosureRouteAdvisor} 把“人工收口”与
 * “恢复为结果未知”“逐条解除未知事实”“放弃 HELD 认领”放在同一份输入上各判一遍。
 * 预览与提交用同一份文案（{@code gateMessages}），且**只有当另一条入口现在确实可用时**，
 * 拒绝文案才会把它写成引导。存在下列**平台内没有入口**的死交，页面必须如实说明而不是换一句引导：
 * <ul>
 *   <li>没有认领行（历史直接发布、V32 应用之前的数据）：既不满足成功也不满足失败的准入；</li>
 *   <li>认领已放弃（{@code RELEASED + ABANDONED}）且部署记录都已是确定结果：收口要求认领正常收口，
 *       恢复又要求存在 RUNNING 或 UNKNOWN 记录，两边都不接受；</li>
 *   <li>发布单没有任何部署记录：恢复以“不能证明远端操作未开始”拒绝；</li>
 *   <li>V31 未应用：登记、恢复、解除三个入口都按失败关闭拒绝；V32 未应用：不存在认领行，
 *       也就没有“放弃认领”这个动作。</li>
 * </ul>
 * 平台不提供绕过这些边界的强收口入口：那会把“无法证明”写成“已确认”。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReleaseStuckOrderClosureService {

    public static final String OUTCOME_SUCCEEDED = "SUCCEEDED";
    public static final String OUTCOME_FAILED = "FAILED";

    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_FAILED = "FAILED";
    private static final String STATUS_UNKNOWN = "UNKNOWN";
    private static final String CLAIM_RELEASED = "RELEASED";
    private static final String CLAIM_COMPLETED = "COMPLETED";

    private static final String DISCLAIMER =
            "平台未连接远端、未重试、未回滚，也不从超时或进程无响应推断远端已停止；"
                    + "本页面显示的结论是操作人的判断，平台只核对了本地持久事实";

    private final ReleaseOrderMapper releaseOrderMapper;
    private final BaseServiceMapper baseServiceMapper;
    private final PipelineRunMapper pipelineRunMapper;
    private final ReleaseCanaryPlanMapper releaseCanaryPlanMapper;
    private final RuntimeDeployRecordService runtimeDeployRecordService;
    private final RuntimeDeployRecordInstanceService runtimeDeployRecordInstanceService;
    private final RuntimeServiceInstanceService runtimeServiceInstanceService;
    private final ReleaseExecutionRunClaimService releaseExecutionRunClaimService;
    private final RuntimeExecutionUnknownService runtimeExecutionUnknownService;
    private final ReleaseCurrentVersionService releaseCurrentVersionService;
    private final ReleaseDeployEvidenceService releaseDeployEvidenceService;
    private final ReleaseAuditLogService releaseAuditLogService;
    /** 各条可选路径的准入判定与统一文案，预览与提交共用。 */
    private final ReleaseClosureRouteAdvisor releaseClosureRouteAdvisor;
    /** 合并台账可用性：订单级事实是否吸收了别的批次登记，决定解除是否需要显式确认。 */
    private final RuntimeExecutionUnknownMergeService runtimeExecutionUnknownMergeService;

    // ------------------------------------------------------------------
    // 收口前对账（只读）
    // ------------------------------------------------------------------

    /**
     * 只读对账：订单、认领、流水线运行、部署记录、未知事实各是什么状态，以及两个人工作结论的准入判定。
     *
     * <p>不加锁：结果是一个快照，可能被并发操作改变。收口时会在服务行锁与订单行锁内重新核对一遍，
     * 因此预览结论与最终是否被接受可能不同 —— 页面必须把它当“核对材料”，不是“许可证”。
     */
    public StuckReleaseClosurePreviewResponse preview(Long releaseOrderId) {
        ReleaseOrder order = requireOrder(releaseOrderId);
        ReleaseExecutionRunClaim claim = releaseExecutionRunClaimService.findDeployClaim(order.getId());
        StuckReleaseClosurePreviewResponse response = new StuckReleaseClosurePreviewResponse();
        response.setReleaseOrderId(order.getId());
        response.setOrderStatus(order.getStatus());
        response.setServiceId(order.getServiceId());
        response.setEnvId(order.getEnvId());
        response.setVersionId(order.getVersionId());
        response.setDisclaimer(DISCLAIMER);

        List<RuntimeDeployRecord> records = collectRecords(order, claim);
        fillRecords(response, records);
        fillClaimAndRun(response, claim);
        fillInstanceEvidence(response, order, claim);
        fillTableAvailability(response);
        fillRouteAssessment(response, order, claim, records);
        return response;
    }

    // ------------------------------------------------------------------
    // 收口（写）
    // ------------------------------------------------------------------

    /**
     * 人工收口一个卡住的发布单。
     *
     * <p>{@code outcome=SUCCEEDED} 要求：订单 RUNNING；存在认领且已 RELEASED + COMPLETED；
     * 本次执行的部署记录全部成功且实例归属可证明；本发布单与服务/环境下都没有未解除的未知事实；
     * 服务/环境下没有 HELD 认领；没有未收敛的灰度计划。满足时按实例级证据更新版本，
     * 与流水线成功回调走同一套判定与同一份事实。
     *
     * <p>{@code outcome=FAILED} 要求：订单 RUNNING；**存在认领且已正常 RELEASED + COMPLETED**；
     * 没有 HELD 认领；没有仍为 RUNNING 的部署记录（还有执行中的记录时请改用“恢复为结果未知”，
     * 那条路径会留下未知事实）；没有未收敛的灰度计划。未解除的未知事实不阻止收口，
     * 但也不会被解除：响应会标明阻断仍然存在。
     *
     * <p><b>为什么失败方向也要求认领正常收口</b>：没有认领行就无法排除“直接发布仍在执行”，
     * 而“放弃认领”只表达平台不再认为这次执行在飞，**不是**远端已停止的证据。放宽这一条
     * 等于用一句无法核实的话换取服务/环境被放行，因此不提供该降级。
     *
     * <p><b>预览与提交同源</b>：拒绝文案来自 {@link ReleaseClosureRouteAdvisor} 的同一份
     * {@code gateMessages}，因此预览里看到的拒绝原因与提交时的报错逐字一致；而指向
     * “恢复为结果未知”的引导只在那个入口现在确实可用时才会出现（否则如实说明它为什么不可用）。
     * 所有判定用的是**锁后**读回的订单行与锁后重算的事实，预览的结论不构成许可。
     */
    @Transactional
    public StuckReleaseClosureResponse close(Long releaseOrderId, String outcome, String note) {
        String normalizedOutcome = requireOutcome(outcome);
        String normalizedNote = requireNote(note);

        ReleaseOrder snapshot = requireOrder(releaseOrderId);
        if (snapshot.getServiceId() == null
                || baseServiceMapper.lockById(snapshot.getServiceId()) == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "发布单关联的服务不存在，已拒绝收口");
        }
        ReleaseOrder order = releaseOrderMapper.selectForUpdate(releaseOrderId);
        if (order == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "发布单不存在: " + releaseOrderId);
        }

        ReleaseExecutionRunClaim claim = releaseExecutionRunClaimService.findDeployClaim(order.getId());
        List<RuntimeDeployRecord> records = collectRecords(order, claim);
        // 只读证据用于判定与文案；成功方向随后会用 collectUnderLock 在同一事务内重新核对并锁定实例行。
        ReleaseDeployEvidenceService.DeployEvidence evidence = claim == null ? null
                : releaseDeployEvidenceService.collect(claim.getId(), order.getServiceId(), order.getEnvId());
        ReleaseClosureRouteAdvisor.RouteAssessment assessment = assessRoutes(order, claim, records, evidence);
        List<Long> heldClaimIds = releaseExecutionRunClaimService
                .listHeldClaimIdsForServiceAndEnv(order.getServiceId(), order.getEnvId());

        rejectWith(assessment, ReleaseClosureRouteAdvisor.GATE_ORDER_NOT_RUNNING,
                "只有 RUNNING 发布单允许人工收口；当前状态: " + order.getStatus()
                        + "（重复收口不会覆盖已有终态）");
        // 认领边界一：该发布单自己的部署认领仍在执行。
        if (releaseExecutionRunClaimService.hasHeldDeployClaim(order.getId())) {
            rejectWith(assessment, ReleaseClosureRouteAdvisor.GATE_HELD_CLAIM,
                    "发布单 " + order.getId() + " 仍有一行正在执行（HELD）的部署认领，说明一次执行可能仍在进行；"
                            + "请先确认该次执行已不再由平台控制并显式放弃认领，再收口订单");
        }
        rejectWith(assessment, ReleaseClosureRouteAdvisor.GATE_ACTIVE_CANARY_PLAN,
                "发布单 " + order.getId() + " 还有未收敛的灰度计划，灰度发布由计划与批次自己的终态语义收口；"
                        + "请在灰度页面处理该计划后再收口订单");
        rejectWith(assessment, ReleaseClosureRouteAdvisor.GATE_CLAIM_MISSING,
                "发布单没有可核对的部署认领，无法排除直接发布仍在执行；请先核对本地事实");
        rejectWith(assessment, ReleaseClosureRouteAdvisor.GATE_CLAIM_NOT_CLOSED,
                "部署认领未正常收口，不能仅凭放弃认领结束订单；放弃认领不代表远端已停止");
        // 认领边界二：同一服务/环境下还有别人未收口的认领。
        rejectWith(assessment, ReleaseClosureRouteAdvisor.GATE_HELD_CLAIM,
                "同一服务/环境仍有未收口的执行认领，不能人工收口发布单");

        long runningRecords = records.stream()
                .filter(record -> STATUS_RUNNING.equalsIgnoreCase(record.getDeployStatus()))
                .count();
        if (runningRecords > 0) {
            rejectWith(assessment, ReleaseClosureRouteAdvisor.GATE_RUNNING_RECORDS,
                    "发布单还有 " + runningRecords + " 条部署记录停在 RUNNING，说明执行尚未收口；"
                            + "不能直接给出结论");
        }

        int unresolvedForOrder = countUnresolvedFactsForOrder(order);
        int unresolvedForServiceEnv = runtimeExecutionUnknownService
                .listUnresolved(order.getServiceId(), order.getEnvId()).size();

        List<Long> updatedInstanceIds = List.of();
        List<Long> notDeployed = List.of();
        String resultingStatus;
        if (OUTCOME_SUCCEEDED.equals(normalizedOutcome)) {
            requireSuccessPreconditions(order, claim, unresolvedForOrder, unresolvedForServiceEnv, assessment);
            ReleaseDeployEvidenceService.DeployEvidence lockedEvidence = releaseDeployEvidenceService
                    .collectUnderLock(claim.getId(), order.getServiceId(), order.getEnvId());
            if (!lockedEvidence.provable()) {
                throw new BizException(ErrorCode.VALIDATION_ERROR,
                        ReleaseClosureRouteAdvisor.evidenceGateMessage(lockedEvidence.rejectionReason()));
            }
            if (releaseOrderMapper.markSuccessIfRunning(order.getId()) != 1) {
                throw new BizException(ErrorCode.INTERNAL_ERROR,
                        "发布单状态已变化，人工收口为成功未生效: " + order.getId());
            }
            runtimeServiceInstanceService.updateVersion(lockedEvidence.provenInstanceIds(), order.getVersionId());
            releaseCurrentVersionService.markReleaseVersion(order.getEnvId(), order.getServiceId(),
                    order.getVersionId(), order.getId());
            updatedInstanceIds = lockedEvidence.provenInstanceIds();
            notDeployed = lockedEvidence.currentInstanceIdsNotProven();
            resultingStatus = STATUS_SUCCESS;
        } else {
            if (releaseOrderMapper.markFailedIfRunning(order.getId()) != 1) {
                throw new BizException(ErrorCode.INTERNAL_ERROR,
                        "发布单状态已变化，人工收口为失败未生效: " + order.getId());
            }
            resultingStatus = STATUS_FAILED;
        }

        // 收口不改任何未解除的未知事实：订单状态与阻断是两件事。
        boolean blockingRemains = unresolvedForServiceEnv > 0;
        releaseAuditLogService.recordManualReleaseClosure(order.getId(), normalizedOutcome,
                claim == null ? null : claim.getId(), normalizedNote, updatedInstanceIds, notDeployed,
                unresolvedForOrder, blockingRemains);
        log.warn("发布单 {} 已由操作人人工收口为 {}（认领 {}, 更新实例 {} 个, 订单级未解除事实 {}, "
                        + "服务/环境未解除事实 {}, 阻断仍存在={}）",
                order.getId(), normalizedOutcome, claim == null ? null : claim.getId(),
                updatedInstanceIds.size(), unresolvedForOrder, unresolvedForServiceEnv, blockingRemains);

        StuckReleaseClosureResponse response = new StuckReleaseClosureResponse();
        response.setReleaseOrderId(order.getId());
        response.setOutcome(normalizedOutcome);
        response.setOrderStatus(resultingStatus);
        response.setClaimId(claim == null ? null : claim.getId());
        response.setClaimStatus(claim == null ? null : claim.getStatus());
        response.setClaimReleaseOutcome(claim == null ? null : claim.getReleaseOutcome());
        response.setPipelineRunId(claim == null ? null : claim.getPipelineRunId());
        response.setPipelineRunStatus(resolveRunStatus(claim));
        response.setUpdatedInstanceIds(updatedInstanceIds);
        response.setCurrentInstanceIdsNotDeployed(notDeployed);
        response.setDeployRecordCount(records.size());
        response.setUnresolvedFactCountForOrder(unresolvedForOrder);
        response.setUnresolvedFactCountForServiceEnv(unresolvedForServiceEnv);
        response.setBlockingRemains(blockingRemains);
        response.setConclusion(OUTCOME_SUCCEEDED.equals(normalizedOutcome)
                ? "操作人结论：这次发布实际成功。平台据此把发布单收敛为 SUCCESS，并把版本只更新到本次执行"
                + "产出的部署记录所证明的实例（" + updatedInstanceIds.size() + " 个）"
                : "操作人结论：这次发布没有成功。平台据此把发布单收敛为 FAILED，不改动任何实例版本");
        response.setDisclaimer(blockingRemains
                ? DISCLAIMER + "；该服务/环境下仍有未解除的未知事实，阻断**没有**被解除"
                : DISCLAIMER);
        fillRemainingRoutes(response, order, claim, unresolvedForOrder, unresolvedForServiceEnv);
        return response;
    }

    /**
     * 收口之后仍然可用的路径：订单已经是终态，因此收口类路径都消失，
     * 通常只剩“逐条解除未解除的未知事实”。在同一把锁内重新判定，与预览同源。
     */
    private void fillRemainingRoutes(StuckReleaseClosureResponse response, ReleaseOrder order,
                                     ReleaseExecutionRunClaim claim, int unresolvedForOrder,
                                     int unresolvedForServiceEnv) {
        ReleaseClosureRouteAdvisor.RouteAssessment assessment = releaseClosureRouteAdvisor.assess(
                new ReleaseClosureRouteAdvisor.RouteInput(
                        order, claim, List.of(), unresolvedForOrder, unresolvedForServiceEnv,
                        releaseExecutionRunClaimService
                                .listHeldClaimIdsForServiceAndEnv(order.getServiceId(), order.getEnvId()),
                        releaseCanaryPlanMapper.countActiveByReleaseOrderId(order.getId()) > 0,
                        // 收口已按各自的准入条件通过/落库，不再用证据重新判定版本写入。
                        false, null,
                        releaseExecutionRunClaimService.isClaimTableAvailable(),
                        runtimeExecutionUnknownService.isMarkerTableAvailable(),
                        runtimeExecutionUnknownMergeService.isTableAvailable(),
                        OffsetDateTime.now()));
        List<ReleaseClosureRouteView> remaining = assessment.availableRoutes();
        response.setRemainingRoutes(remaining);
        response.setAnyRemainingRouteAvailable(!remaining.isEmpty());
        response.setNextStepSummary(describeNextStep(remaining, response.isBlockingRemains()));
    }

    private static String describeNextStep(List<ReleaseClosureRouteView> remaining, boolean blockingRemains) {
        if (!remaining.isEmpty()) {
            return "订单已收敛，但该服务/环境下仍有未解除的未知事实挡住新操作；"
                    + "下一步可执行：" + remaining.stream()
                    .map(view -> view.getLabel() + "（" + view.getActionPath() + "）")
                    .reduce((left, right) -> left + "；" + right).orElse("")
                    + "。解除后才放行同一服务/环境的新发布、回滚与实例移除。";
        }
        if (blockingRemains) {
            return "订单已收敛，但该服务/环境下仍有未解除的未知事实，平台内现在没有可执行的解除入口"
                    + "（逐条原因见 routes）；这些事实继续挡住同一服务/环境的新发布、回滚与实例移除，"
                    + "请交给运维在平台之外处置。";
        }
        return "订单已收敛，且该服务/环境下没有未解除的未知事实，阻断已清空，无需进一步操作。";
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private void requireSuccessPreconditions(ReleaseOrder order, ReleaseExecutionRunClaim claim,
                                             int unresolvedForOrder, int unresolvedForServiceEnv,
                                             ReleaseClosureRouteAdvisor.RouteAssessment assessment) {
        if (claim == null) {
            rejectWith(assessment, ReleaseClosureRouteAdvisor.GATE_CLAIM_MISSING,
                    "发布单 " + order.getId() + " 没有部署认领，无法证明这次运行真正执行过部署，不能收口为成功");
        }
        if (!CLAIM_RELEASED.equals(claim.getStatus())) {
            rejectWith(assessment, ReleaseClosureRouteAdvisor.GATE_CLAIM_NOT_CLOSED,
                    "部署认领 " + claim.getId() + " 仍为 " + claim.getStatus() + "（未正常收口），不能收口为成功");
        }
        if (!CLAIM_COMPLETED.equals(claim.getReleaseOutcome())) {
            rejectWith(assessment, ReleaseClosureRouteAdvisor.GATE_CLAIM_NOT_CLOSED,
                    "部署认领 " + claim.getId() + " 的释放结果是 " + claim.getReleaseOutcome()
                            + "（不是正常收口），不能收口为成功");
        }
        if (unresolvedForOrder > 0 || unresolvedForServiceEnv > 0) {
            rejectWith(assessment, ReleaseClosureRouteAdvisor.GATE_UNRESOLVED_FACTS,
                    "同一服务/环境仍存在未收口的执行事实（本单 " + unresolvedForOrder + " 条 / 服务环境 "
                            + unresolvedForServiceEnv + " 条未解除的未知事实）：平台不据此判断远端状态，"
                            + "也不会在事实未收口时声明成功");
        }
        if (!releaseExecutionRunClaimService
                .listHeldClaimIdsForServiceAndEnv(order.getServiceId(), order.getEnvId()).isEmpty()) {
            rejectWith(assessment, ReleaseClosureRouteAdvisor.GATE_HELD_CLAIM,
                    "同一服务/环境还有未释放（HELD）的部署认领，可能仍有执行在进行，不能收口为成功");
        }
    }

    /**
     * 用对账得到的**统一文案**拒绝。{@code fallback} 只在对应门槛的原因缺失时兜底
     * （正常情况下调用方的条件与对账的条件同源，文案必定存在）。
     */
    private static void rejectWith(ReleaseClosureRouteAdvisor.RouteAssessment assessment, String gate,
                                  String fallback) {
        String message = assessment.gateMessage(gate);
        throw new BizException(ErrorCode.VALIDATION_ERROR, StringUtils.hasText(message) ? message : fallback);
    }

    private ReleaseOrder requireOrder(Long releaseOrderId) {
        if (releaseOrderId == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "发布单 ID 不能为空");
        }
        ReleaseOrder order = releaseOrderMapper.selectById(releaseOrderId);
        if (order == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "发布单不存在: " + releaseOrderId);
        }
        return order;
    }

    /** 本次执行的部署记录：有认领时用认领（执行归属），没有认领时退回该发布单的全部记录。 */
    private List<RuntimeDeployRecord> collectRecords(ReleaseOrder order, ReleaseExecutionRunClaim claim) {
        if (claim != null) {
            List<RuntimeDeployRecord> records = runtimeDeployRecordService.listByExecutionClaimId(claim.getId());
            if (!records.isEmpty()) {
                return records;
            }
        }
        return runtimeDeployRecordService.listByReleaseOrderId(order.getId());
    }

    private void fillRecords(StuckReleaseClosurePreviewResponse response, List<RuntimeDeployRecord> records) {
        List<Long> recordIds = records.stream()
                .map(RuntimeDeployRecord::getId)
                .filter(Objects::nonNull)
                .toList();
        Map<Long, List<RuntimeDeployRecordInstance>> linksByRecord = new LinkedHashMap<>();
        for (RuntimeDeployRecordInstance link : runtimeDeployRecordInstanceService.listByDeployRecordIds(recordIds)) {
            linksByRecord.computeIfAbsent(link.getDeployRecordId(), key -> new ArrayList<>()).add(link);
        }
        for (RuntimeDeployRecord record : records) {
            StuckReleaseClosurePreviewResponse.DeployRecordView view =
                    new StuckReleaseClosurePreviewResponse.DeployRecordView();
            view.setDeployRecordId(record.getId());
            view.setDeployStatus(record.getDeployStatus());
            view.setDeployBatch(record.getDeployBatch());
            view.setStartedAt(record.getStartedAt());
            view.setEndedAt(record.getEndedAt());
            List<RuntimeDeployRecordInstance> links = linksByRecord.getOrDefault(record.getId(), List.of());
            String attribution;
            if (links.isEmpty()) {
                attribution = "MISSING";
            } else if (links.size() > 1) {
                attribution = "AMBIGUOUS";
            } else {
                view.setInstanceId(links.get(0).getInstanceId());
                view.setInstanceName(links.get(0).getInstanceName());
                attribution = "ATTRIBUTED";
            }
            view.setInstanceAttribution(attribution);
            response.getDeployRecords().add(view);
            if (STATUS_RUNNING.equalsIgnoreCase(record.getDeployStatus())) {
                response.setRunningRecordCount(response.getRunningRecordCount() + 1);
            } else if (STATUS_SUCCESS.equalsIgnoreCase(record.getDeployStatus())) {
                response.setSuccessRecordCount(response.getSuccessRecordCount() + 1);
            } else if (STATUS_FAILED.equalsIgnoreCase(record.getDeployStatus())) {
                response.setFailedRecordCount(response.getFailedRecordCount() + 1);
            } else if (STATUS_UNKNOWN.equalsIgnoreCase(record.getDeployStatus())) {
                response.setUnknownRecordCount(response.getUnknownRecordCount() + 1);
            }
            if (!"ATTRIBUTED".equals(attribution)) {
                response.setRecordCountWithoutInstanceAttribution(
                        response.getRecordCountWithoutInstanceAttribution() + 1);
            }
        }
        response.getDeployRecords().sort(Comparator.comparing(
                StuckReleaseClosurePreviewResponse.DeployRecordView::getDeployRecordId,
                Comparator.nullsLast(Comparator.naturalOrder())));
    }

    private void fillClaimAndRun(StuckReleaseClosurePreviewResponse response, ReleaseExecutionRunClaim claim) {
        if (claim == null) {
            return;
        }
        StuckReleaseClosurePreviewResponse.ClaimView claimView =
                new StuckReleaseClosurePreviewResponse.ClaimView();
        claimView.setClaimId(claim.getId());
        claimView.setStatus(claim.getStatus());
        claimView.setReleaseOutcome(claim.getReleaseOutcome());
        claimView.setPipelineRunId(claim.getPipelineRunId());
        claimView.setClaimedAt(claim.getClaimedAt());
        claimView.setReleasedAt(claim.getReleasedAt());
        claimView.setPipelineRunStatus(resolveRunStatus(claim));
        response.setClaim(claimView);
        if (claim.getPipelineRunId() != null) {
            PipelineRun run = pipelineRunMapper.selectById(claim.getPipelineRunId());
            if (run != null) {
                StuckReleaseClosurePreviewResponse.RunView runView =
                        new StuckReleaseClosurePreviewResponse.RunView();
                runView.setPipelineRunId(run.getId());
                runView.setRunStatus(run.getRunStatus());
                runView.setStartTime(run.getStartTime());
                runView.setEndTime(run.getEndTime());
                response.setPipelineRun(runView);
            }
        }
    }

    private void fillInstanceEvidence(StuckReleaseClosurePreviewResponse response, ReleaseOrder order,
                                      ReleaseExecutionRunClaim claim) {
        if (claim == null) {
            response.setInstanceAttributionProvable(false);
            response.setInstanceAttributionRejectionReason("没有部署认领，无法证明这次运行真正执行过部署");
            return;
        }
        ReleaseDeployEvidenceService.DeployEvidence evidence = releaseDeployEvidenceService
                .collect(claim.getId(), order.getServiceId(), order.getEnvId());
        response.setProvenInstanceIds(evidence.provenInstanceIds());
        response.setCurrentInstanceIdsNotDeployed(evidence.currentInstanceIdsNotProven());
        response.setInstanceAttributionProvable(evidence.provable());
        response.setInstanceAttributionRejectionReason(evidence.rejectionReason());
        markAttributionDetail(response, evidence);
    }

    /** 把“实例已移除 / 已改绑”标到对应记录上，让操作者看到是哪条记录对不上。 */
    private void markAttributionDetail(StuckReleaseClosurePreviewResponse response,
                                       ReleaseDeployEvidenceService.DeployEvidence evidence) {
        if (evidence.removedInstanceIds().isEmpty() && evidence.reattributedInstanceIds().isEmpty()) {
            return;
        }
        Map<Long, String> affected = new LinkedHashMap<>();
        for (Long instanceId : evidence.removedInstanceIds()) {
            affected.put(instanceId, "INSTANCE_REMOVED");
        }
        for (Long instanceId : evidence.reattributedInstanceIds()) {
            affected.put(instanceId, "INSTANCE_REBOUND");
        }
        for (StuckReleaseClosurePreviewResponse.DeployRecordView view : response.getDeployRecords()) {
            if (view.getInstanceId() != null && affected.containsKey(view.getInstanceId())) {
                view.setInstanceAttribution(affected.get(view.getInstanceId()));
            }
        }
    }

    /** 两套拒绝文案共用的门槛，顺序即展示顺序。 */
    private static final List<String> COMMON_GATES = List.of(
            ReleaseClosureRouteAdvisor.GATE_ORDER_NOT_RUNNING,
            ReleaseClosureRouteAdvisor.GATE_ACTIVE_CANARY_PLAN,
            ReleaseClosureRouteAdvisor.GATE_RUNNING_RECORDS,
            ReleaseClosureRouteAdvisor.GATE_HELD_CLAIM,
            ReleaseClosureRouteAdvisor.GATE_CLAIM_MISSING,
            ReleaseClosureRouteAdvisor.GATE_CLAIM_NOT_CLOSED);

    /** 只对“收口为成功”成立的门槛。 */
    private static final List<String> SUCCESS_ONLY_GATES = List.of(
            ReleaseClosureRouteAdvisor.GATE_UNRESOLVED_FACTS,
            ReleaseClosureRouteAdvisor.GATE_EVIDENCE_NOT_PROVABLE);

    /** 报告本库的迁移可用性：它是各入口失败关闭的前提，页面需要把它与业务状态分开陈述。 */
    private void fillTableAvailability(StuckReleaseClosurePreviewResponse response) {
        response.setClaimTableAvailable(releaseExecutionRunClaimService.isClaimTableAvailable());
        response.setUnknownMarkerTableAvailable(runtimeExecutionUnknownService.isMarkerTableAvailable());
        response.setMergeLedgerAvailable(runtimeExecutionUnknownMergeService.isTableAvailable());
        // 与 DeployEvidence 用的是同一个探测：这里单独报一次，没有认领时也能说明 V34 是否已应用，
        // 否则页面会把“没有认领”误读成“实例归属表不可用”。
        response.setInstanceAttributionTableAvailable(runtimeDeployRecordInstanceService.isTableAvailable());
    }

    /**
     * 对账各条可选路径，并用**同一份门槛文案**填准入判定。
     *
     * <p>它替掉了原来的“只列阻塞项”：被拒绝时，指向另一条入口的引导只在该入口现在确实可用时
     * 才会出现；一条都不可用时，{@code noRouteReason} 如实说明这是死交，
     * 而不是把操作者引到一条同样会被拒绝的路。
     */
    private void fillRouteAssessment(StuckReleaseClosurePreviewResponse response, ReleaseOrder order,
                                     ReleaseExecutionRunClaim claim, List<RuntimeDeployRecord> records) {
        ReleaseDeployEvidenceService.DeployEvidence evidence = claim == null ? null
                : releaseDeployEvidenceService.collect(claim.getId(), order.getServiceId(), order.getEnvId());
        ReleaseClosureRouteAdvisor.RouteAssessment assessment = assessRoutes(order, claim, records, evidence);
        List<Long> heldClaimIds = releaseExecutionRunClaimService
                .listHeldClaimIdsForServiceAndEnv(order.getServiceId(), order.getEnvId());

        response.setRoutes(assessment.routes());
        response.setAnyRouteAvailable(assessment.anyRouteAvailable());
        response.setNoRouteReason(assessment.anyRouteAvailable()
                ? null : describeNoRoute(assessment));
        response.setAdoptSuccessRefusalReasons(reasonsFor(assessment, true));
        response.setAdoptSuccessAllowed(response.getAdoptSuccessRefusalReasons().isEmpty());
        response.setCloseAsFailedRefusalReasons(reasonsFor(assessment, false));
        response.setCloseAsFailedAllowed(response.getCloseAsFailedRefusalReasons().isEmpty());
        // heldClaimOnServiceEnv 与 heldClaimIds 同源：两者都表示“服务/环境下仍有 HELD 认领”。
        response.setHeldClaimOnServiceEnv(!heldClaimIds.isEmpty());
        response.setActiveCanaryPlan(
                releaseCanaryPlanMapper.countActiveByReleaseOrderId(order.getId()) > 0);
        response.setUnresolvedFactCountForOrder(countUnresolvedFactsForOrder(order));
        response.setUnresolvedFactCountForServiceEnv(
                runtimeExecutionUnknownService.listUnresolved(order.getServiceId(), order.getEnvId()).size());
    }

    /** 把已在同一时刻读到的事实交给对账：判定与文案来自一处，预览与提交因此不会漂移。 */
    private ReleaseClosureRouteAdvisor.RouteAssessment assessRoutes(
            ReleaseOrder order, ReleaseExecutionRunClaim claim, List<RuntimeDeployRecord> records,
            ReleaseDeployEvidenceService.DeployEvidence evidence) {
        return releaseClosureRouteAdvisor.assess(new ReleaseClosureRouteAdvisor.RouteInput(
                order, claim, records,
                countUnresolvedFactsForOrder(order),
                runtimeExecutionUnknownService.listUnresolved(order.getServiceId(), order.getEnvId()).size(),
                releaseExecutionRunClaimService
                        .listHeldClaimIdsForServiceAndEnv(order.getServiceId(), order.getEnvId()),
                releaseCanaryPlanMapper.countActiveByReleaseOrderId(order.getId()) > 0,
                evidence != null && evidence.provable(),
                evidence == null ? "没有部署认领，无法证明这次运行真正执行过部署" : evidence.rejectionReason(),
                releaseExecutionRunClaimService.isClaimTableAvailable(),
                runtimeExecutionUnknownService.isMarkerTableAvailable(),
                runtimeExecutionUnknownMergeService.isTableAvailable(),
                OffsetDateTime.now()));
    }

    private static List<String> reasonsFor(ReleaseClosureRouteAdvisor.RouteAssessment assessment,
                                           boolean forSuccess) {
        List<String> gates = forSuccess
                ? concat(COMMON_GATES, SUCCESS_ONLY_GATES)
                : COMMON_GATES;
        List<String> reasons = new ArrayList<>();
        for (String gate : gates) {
            String message = assessment.gateMessage(gate);
            if (StringUtils.hasText(message)) {
                reasons.add(message);
            }
        }
        return reasons;
    }

    private static List<String> concat(List<String> left, List<String> right) {
        List<String> all = new ArrayList<>(left);
        all.addAll(right);
        return all;
    }

    /**
     * 一条可执行路径都没有时的如实陈述。它不重复各路径的逐条原因（那些在 {@code routes} 里），
     * 只说明：为什么没有入口、订单会怎样、平台不做什么。
     */
    private static String describeNoRoute(ReleaseClosureRouteAdvisor.RouteAssessment assessment) {
        return "平台内现在没有可以提交的收口或恢复入口（各路径的逐条原因见 routes；"
                + "当前不可用的路径是 " + assessment.routes().stream()
                .filter(view -> !view.isAvailable())
                .map(ReleaseClosureRouteView::getLabel)
                .reduce((left, right) -> left + "、" + right).orElse("无")
                + "）。订单将保持 RUNNING，并继续挡住同一服务/环境的新发布、回滚与实例移除；"
                + "平台不提供绕过这些准入边界的强制收口，请把这个死交交给运维在平台之外处置。";
    }

    private int countUnresolvedFactsForOrder(ReleaseOrder order) {
        return (int) runtimeExecutionUnknownService.listUnresolved(order.getServiceId(), order.getEnvId()).stream()
                .filter(fact -> Objects.equals(order.getId(), fact.getReleaseOrderId()))
                .count();
    }

    private String resolveRunStatus(ReleaseExecutionRunClaim claim) {
        if (claim == null || claim.getPipelineRunId() == null) {
            return null;
        }
        PipelineRun run = pipelineRunMapper.selectById(claim.getPipelineRunId());
        return run == null ? null : run.getRunStatus();
    }

    private static String requireOutcome(String outcome) {
        if (outcome == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "人工收口结论不能为空");
        }
        String normalized = outcome.trim().toUpperCase();
        if (!OUTCOME_SUCCEEDED.equals(normalized) && !OUTCOME_FAILED.equals(normalized)) {
            throw new BizException(ErrorCode.VALIDATION_ERROR,
                    "人工收口结论只能是 SUCCEEDED 或 FAILED，当前: " + outcome);
        }
        return normalized;
    }

    private static String requireNote(String note) {
        if (!StringUtils.hasText(note)) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "人工收口必须写明核对结论与依据");
        }
        String trimmed = note.trim();
        return trimmed.length() > 1000 ? trimmed.substring(0, 1000) : trimmed;
    }
}

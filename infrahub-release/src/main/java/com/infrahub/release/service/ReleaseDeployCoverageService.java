package com.infrahub.release.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.infrahub.release.dto.DeployCoverage;
import com.infrahub.release.entity.ReleaseCanaryBatch;
import com.infrahub.release.entity.ReleaseCanaryPlan;
import com.infrahub.release.entity.ReleaseCurrentVersion;
import com.infrahub.release.entity.ReleaseExecutionRunClaim;
import com.infrahub.release.entity.ReleaseOrder;
import com.infrahub.release.entity.ReleaseRollbackOrder;
import com.infrahub.release.entity.ReleaseVersion;
import com.infrahub.release.entity.RuntimeDeployRecord;
import com.infrahub.release.mapper.ReleaseCanaryBatchMapper;
import com.infrahub.release.mapper.ReleaseCanaryPlanMapper;
import com.infrahub.release.mapper.ReleaseOrderMapper;
import com.infrahub.release.mapper.ReleaseRollbackOrderMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 算出一次发布的**实例覆盖范围**：本次成功部署的证据覆盖了当前实例集合里的哪些实例。
 *
 * <p>本类只回答“这次执行覆盖了谁”，**不**回答“这些实例现在是否仍登记在该版本”。后者由
 * {@link VersionRegistrationConsistencyService} 单独计算，两个结论在 DTO 与页面上必须分开表述。
 * 即使 {@code verdict=FULL}，也只表示本次执行覆盖了当时/当前实例集合的全部实例，不表示
 * 当前登记版本一致，更不表示远端实际运行版本。
 *
 * <p><b>证据来源</b>：
 * <ul>
 *   <li>普通发布：发布单 → 部署认领 → 认领产出的部署记录 → 记录创建时写下的实例归属；</li>
 *   <li>灰度发布：灰度计划 → 成功批次的实例清单 + 该批次的部署记录/实例归属；未成功批次中的
 *       实例单独列为 {@code unfinishedInstanceIds}，不混入“未参与”集合；</li>
 *   <li>回滚：部署记录不写实例身份，回滚来源的覆盖范围结构上不可证明，只给出经过校验的来源原因。</li>
 * </ul>
 * 所有拒绝理由均面向操作者，不包含表名、迁移编号、类名、命令、异常或远端输出；技术细节留在
 * {@link ReleaseDeployEvidenceService.DeployEvidence} 与交付说明中。
 *
 * <p><b>并发与执行期间的实例变更</b>：本视图是只读快照，不持锁。执行期间新增的实例落入未覆盖
 * 集合，**不是部署失败**；执行期间被移除或改绑的实例会让整次证据不可证明（失败关闭），不从
 * “看起来没问题”的部分外推覆盖结论。
 */
@Service
@RequiredArgsConstructor
public class ReleaseDeployCoverageService {

    private static final String OPERATION_RELEASE = "RELEASE";
    private static final String ROLLBACK_STATUS_SUCCESS = "SUCCESS";
    private static final String RELEASE_TYPE_CANARY = "CANARY";

    private final ReleaseExecutionRunClaimService releaseExecutionRunClaimService;
    private final ReleaseDeployEvidenceService releaseDeployEvidenceService;
    private final RuntimeDeployRecordService runtimeDeployRecordService;
    private final ReleaseVersionService releaseVersionService;
    private final ReleaseOrderMapper releaseOrderMapper;
    private final ReleaseRollbackOrderMapper releaseRollbackOrderMapper;
    private final ReleaseCanaryPlanMapper releaseCanaryPlanMapper;
    private final ReleaseCanaryBatchMapper releaseCanaryBatchMapper;
    private final ObjectMapper objectMapper;

    /** 版本指针的覆盖范围：先校验来源订单，再按来源类型计算。指针不存在时返回“没有可归属的执行”。 */
    public DeployCoverage forVersionPointer(ReleaseCurrentVersion pointer) {
        if (pointer == null) {
            DeployCoverage coverage = new DeployCoverage();
            coverage.setSourceType(DeployCoverage.SOURCE_NONE);
            return notStarted(coverage, "没有版本指针行");
        }

        DeployCoverage coverage = new DeployCoverage();
        coverage.setTargetVersionId(pointer.getVersionId());
        fillTargetVersionNo(coverage);

        if (pointer.getSourceRollbackOrderId() != null) {
            return forRollbackSource(coverage, pointer);
        }
        if (pointer.getSourceReleaseOrderId() == null) {
            coverage.setSourceType(DeployCoverage.SOURCE_NONE);
            return notStarted(coverage, "版本指针没有记录来源订单，无法归属这次执行");
        }
        coverage.setSourceType(DeployCoverage.SOURCE_RELEASE);
        return forReleaseOrder(coverage, pointer.getSourceReleaseOrderId(),
                pointer.getServiceId(), pointer.getEnvId(), pointer.getVersionId());
    }

    /** 某个发布单这次执行的覆盖范围（订单结果展示用）。 */
    public DeployCoverage forReleaseOrder(Long releaseOrderId, Long serviceId, Long envId,
                                          Long targetVersionId) {
        DeployCoverage coverage = new DeployCoverage();
        coverage.setSourceType(DeployCoverage.SOURCE_RELEASE);
        return forReleaseOrder(coverage, releaseOrderId, serviceId, envId, targetVersionId);
    }

    private DeployCoverage forReleaseOrder(DeployCoverage coverage, Long releaseOrderId,
                                           Long serviceId, Long envId, Long targetVersionId) {
        if (releaseOrderId == null) {
            return notStarted(coverage, "没有来源发布单，无法归属这次执行");
        }
        ReleaseOrder order = releaseOrderMapper.selectById(releaseOrderId);
        if (order == null) {
            return unprovable(coverage, DeployCoverage.REJECTION_SOURCE_MISSING,
                    "来源发布单不存在，无法核对这次执行的覆盖范围");
        }
        coverage.setSourceOrderId(order.getId());
        coverage.setSourceOrderNo(order.getOrderNo());
        if (order.getVersionId() == null) {
            return unprovable(coverage, DeployCoverage.REJECTION_SOURCE_MISMATCH,
                    "来源发布单没有目标版本，无法核对部署记录是否属于这次发布");
        }
        if (!Objects.equals(order.getServiceId(), serviceId)
                || !Objects.equals(order.getEnvId(), envId)
                || !Objects.equals(order.getVersionId(), targetVersionId)) {
            return unprovable(coverage, DeployCoverage.REJECTION_SOURCE_MISMATCH,
                    "来源发布单的服务、环境或目标版本与当前展示的来源不一致，不能复用它的执行证据");
        }

        coverage.setTargetVersionId(order.getVersionId());
        fillTargetVersionNo(coverage);

        ReleaseCanaryPlan plan = releaseCanaryPlanMapper.findByReleaseOrderId(order.getId());
        if (plan != null || RELEASE_TYPE_CANARY.equalsIgnoreCase(order.getReleaseType())) {
            coverage.setSourceType(DeployCoverage.SOURCE_CANARY);
            return forCanary(coverage, order, plan);
        }
        return forClaim(coverage, order);
    }

    private DeployCoverage forRollbackSource(DeployCoverage coverage, ReleaseCurrentVersion pointer) {
        Long rollbackOrderId = pointer.getSourceRollbackOrderId();
        coverage.setSourceType(DeployCoverage.SOURCE_ROLLBACK);
        coverage.setSourceOrderId(rollbackOrderId);

        ReleaseRollbackOrder rollback = releaseRollbackOrderMapper.selectById(rollbackOrderId);
        if (rollback == null) {
            return unprovable(coverage, DeployCoverage.REJECTION_SOURCE_MISSING,
                    "版本指针记录的回滚单不存在，无法核对这次版本变更的来源");
        }
        coverage.setSourceOrderNo(rollback.getRollbackNo());
        if (rollback.getReleaseOrderId() == null) {
            if (!Objects.equals(rollback.getServiceId(), pointer.getServiceId())
                    || !Objects.equals(rollback.getEnvId(), pointer.getEnvId())
                    || !Objects.equals(rollback.getToVersionId(), pointer.getVersionId())
                    || !ROLLBACK_STATUS_SUCCESS.equalsIgnoreCase(rollback.getStatus())) {
                return unprovable(coverage, DeployCoverage.REJECTION_SOURCE_MISMATCH,
                        "回滚记录的服务、环境、目标版本或执行状态与当前版本不一致");
            }
            fillTargetVersionNo(coverage);
            return fromEvidence(coverage, releaseDeployEvidenceService.analyzeRecords(null,
                    runtimeDeployRecordService.listByRollbackOrderId(rollbackOrderId), rollback.selectedInstanceIds(),
                    pointer.getServiceId(), pointer.getEnvId(), pointer.getVersionId()));
        }
        ReleaseOrder sourceOrder = releaseOrderMapper.selectById(rollback.getReleaseOrderId());
        if (sourceOrder == null) {
            return unprovable(coverage, DeployCoverage.REJECTION_SOURCE_MISSING,
                    "回滚单关联的发布单不存在，无法核对这次版本变更的来源");
        }
        if (!Objects.equals(sourceOrder.getServiceId(), pointer.getServiceId())
                || !Objects.equals(sourceOrder.getEnvId(), pointer.getEnvId())
                || !Objects.equals(rollback.getToVersionId(), pointer.getVersionId())) {
            return unprovable(coverage, DeployCoverage.REJECTION_SOURCE_MISMATCH,
                    "版本指针的服务、环境或目标版本与回滚单不一致，不能把回滚单当作这次版本变更的来源");
        }
        if (!ROLLBACK_STATUS_SUCCESS.equalsIgnoreCase(rollback.getStatus())) {
            return unprovable(coverage, DeployCoverage.REJECTION_SOURCE_MISMATCH,
                    "回滚单当前不是成功状态，版本指针却把它记为来源；平台不能确认这次版本变更已经完成");
        }
        fillTargetVersionNo(coverage);
        return unprovable(coverage, DeployCoverage.REJECTION_ROLLBACK_UNPROVABLE,
                "这次版本变更来自回滚，但回滚执行的部署记录没有保存实例身份，"
                        + "平台无法从持久事实证明它覆盖了哪些实例");
    }

    private DeployCoverage forClaim(DeployCoverage coverage, ReleaseOrder order) {
        if (!releaseExecutionRunClaimService.isClaimTableAvailable()) {
            return unprovable(coverage, DeployCoverage.REJECTION_CLAIM_UNAVAILABLE,
                    "平台没有可用的执行认领记录，无法把这次发布归属到一次具体执行");
        }
        ReleaseExecutionRunClaim claim = releaseExecutionRunClaimService.findDeployClaim(order.getId());
        if (claim == null) {
            return unprovable(coverage, DeployCoverage.REJECTION_NO_CLAIM,
                    "没有找到这次发布的执行认领记录（可能来自旧版本，或没有经过平台的执行入口），"
                            + "无法证明这次运行真正执行过部署");
        }
        coverage.setSourceClaimId(claim.getId());
        if (!Objects.equals(claim.getServiceId(), order.getServiceId())
                || !Objects.equals(claim.getEnvId(), order.getEnvId())) {
            return unprovable(coverage, DeployCoverage.REJECTION_SOURCE_MISMATCH,
                    "执行认领的服务、环境与发布单不一致，不能复用这次执行的证据");
        }
        if (ReleaseExecutionRunClaim.STATUS_HELD.equals(claim.getStatus())) {
            return unprovable(coverage, DeployCoverage.REJECTION_CLAIM_NOT_CLOSED,
                    "这次执行的认领尚未收口，平台无法确认执行已经结束");
        }
        if (ReleaseExecutionRunClaim.OUTCOME_ABANDONED.equals(claim.getReleaseOutcome())) {
            return unprovable(coverage, DeployCoverage.REJECTION_CLAIM_ABANDONED,
                    "这次执行的认领已被放弃；放弃只表示平台不再等待它，不代表部署成功");
        }
        if (!ReleaseExecutionRunClaim.STATUS_RELEASED.equals(claim.getStatus())
                || !ReleaseExecutionRunClaim.OUTCOME_COMPLETED.equals(claim.getReleaseOutcome())) {
            return unprovable(coverage, DeployCoverage.REJECTION_CLAIM_NOT_CLOSED,
                    "这次执行的认领状态不完整，平台无法确认它已经正常收口");
        }

        ReleaseDeployEvidenceService.DeployEvidence evidence = releaseDeployEvidenceService
                .collectForVersion(claim.getId(), order.getServiceId(), order.getEnvId(), order.getVersionId());
        return fromEvidence(coverage, evidence);
    }

    private DeployCoverage forCanary(DeployCoverage coverage, ReleaseOrder order, ReleaseCanaryPlan plan) {
        if (plan == null) {
            return unprovable(coverage, DeployCoverage.REJECTION_CANARY_PLAN_MISSING,
                    "这次发布被标记为灰度，但没有找到灰度计划，平台无法知道它覆盖了哪些实例");
        }
        List<ReleaseCanaryBatch> batches = releaseCanaryBatchMapper.findByPlanId(plan.getId());
        if (batches == null || batches.isEmpty()) {
            return unprovable(coverage, DeployCoverage.REJECTION_CANARY_BATCH_INVALID,
                    "灰度计划没有保存批次清单，平台无法核对覆盖范围");
        }

        List<ReleaseCanaryBatch> successBatches = new ArrayList<>();
        Set<Long> expectedInstanceIds = new LinkedHashSet<>();
        Set<Long> unfinishedInstanceIds = new LinkedHashSet<>();
        Set<Integer> successBatchNos = new LinkedHashSet<>();
        boolean anyStarted = false;
        for (ReleaseCanaryBatch batch : batches) {
            List<Long> instanceIds = parseBatchInstanceIds(batch.getInstanceIds());
            if (instanceIds == null || instanceIds.isEmpty()
                    || instanceIds.stream().anyMatch(Objects::isNull)
                    || instanceIds.stream().distinct().count() != instanceIds.size()) {
                return unprovable(coverage, DeployCoverage.REJECTION_CANARY_BATCH_INVALID,
                        "灰度批次清单缺失或无法解析，平台无法确定这次灰度覆盖了哪些实例");
            }
            boolean success = ReleaseCanaryBatch.STATUS_SUCCESS.equals(batch.getStatus());
            if (success) {
                successBatches.add(batch);
                successBatchNos.add(batch.getBatchNo());
                expectedInstanceIds.addAll(instanceIds);
            } else {
                if (!ReleaseCanaryBatch.STATUS_PENDING.equals(batch.getStatus())) {
                    anyStarted = true;
                }
                unfinishedInstanceIds.addAll(instanceIds);
            }
        }

        if (successBatches.isEmpty()) {
            if (!anyStarted && ReleaseCanaryPlan.STATUS_CREATED.equals(plan.getStatus())) {
                return notStarted(coverage, "灰度计划尚未开始执行，没有可证明的成功覆盖范围");
            }
            return unprovable(coverage, DeployCoverage.REJECTION_CANARY_NO_SUCCESS,
                    "这次灰度还没有任何成功批次，平台没有可证明的成功覆盖范围");
        }

        List<RuntimeDeployRecord> batchRecords = runtimeDeployRecordService
                .listByReleaseOrderId(order.getId()).stream()
                .filter(record -> OPERATION_RELEASE.equalsIgnoreCase(record.getOperationType()))
                .filter(record -> record.getDeployBatch() != null
                        && successBatchNos.contains(record.getDeployBatch()))
                .toList();

        ReleaseDeployEvidenceService.DeployEvidence evidence = releaseDeployEvidenceService.analyzeRecords(
                null, batchRecords, expectedInstanceIds, order.getServiceId(), order.getEnvId(),
                order.getVersionId());
        if (!evidence.provable()) {
            return fromEvidence(coverage, evidence);
        }

        List<Long> deployed = evidence.provenInstanceIds();
        List<Long> notProven = evidence.currentInstanceIdsNotProven();
        List<Long> unfinished = notProven.stream()
                .filter(unfinishedInstanceIds::contains)
                .sorted(Comparator.naturalOrder())
                .toList();
        List<Long> uncovered = notProven.stream()
                .filter(instanceId -> !unfinishedInstanceIds.contains(instanceId))
                .sorted(Comparator.naturalOrder())
                .toList();

        coverage.setEvidenceProvable(true);
        coverage.setDeployedInstanceIds(deployed);
        coverage.setDeployedCount(deployed.size());
        coverage.setUnfinishedInstanceIds(unfinished);
        coverage.setUnfinishedCount(unfinished.size());
        coverage.setUncoveredInstanceIds(uncovered);
        coverage.setUncoveredCount(uncovered.size());
        coverage.setCurrentInstanceCount(deployed.size() + unfinished.size() + uncovered.size());
        coverage.setDisclaimer(DeployCoverage.DISCLAIMER);

        boolean planComplete = ReleaseCanaryPlan.STATUS_SUCCESS.equals(plan.getStatus())
                && successBatches.size() == batches.size();
        if (planComplete && uncovered.isEmpty() && unfinished.isEmpty()) {
            coverage.setVerdict(DeployCoverage.VERDICT_FULL);
            coverage.setStatement("本次灰度执行的全部批次均已成功，覆盖了该服务/环境当前的全部 "
                    + coverage.getCurrentInstanceCount() + " 个实例（实例 " + joinIds(deployed)
                    + "）。这只表示本次执行的覆盖范围，不表示这些实例现在仍登记在该版本。");
            coverage.setNextStep("本次执行已覆盖当前全部实例；如果之后新增实例或再次发布、回滚，"
                    + "覆盖范围与当前登记版本一致性会分别变化。");
            return coverage;
        }

        coverage.setVerdict(DeployCoverage.VERDICT_PARTIAL);
        StringBuilder statement = new StringBuilder();
        statement.append("本次灰度执行的成功批次覆盖了该服务/环境当前 ")
                .append(coverage.getCurrentInstanceCount()).append(" 个实例中的 ")
                .append(deployed.size()).append(" 个（实例 ").append(joinIds(deployed)).append("）。");
        if (!unfinished.isEmpty()) {
            statement.append("其中 ").append(unfinished.size()).append(" 个实例所在批次未成功或尚未执行（实例 ")
                    .append(joinIds(unfinished)).append("），平台没有它们的成功部署证据。");
        }
        if (!uncovered.isEmpty()) {
            statement.append("另有 ").append(uncovered.size()).append(" 个实例没有进入本次灰度范围（实例 ")
                    .append(joinIds(uncovered)).append("），它们没有参与这次执行，不是部署失败。");
        }
        statement.append("灰度计划状态：").append(planStatusText(plan.getStatus())).append("。");
        coverage.setStatement(statement.toString());
        coverage.setNextStep("平台当前的发布入口以服务/环境为范围，不能只针对上述实例发起定向发布；"
                + "如需让未覆盖或未成功的实例到达该版本，需要按服务/环境的发布流程重新执行。"
                + "平台不会自动补发，也不会因为部分批次成功就把其余实例标记为已部署。");
        return coverage;
    }

    private DeployCoverage fromEvidence(DeployCoverage coverage,
                                        ReleaseDeployEvidenceService.DeployEvidence evidence) {
        coverage.setEvidenceProvable(evidence.provable());
        // 移除/改绑是证据直接暴露的事实；即使整体不可证明，也应让操作者看到具体实例。
        coverage.setRemovedInstanceIds(evidence.removedInstanceIds());
        coverage.setReboundInstanceIds(evidence.reattributedInstanceIds());
        if (!evidence.provable()) {
            coverage.setVerdict(DeployCoverage.VERDICT_UNPROVABLE);
            coverage.setRejectionCode(rejectionCode(evidence));
            String reason = evidenceReasons(evidence);
            coverage.setRejectionReason(reason);
            coverage.setStatement("无法证明这次执行实际部署在哪些实例上：" + reason
                    + "。平台不据此声明它覆盖了当前任何实例。");
            coverage.setNextStep("若要确认覆盖范围，需要人工核对部署记录与实例归属；"
                    + "平台不会连接远端、不会自动补发。");
            coverage.setDisclaimer(DeployCoverage.DISCLAIMER);
            return coverage;
        }

        List<Long> deployed = evidence.provenInstanceIds();
        List<Long> uncovered = evidence.currentInstanceIdsNotProven();
        coverage.setDeployedInstanceIds(deployed);
        coverage.setDeployedCount(deployed.size());
        coverage.setUncoveredInstanceIds(uncovered);
        coverage.setUncoveredCount(uncovered.size());
        coverage.setCurrentInstanceCount(deployed.size() + uncovered.size());
        coverage.setDisclaimer(DeployCoverage.DISCLAIMER);

        if (uncovered.isEmpty()) {
            coverage.setVerdict(DeployCoverage.VERDICT_FULL);
            coverage.setStatement("本次执行的成功部署证据覆盖了该服务/环境当前的全部 "
                    + coverage.getCurrentInstanceCount() + " 个实例（实例 " + joinIds(deployed)
                    + "）。这只表示本次执行的覆盖范围，不表示这些实例现在仍登记在该版本，"
                    + "也不表示远端实际运行版本。");
            coverage.setNextStep("本次执行已覆盖当前全部实例；如果之后新增实例或再次发布、回滚，"
                    + "覆盖范围与当前登记版本一致性会分别变化。");
            return coverage;
        }

        coverage.setVerdict(DeployCoverage.VERDICT_PARTIAL);
        coverage.setStatement("本次执行只覆盖了该服务/环境当前 " + coverage.getCurrentInstanceCount()
                + " 个实例中的 " + deployed.size() + " 个；未覆盖的 " + uncovered.size()
                + " 个实例（实例 " + joinIds(uncovered) + "）没有参与这次执行，不是部署失败。"
                + "平台没有它们达到该版本的成功部署证据。这只是本次执行的覆盖范围，"
                + "不表示当前所有实例都已登记在该版本。");
        coverage.setNextStep("平台当前的发布入口以服务/环境为范围，不能只针对上述未覆盖实例发起发布；"
                + "如需让它们到达该版本，需要按服务/环境的发布流程重新执行。"
                + "平台不会自动补发，也不会因为本次发布成功就把它们标记为已部署。");
        return coverage;
    }

    /** 把技术证据翻译成面向操作者的原因；不出现表名、迁移编号、类名或远端输出。 */
    private static String evidenceReasons(ReleaseDeployEvidenceService.DeployEvidence evidence) {
        List<String> reasons = new ArrayList<>();
        if (!evidence.attributionTableAvailable()) {
            reasons.add("平台尚未具备实例级部署归属记录，无法证明部署落在哪些实例上");
        }
        if (evidence.claimedRecordCount() == 0) {
            reasons.add("本次执行没有留下任何部署记录");
        } else if (evidence.successRecordCount() != evidence.claimedRecordCount()) {
            reasons.add("本次执行的部署记录并非全部成功（" + evidence.successRecordCount() + "/"
                    + evidence.claimedRecordCount() + "）");
        }
        if (!evidence.recordScopeMismatchRecordIds().isEmpty()) {
            reasons.add("有部署记录的服务或环境与本次执行不一致");
        }
        if (!evidence.versionMismatchRecordIds().isEmpty()) {
            reasons.add("有部署记录的目标版本与本次发布的目标版本不一致");
        }
        if (!evidence.unattributedRecordIds().isEmpty()) {
            reasons.add("有成功部署记录缺少实例归属，无法确定部署到哪个实例");
        }
        if (!evidence.ambiguousRecordIds().isEmpty()) {
            reasons.add("有成功部署记录对应多个实例归属，无法确定覆盖关系");
        }
        if (!evidence.misattributedRecordIds().isEmpty()) {
            reasons.add("有部署记录的实例归属与记录本身不一致");
        }
        if (!evidence.duplicateInstanceIds().isEmpty()) {
            reasons.add("同一实例对应多条成功部署记录，无法确定覆盖关系");
        }
        if (!evidence.missingExpectedInstanceIds().isEmpty()) {
            reasons.add("灰度批次清单中的实例没有对应的成功部署记录");
        }
        if (!evidence.unexpectedInstanceIds().isEmpty()) {
            reasons.add("存在不属于灰度批次清单的实例部署记录");
        }
        if (!evidence.removedInstanceIds().isEmpty()) {
            reasons.add("本次部署涉及的实例已被移除，无法证明版本更新落到实际部署的实例上");
        }
        if (!evidence.reattributedInstanceIds().isEmpty()) {
            reasons.add("本次部署涉及的实例已改绑服务、环境或主机");
        }
        if (reasons.isEmpty()) {
            reasons.add("没有可证明的成功实例");
        }
        return String.join("；", reasons);
    }

    private static String rejectionCode(ReleaseDeployEvidenceService.DeployEvidence evidence) {
        if (!evidence.attributionTableAvailable()) {
            return DeployCoverage.REJECTION_ATTRIBUTION_UNAVAILABLE;
        }
        if (!evidence.recordScopeMismatchRecordIds().isEmpty()) {
            return DeployCoverage.REJECTION_RECORD_SCOPE_MISMATCH;
        }
        if (!evidence.versionMismatchRecordIds().isEmpty()) {
            return DeployCoverage.REJECTION_VERSION_MISMATCH;
        }
        if (!evidence.unattributedRecordIds().isEmpty()) {
            return DeployCoverage.REJECTION_ATTRIBUTION_MISSING;
        }
        if (!evidence.ambiguousRecordIds().isEmpty()) {
            return DeployCoverage.REJECTION_ATTRIBUTION_AMBIGUOUS;
        }
        if (!evidence.misattributedRecordIds().isEmpty()) {
            return DeployCoverage.REJECTION_ATTRIBUTION_MISMATCH;
        }
        if (!evidence.duplicateInstanceIds().isEmpty()) {
            return DeployCoverage.REJECTION_DUPLICATE_INSTANCE;
        }
        if (!evidence.missingExpectedInstanceIds().isEmpty()) {
            return DeployCoverage.REJECTION_EXPECTED_INSTANCE_MISSING;
        }
        if (!evidence.unexpectedInstanceIds().isEmpty()) {
            return DeployCoverage.REJECTION_UNEXPECTED_INSTANCE;
        }
        if (!evidence.removedInstanceIds().isEmpty()) {
            return DeployCoverage.REJECTION_INSTANCE_REMOVED;
        }
        if (!evidence.reattributedInstanceIds().isEmpty()) {
            return DeployCoverage.REJECTION_INSTANCE_REBOUND;
        }
        if (evidence.claimedRecordCount() == 0) {
            return DeployCoverage.REJECTION_NO_RECORDS;
        }
        if (evidence.successRecordCount() != evidence.claimedRecordCount()) {
            return DeployCoverage.REJECTION_RECORDS_NOT_SUCCESS;
        }
        return DeployCoverage.REJECTION_NO_PROVEN_INSTANCE;
    }

    private static DeployCoverage notStarted(DeployCoverage coverage, String reason) {
        coverage.setVerdict(DeployCoverage.VERDICT_NOT_STARTED);
        coverage.setEvidenceProvable(false);
        coverage.setRejectionCode(DeployCoverage.REJECTION_NO_SOURCE);
        coverage.setRejectionReason(reason);
        coverage.setStatement(reason + "；没有覆盖范围可言，平台不对该服务/环境的实例版本作任何声明。");
        coverage.setNextStep("若该服务/环境确实需要发布，请从发布单发起一次新的发布。");
        coverage.setDisclaimer(DeployCoverage.DISCLAIMER);
        return coverage;
    }

    private static DeployCoverage unprovable(DeployCoverage coverage, String code, String reason) {
        coverage.setVerdict(DeployCoverage.VERDICT_UNPROVABLE);
        coverage.setEvidenceProvable(false);
        coverage.setRejectionCode(code);
        coverage.setRejectionReason(reason);
        coverage.setStatement(reason + "。平台不据此声明它覆盖了当前任何实例。");
        coverage.setNextStep(nextStepFor(code));
        coverage.setDisclaimer(DeployCoverage.DISCLAIMER);
        return coverage;
    }

    private static String nextStepFor(String code) {
        if (DeployCoverage.REJECTION_CLAIM_NOT_CLOSED.equals(code)
                || DeployCoverage.REJECTION_CLAIM_ABANDONED.equals(code)) {
            return "请先在执行认领页面处理这次执行的认领；平台不会自动把认领当作部署成功。";
        }
        if (DeployCoverage.REJECTION_ROLLBACK_UNPROVABLE.equals(code)) {
            return "回滚单的成功只表示回滚流程完成，不能代替实例级证据；"
                    + "如需确认实例版本，需要人工核对。";
        }
        return "若要确认覆盖范围，需要人工核对部署记录与实例归属；平台不会连接远端、不会自动补发。";
    }

    private void fillTargetVersionNo(DeployCoverage coverage) {
        if (coverage.getTargetVersionId() == null) {
            return;
        }
        ReleaseVersion version = releaseVersionService.getById(coverage.getTargetVersionId());
        if (version != null) {
            coverage.setTargetVersionNo(version.getVersionNo());
        }
    }

    private List<Long> parseBatchInstanceIds(String instanceIdsJson) {
        if (instanceIdsJson == null || instanceIdsJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(instanceIdsJson, new TypeReference<List<Long>>() {
            });
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static String planStatusText(String status) {
        if (status == null) {
            return "未知";
        }
        return switch (status) {
            case ReleaseCanaryPlan.STATUS_CREATED -> "尚未开始";
            case ReleaseCanaryPlan.STATUS_RUNNING -> "执行中";
            case ReleaseCanaryPlan.STATUS_PAUSED -> "已暂停";
            case ReleaseCanaryPlan.STATUS_SUCCESS -> "已完成";
            case ReleaseCanaryPlan.STATUS_FAILED -> "已失败";
            case ReleaseCanaryPlan.STATUS_ROLLED_BACK -> "已回滚";
            case ReleaseCanaryPlan.STATUS_BLOCKED -> "已阻塞";
            default -> "未知";
        };
    }

    private static String joinIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return "-";
        }
        return ids.stream().filter(Objects::nonNull).map(String::valueOf)
                .collect(Collectors.joining(", "));
    }
}

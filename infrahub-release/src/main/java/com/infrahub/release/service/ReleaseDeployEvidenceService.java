package com.infrahub.release.service;

import com.infrahub.common.error.ErrorCode;
import com.infrahub.common.exception.BizException;
import com.infrahub.release.entity.RuntimeDeployRecord;
import com.infrahub.release.entity.RuntimeDeployRecordInstance;
import com.infrahub.release.entity.RuntimeServiceInstance;
import com.infrahub.release.mapper.RuntimeServiceInstanceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 一次发布执行产出的**实例级证据**：把“这次部署实际成功落在哪些实例上”从持久事实里算出来。
 *
 * <p>存在的理由：流水线成功回调原先按“服务 + 环境”重新枚举实例并更新版本。执行期间新增、
 * 被改绑、被移除的实例都会被卷进这次发布，历史记录也会被事后猜测。这里改成只用这次执行
 * 自己写下的两份事实：
 * <ol>
 *   <li>这次执行认领（{@code release_execution_run_claim}）产出的部署记录；</li>
 *   <li>每条记录在创建时写下的实例归属（{@code runtime_deploy_record_instance}）。</li>
 * </ol>
 *
 * <p><b>结论只有两种：可证明 / 不可证明。</b>不可证明时调用方必须失败关闭（不采纳成功、
 * 不推进任何实例版本），而不是挑出“看起来没问题”的部分照做。
 *
 * <p>判定为不可证明的情形（全部显式实现）：
 * <ul>
 *   <li>实例归属表不可用（V34 未应用）；</li>
 *   <li>这次执行没有任何产出记录，或存在未收口（非 SUCCESS）的记录；</li>
 *   <li>成功记录没有实例归属行，或一条记录对应多条归属行；</li>
 *   <li>归属行与记录的服务/环境/主机不一致；</li>
 *   <li>归属实例已不存在（执行期间被移除）；</li>
 *   <li>实例身份已变化（服务/环境/主机与创建记录时不一致，即执行期间被改绑）。</li>
 * </ul>
 *
 * <p>**执行期间新增的实例不算失败**：它本来就没被这次执行覆盖，不会被更新版本，但会出现在
 * {@code currentInstanceIdsNotProven} 里供审计，说明“服务/环境当前实例集合与本次部署集合不一致”。
 *
 * <p><b>读法</b>：{@link #collect} 是只读核对（供人工收口预览、审计），{@link #collectUnderLock}
 * 会按实例 ID 升序锁定实例行后再核对，供终态采纳使用 —— 判定与随后的版本写入在同一事务内，
 * 期间没有任何其他会话能改写这些实例行。
 */
@Service
@RequiredArgsConstructor
public class ReleaseDeployEvidenceService {

    private static final String STATUS_SUCCESS = "SUCCESS";

    private final RuntimeDeployRecordService runtimeDeployRecordService;
    private final RuntimeDeployRecordInstanceService runtimeDeployRecordInstanceService;
    private final RuntimeServiceInstanceService runtimeServiceInstanceService;
    private final RuntimeServiceInstanceMapper runtimeServiceInstanceMapper;

    /**
     * 一次执行的实例级证据。{@code provable() == false} 时，所有拒绝理由都在
     * {@code rejectionReason()} 里，可直接写进审计（不含命令、输出、凭据）。
     *
     * @param claimId                     这次执行的认领（{@code release_execution_run_claim.id}）；
     *                                    灰度来源没有认领时为 null
     * @param claimedRecordCount          参与判定的记录总数
     * @param successRecordCount          其中状态为 SUCCESS 的数量
     * @param provenInstanceIds           可证明被这次执行成功部署的实例 ID（升序、去重）
     * @param unattributedRecordIds       没有实例归属行的成功记录
     * @param ambiguousRecordIds          对应多条归属行的记录
     * @param misattributedRecordIds      归属行与记录本身的服务/环境/主机不一致的记录
     * @param recordScopeMismatchRecordIds 记录的服务/环境与本次执行的目标不一致
     * @param versionMismatchRecordIds    记录的目标版本与本次执行的目标版本不一致
     * @param duplicateInstanceIds        同一实例对应多条成功记录
     * @param missingExpectedInstanceIds  本次执行范围（灰度批次清单）内、没有成功记录的实例
     * @param unexpectedInstanceIds       成功记录归属到本次执行范围之外的实例
     * @param removedInstanceIds          归属指向的实例已不存在
     * @param reattributedInstanceIds     归属实例的服务/环境/主机已变化（改绑）
     * @param currentInstanceIdsNotProven 该服务/环境当前存在、但本次未覆盖的实例（新增或未参与）
     * @param attributionTableAvailable   实例归属表是否可用（V34 是否已应用）
     */
    public record DeployEvidence(
            Long claimId,
            int claimedRecordCount,
            int successRecordCount,
            List<Long> provenInstanceIds,
            List<Long> unattributedRecordIds,
            List<Long> ambiguousRecordIds,
            List<Long> misattributedRecordIds,
            List<Long> recordScopeMismatchRecordIds,
            List<Long> versionMismatchRecordIds,
            List<Long> duplicateInstanceIds,
            List<Long> missingExpectedInstanceIds,
            List<Long> unexpectedInstanceIds,
            List<Long> removedInstanceIds,
            List<Long> reattributedInstanceIds,
            List<Long> currentInstanceIdsNotProven,
            boolean attributionTableAvailable
    ) {

        /** 是否可以把“这次执行成功部署了 {@code provenInstanceIds}”当作已证明的事实。 */
        public boolean provable() {
            return attributionTableAvailable
                    && !provenInstanceIds.isEmpty()
                    && claimedRecordCount > 0
                    && successRecordCount == claimedRecordCount
                    && unattributedRecordIds.isEmpty()
                    && ambiguousRecordIds.isEmpty()
                    && misattributedRecordIds.isEmpty()
                    && recordScopeMismatchRecordIds.isEmpty()
                    && versionMismatchRecordIds.isEmpty()
                    && duplicateInstanceIds.isEmpty()
                    && missingExpectedInstanceIds.isEmpty()
                    && unexpectedInstanceIds.isEmpty()
                    && removedInstanceIds.isEmpty()
                    && reattributedInstanceIds.isEmpty();
        }

        /** 不可证明时的明确理由（半角分号串联）；可证明时返回 null。 */
        public String rejectionReason() {
            if (provable()) {
                return null;
            }
            List<String> reasons = new ArrayList<>();
            if (!attributionTableAvailable) {
                reasons.add("实例归属表 runtime_deploy_record_instance 不可用（V34 未应用），无法证明部署落在哪些实例上");
            }
            if (claimedRecordCount == 0) {
                reasons.add("本次执行没有产出任何部署记录");
            } else if (successRecordCount != claimedRecordCount) {
                reasons.add("本次执行产出的部署记录并非全部成功（" + successRecordCount + "/"
                        + claimedRecordCount + "），不能当作成功部署");
            }
            if (!versionMismatchRecordIds.isEmpty()) {
                reasons.add("部署记录的目标版本与本次发布的目标版本不一致: " + joinIds(versionMismatchRecordIds));
            }
            if (!recordScopeMismatchRecordIds.isEmpty()) {
                reasons.add("部署记录的服务/环境与本次执行不一致: " + joinIds(recordScopeMismatchRecordIds));
            }
            if (!unattributedRecordIds.isEmpty()) {
                reasons.add("成功记录缺少实例归属: " + joinIds(unattributedRecordIds));
            }
            if (!ambiguousRecordIds.isEmpty()) {
                reasons.add("成功记录存在多条实例归属，归属不一致: " + joinIds(ambiguousRecordIds));
            }
            if (!misattributedRecordIds.isEmpty()) {
                reasons.add("部署记录的实例归属与记录的服务/环境/主机不一致: " + joinIds(misattributedRecordIds));
            }
            if (!duplicateInstanceIds.isEmpty()) {
                reasons.add("同一实例对应多条成功部署记录，无法确定覆盖关系: " + joinIds(duplicateInstanceIds));
            }
            if (!missingExpectedInstanceIds.isEmpty()) {
                reasons.add("本次执行范围内的实例没有对应的成功部署记录: " + joinIds(missingExpectedInstanceIds));
            }
            if (!unexpectedInstanceIds.isEmpty()) {
                reasons.add("存在不属于本次执行范围的实例部署记录: " + joinIds(unexpectedInstanceIds));
            }
            if (!removedInstanceIds.isEmpty()) {
                reasons.add("本次部署的实例已被移除，无法证明版本更新落到实际部署的实例上: "
                        + joinIds(removedInstanceIds));
            }
            if (!reattributedInstanceIds.isEmpty()) {
                reasons.add("本次部署的实例身份已变化（服务/环境/主机在执行期间被改绑）: "
                        + joinIds(reattributedInstanceIds));
            }
            if (reasons.isEmpty()) {
                reasons.add("本次执行没有可证明的成功实例");
            }
            return String.join("；", reasons);
        }

        private static String joinIds(List<Long> ids) {
            return ids.stream().map(String::valueOf).collect(Collectors.joining(", "));
        }
    }

    /**
     * 只读核对：不加锁、不写任何东西，供人工收口预览与审计使用。
     *
     * <p>不加锁意味着结果是一个快照：实例行可能在读取之后被改绑或移除。任何**据此采纳终态**的
     * 路径都必须改用 {@link #collectUnderLock}，由实例行锁保证判定与写入之间没有被改写。
     */
    public DeployEvidence collect(Long claimId, Long serviceId, Long envId) {
        return analyzeClaim(claimId, serviceId, envId, null, false, false);
    }

    /**
     * 只读核对，并把“本次执行的目标版本”纳入判定：部署记录的目标版本必须与之一致。
     *
     * <p>比 {@link #collect} 更严格，供覆盖范围读路径使用；人工收口仍使用 {@link #collect} /
     * {@link #collectUnderLock} 的既有语义，避免改变其拒绝条件。
     */
    public DeployEvidence collectForVersion(Long claimId, Long serviceId, Long envId, Long expectedVersionId) {
        return analyzeClaim(claimId, serviceId, envId, expectedVersionId, true, false);
    }

    /**
     * 加锁核对：先按实例 ID 升序逐行 {@code SELECT ... FOR UPDATE}，再比对身份。
     *
     * <p>必须在真实生效的事务内调用（锁随事务结束释放）。锁序与全项目一致：
     * {@code base_service → release_order → runtime_service_instance（ID 升序）}；
     * 灰度批次、采集写回、移除登记、监控配置写入都按实例 ID 升序加锁，因此不会与本方法互锁。
     *
     * @throws BizException 传入的认领为空
     */
    public DeployEvidence collectUnderLock(Long claimId, Long serviceId, Long envId) {
        if (claimId == null) {
            throw new BizException(ErrorCode.INTERNAL_ERROR,
                    "缺少部署执行认领标识，无法核对本次部署的实例归属");
        }
        return analyzeClaim(claimId, serviceId, envId, null, false, true);
    }

    /**
     * 核对一组已经按执行范围筛出的部署记录（灰度批次使用）。
     *
     * <p>灰度没有部署认领行，它的范围事实来自已保存的批次清单：调用方只传入**成功批次**的
     * 部署记录，并把该批次清单作为 {@code expectedInstanceIds}。批次清单与记录必须严格一一对应，
     * 否则整体不可证明——不从“批次成功”外推覆盖范围。
     */
    public DeployEvidence analyzeRecords(Long claimId, List<RuntimeDeployRecord> records,
                                         Collection<Long> expectedInstanceIds,
                                         Long serviceId, Long envId, Long expectedVersionId) {
        return analyze(claimId, records == null ? List.of() : records, expectedInstanceIds,
                serviceId, envId, expectedVersionId, true, false);
    }

    private DeployEvidence analyzeClaim(Long claimId, Long serviceId, Long envId,
                                        Long expectedVersionId, boolean enforceScope, boolean lockInstances) {
        List<RuntimeDeployRecord> records = claimId == null
                ? List.of()
                : runtimeDeployRecordService.listByExecutionClaimId(claimId);
        return analyze(claimId, records, null, serviceId, envId, expectedVersionId, enforceScope, lockInstances);
    }

    private DeployEvidence analyze(Long claimId, List<RuntimeDeployRecord> records,
                                   Collection<Long> expectedInstanceIds,
                                   Long serviceId, Long envId, Long expectedVersionId,
                                   boolean enforceScope, boolean lockInstances) {
        boolean tableAvailable = runtimeDeployRecordInstanceService.isTableAvailable();
        // 严格模式只在覆盖范围读路径启用（带目标版本或预期实例清单）；手工收口沿用既有语义，
        // 不因新增的重复实例判定而改变其拒绝条件。
        boolean strict = expectedVersionId != null || expectedInstanceIds != null;
        List<Long> recordIds = records.stream().map(RuntimeDeployRecord::getId).filter(Objects::nonNull).toList();
        List<Long> successRecordIds = records.stream()
                .filter(record -> STATUS_SUCCESS.equalsIgnoreCase(record.getDeployStatus()))
                .map(RuntimeDeployRecord::getId)
                .filter(Objects::nonNull)
                .toList();

        Map<Long, List<RuntimeDeployRecordInstance>> linksByRecord = new LinkedHashMap<>();
        for (RuntimeDeployRecordInstance link : runtimeDeployRecordInstanceService.listByDeployRecordIds(successRecordIds)) {
            linksByRecord.computeIfAbsent(link.getDeployRecordId(), key -> new ArrayList<>()).add(link);
        }

        Map<Long, RuntimeDeployRecord> successRecords = new LinkedHashMap<>();
        for (RuntimeDeployRecord record : records) {
            if (record.getId() != null && STATUS_SUCCESS.equalsIgnoreCase(record.getDeployStatus())) {
                successRecords.put(record.getId(), record);
            }
        }

        List<Long> unattributed = new ArrayList<>();
        List<Long> ambiguous = new ArrayList<>();
        List<Long> misattributed = new ArrayList<>();
        List<Long> recordScopeMismatch = new ArrayList<>();
        List<Long> versionMismatch = new ArrayList<>();
        List<Long> duplicateInstances = new ArrayList<>();
        Map<Long, RuntimeDeployRecordInstance> linkByInstance = new LinkedHashMap<>();
        for (Map.Entry<Long, RuntimeDeployRecord> entry : successRecords.entrySet()) {
            Long recordId = entry.getKey();
            RuntimeDeployRecord record = entry.getValue();
            if (enforceScope && serviceId != null && envId != null
                    && (!Objects.equals(record.getServiceId(), serviceId)
                    || !Objects.equals(record.getEnvId(), envId))) {
                recordScopeMismatch.add(recordId);
                continue;
            }
            if (expectedVersionId != null && !Objects.equals(record.getVersionId(), expectedVersionId)) {
                versionMismatch.add(recordId);
                continue;
            }
            List<RuntimeDeployRecordInstance> links = linksByRecord.getOrDefault(recordId, List.of());
            if (links.isEmpty()) {
                unattributed.add(recordId);
                continue;
            }
            if (links.size() > 1) {
                ambiguous.add(recordId);
                continue;
            }
            RuntimeDeployRecordInstance link = links.get(0);
            if (link.getInstanceId() == null) {
                unattributed.add(recordId);
                continue;
            }
            // 归属行必须与记录本身说同一件事：记录的服务/环境/主机来自执行时算出的目标，
            // 归属行由同一事务写入，两者不一致说明归属存疑（例如归属行被别处改写）。
            boolean consistent = Objects.equals(link.getServiceId(), record.getServiceId())
                    && Objects.equals(link.getEnvId(), record.getEnvId())
                    && Objects.equals(link.getHostId(), record.getHostId());
            if (!consistent) {
                misattributed.add(recordId);
                continue;
            }
            if (strict && linkByInstance.containsKey(link.getInstanceId())) {
                duplicateInstances.add(link.getInstanceId());
                continue;
            }
            linkByInstance.put(link.getInstanceId(), link);
        }

        List<Long> provenInstanceIds = new ArrayList<>(linkByInstance.keySet());
        provenInstanceIds.sort(Comparator.naturalOrder());
        recordScopeMismatch.sort(Comparator.naturalOrder());
        versionMismatch.sort(Comparator.naturalOrder());
        duplicateInstances.sort(Comparator.naturalOrder());

        List<Long> missingExpected = new ArrayList<>();
        List<Long> unexpected = new ArrayList<>();
        if (expectedInstanceIds != null) {
            Set<Long> expected = new LinkedHashSet<>(expectedInstanceIds);
            for (Long instanceId : expected) {
                if (instanceId != null && !linkByInstance.containsKey(instanceId)) {
                    missingExpected.add(instanceId);
                }
            }
            for (Long instanceId : linkByInstance.keySet()) {
                if (!expected.contains(instanceId)) {
                    unexpected.add(instanceId);
                }
            }
            missingExpected.sort(Comparator.naturalOrder());
            unexpected.sort(Comparator.naturalOrder());
        }

        List<Long> removed = new ArrayList<>();
        List<Long> reattributed = new ArrayList<>();
        for (Long instanceId : provenInstanceIds) {
            RuntimeDeployRecordInstance link = linkByInstance.get(instanceId);
            RuntimeServiceInstance current = lockInstances
                    ? runtimeServiceInstanceMapper.selectFullForUpdate(instanceId)
                    : runtimeServiceInstanceService.getById(instanceId);
            if (current == null) {
                removed.add(instanceId);
                continue;
            }
            if (!Objects.equals(current.getServiceId(), link.getServiceId())
                    || !Objects.equals(current.getEnvId(), link.getEnvId())
                    || !Objects.equals(current.getHostId(), link.getHostId())) {
                reattributed.add(instanceId);
            }
        }

        List<Long> notProven = new ArrayList<>();
        if (serviceId != null && envId != null) {
            for (RuntimeServiceInstance instance : runtimeServiceInstanceService.listByServiceAndEnv(serviceId, envId)) {
                if (instance.getId() != null && !linkByInstance.containsKey(instance.getId())) {
                    notProven.add(instance.getId());
                }
            }
            notProven.sort(Comparator.naturalOrder());
        }

        return new DeployEvidence(claimId, recordIds.size(), successRecordIds.size(), provenInstanceIds,
                unattributed, ambiguous, misattributed, recordScopeMismatch, versionMismatch,
                duplicateInstances, missingExpected, unexpected, removed, reattributed, notProven,
                tableAvailable);
    }
}

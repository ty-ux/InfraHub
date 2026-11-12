package com.infrahub.release.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.infrahub.common.error.ErrorCode;
import com.infrahub.common.exception.BizException;
import com.infrahub.release.dto.RollbackCandidateItem;
import com.infrahub.release.dto.RollbackCandidatePageResponse;
import com.infrahub.release.entity.ReleaseCurrentVersion;
import com.infrahub.release.entity.ReleaseOrder;
import com.infrahub.release.entity.ReleaseRollbackOrder;
import com.infrahub.release.entity.ReleaseVersion;
import com.infrahub.release.entity.RuntimeDeployRecord;
import com.infrahub.release.mapper.ReleaseOrderMapper;
import com.infrahub.release.mapper.ReleaseRollbackOrderMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 回滚候选版本推荐服务。
 *
 * Contract: Section 2.2, 3.4, 3.5, 7
 *
 * 此服务为只读查询服务，不创建回滚单、不修改发布单、不触发回滚执行。
 */
@Service
@RequiredArgsConstructor
public class RollbackCandidateService {

    private final ReleaseOrderMapper releaseOrderMapper;
    private final ReleaseRollbackOrderMapper releaseRollbackOrderMapper;
    private final ReleaseVersionService releaseVersionService;
    private final ReleaseCurrentVersionService releaseCurrentVersionService;
    private final RuntimeDeployRecordService runtimeDeployRecordService;
    private final RollbackExecutorSupportService rollbackExecutorSupportService;

    /**
     * 查询指定发布单的回滚候选版本列表。
     *
     * @param releaseOrderId 发布单 ID
     * @param pageNum 页码（默认 1）
     * @param pageSize 每页数量（上限 100，默认 20）
     * @return 回滚候选版本分页响应
     */
    public RollbackCandidatePageResponse getRollbackCandidates(Long releaseOrderId, Integer pageNum, Integer pageSize) {
        // 参数校验与默认值
        if (pageNum == null || pageNum < 1) {
            pageNum = 1;
        }
        if (pageSize == null || pageSize < 1) {
            pageSize = 20;
        }
        if (pageSize > 100) {
            pageSize = 100;
        }

        // 获取发布单
        ReleaseOrder order = releaseOrderMapper.selectById(releaseOrderId);
        if (order == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "发布单不存在");
        }
        // 回滚边界：候选查询同样只对 SSH 发布单开放，非 SSH 发布单不返回候选版本
        rollbackExecutorSupportService.assertRollbackSupported(order);

        // 获取当前版本
        ReleaseCurrentVersion currentVersion = releaseCurrentVersionService.lambdaQuery()
                .eq(ReleaseCurrentVersion::getEnvId, order.getEnvId())
                .eq(ReleaseCurrentVersion::getServiceId, order.getServiceId())
                .one();

        Long currentVersionId = currentVersion != null ? currentVersion.getVersionId() : null;
        ReleaseVersion currentVersionEntity = currentVersionId != null ? releaseVersionService.getById(currentVersionId) : null;
        String currentVersionNo = currentVersionEntity != null ? currentVersionEntity.getVersionNo() : null;

        // 获取候选版本列表
        List<RollbackCandidateItem> candidates = findCandidateVersions(order, currentVersionId, currentVersionNo);

        // 按 recommendationScore 降序排序
        candidates.sort((a, b) -> {
            int scoreA = a.getRecommendationScore() != null ? a.getRecommendationScore() : 0;
            int scoreB = b.getRecommendationScore() != null ? b.getRecommendationScore() : 0;
            return Integer.compare(scoreB, scoreA); // 降序
        });

        // 分页处理
        long total = candidates.size();
        int fromIndex = (pageNum - 1) * pageSize;
        int toIndex = Math.min(fromIndex + pageSize, candidates.size());

        List<RollbackCandidateItem> pageRecords;
        if (fromIndex >= candidates.size()) {
            pageRecords = Collections.emptyList();
        } else {
            pageRecords = candidates.subList(fromIndex, toIndex);
        }

        return RollbackCandidatePageResponse.builder()
                .records(pageRecords)
                .total(total)
                .pageNum(pageNum)
                .pageSize(pageSize)
                .currentVersionId(currentVersionId)
                .currentVersionNo(currentVersionNo)
                .build();
    }

    /**
     * 查找候选版本列表。
     *
     * Contract Section 7.1 候选版本来源：
     * 1. 该发布单关联的历史成功发布版本
     * 2. 当前版本之前的历史稳定版本
     * 3. 从未回滚失败的版本优先
     */
    private List<RollbackCandidateItem> findCandidateVersions(ReleaseOrder order, Long currentVersionId, String currentVersionNo) {
        // 1. 获取该服务/环境的历史成功发布单及其版本
        LambdaQueryWrapper<ReleaseOrder> orderWrapper = new LambdaQueryWrapper<>();
        orderWrapper.eq(ReleaseOrder::getServiceId, order.getServiceId())
                .eq(ReleaseOrder::getEnvId, order.getEnvId())
                .eq(ReleaseOrder::getStatus, "SUCCESS")
                .orderByDesc(ReleaseOrder::getCreatedAt);
        List<ReleaseOrder> successfulOrders = releaseOrderMapper.selectList(orderWrapper);

        // 2. 获取回滚记录以计算 rollbackCount
        LambdaQueryWrapper<ReleaseRollbackOrder> rollbackWrapper = new LambdaQueryWrapper<>();
        rollbackWrapper.eq(ReleaseRollbackOrder::getReleaseOrderId, order.getId());
        List<ReleaseRollbackOrder> rollbackOrders = releaseRollbackOrderMapper.selectList(rollbackWrapper);

        // 构建版本回滚次数映射：记录从某版本回滚的次数
        Map<Long, Integer> versionRollbackCountMap = new HashMap<>();
        // 同时记录回滚失败的版本
        Set<Long> rollbackFailedVersionIds = new HashSet<>();

        for (ReleaseRollbackOrder rollback : rollbackOrders) {
            // rollback.toVersionId 是回滚的目标版本（回滚到该版本）
            // rollback.fromVersionId 是回滚的源版本（从该版本回滚）
            // Contract 7.1 "从该版本回滚的次数" 指的是从某版本作为源版本回滚出去的次数
            Long fromVersionId = rollback.getFromVersionId();
            versionRollbackCountMap.merge(fromVersionId, 1, Integer::sum);

            // 如果回滚失败，记录目标版本（回滚失败的版本）
            if ("FAILED".equals(rollback.getStatus())) {
                rollbackFailedVersionIds.add(rollback.getToVersionId());
            }
        }

        // 3. 获取历史部署记录以确定部署时间和成功状态
        List<RuntimeDeployRecord> deployRecords = runtimeDeployRecordService.lambdaQuery()
                .eq(RuntimeDeployRecord::getServiceId, order.getServiceId())
                .eq(RuntimeDeployRecord::getEnvId, order.getEnvId())
                .eq(RuntimeDeployRecord::getDeployStatus, "SUCCESS")
                .orderByDesc(RuntimeDeployRecord::getEndedAt)
                .list();

        // 构建版本部署时间映射（取最新的成功部署时间）
        Map<Long, RuntimeDeployRecord> latestDeployByVersion = deployRecords.stream()
                .collect(Collectors.toMap(
                        RuntimeDeployRecord::getVersionId,
                        r -> r,
                        (a, b) -> a.getEndedAt() != null && b.getEndedAt() != null
                                ? (a.getEndedAt().isAfter(b.getEndedAt()) ? a : b)
                                : a
                ));

        // 4. 构建候选版本列表
        Map<Long, RollbackCandidateItem> candidateMap = new LinkedHashMap<>();

        for (ReleaseOrder successOrder : successfulOrders) {
            Long versionId = successOrder.getVersionId();
            if (versionId == null) {
                continue;
            }

            // 排除当前版本（回滚不能回滚到当前版本）
            if (currentVersionId != null && versionId.equals(currentVersionId)) {
                // 当前版本单独标记，但不排除（仍作为候选显示，标记 isCurrent=true）
                continue;
            }

            ReleaseVersion version = releaseVersionService.getById(versionId);
            if (version == null || Boolean.TRUE.equals(version.getIsDeleted())) {
                continue;
            }

            // Contract 7.1: 从未回滚失败的版本优先
            // 如果该版本曾被作为回滚目标且回滚失败，降权处理
            boolean hadRollbackFailure = rollbackFailedVersionIds.contains(versionId);

            RollbackCandidateItem item = new RollbackCandidateItem();
            item.setVersionId(versionId);
            item.setVersionNo(version.getVersionNo());
            item.setIsCurrent(false);
            item.setRollbackCount(versionRollbackCountMap.getOrDefault(versionId, 0));

            // 部署时间
            RuntimeDeployRecord deployRecord = latestDeployByVersion.get(versionId);
            if (deployRecord != null) {
                item.setDeployedAt(deployRecord.getEndedAt());
            }

            // 计算推荐分值
            int score = calculateRecommendationScore(version, item, deployRecord, hadRollbackFailure);
            item.setRecommendationScore(score);

            // 生成推荐理由
            item.setRecommendationReason(generateRecommendationReason(item, deployRecord, hadRollbackFailure));

            candidateMap.put(versionId, item);
        }

        // 5. 当前版本也加入列表（标记 isCurrent=true）
        if (currentVersionId != null && currentVersionNo != null) {
            ReleaseVersion currentVersion = releaseVersionService.getById(currentVersionId);
            if (currentVersion != null) {
                RollbackCandidateItem current = new RollbackCandidateItem();
                current.setVersionId(currentVersionId);
                current.setVersionNo(currentVersionNo);
                current.setIsCurrent(true);
                current.setRollbackCount(versionRollbackCountMap.getOrDefault(currentVersionId, 0));

                RuntimeDeployRecord currentDeploy = latestDeployByVersion.get(currentVersionId);
                if (currentDeploy != null) {
                    current.setDeployedAt(currentDeploy.getEndedAt());
                }

                // 当前版本不推荐回滚（分数低）
                current.setRecommendationScore(0);
                current.setRecommendationReason("当前正在运行的版本");

                candidateMap.put(currentVersionId, current);
            }
        }

        return new ArrayList<>(candidateMap.values());
    }

    /**
     * 计算推荐分值。
     *
     * Contract Section 7.2 推荐分值计算：
     * - 最近部署成功: +30 分
     * - 从未回滚过: +20 分
     * - 版本号稳定（非快照）: +10 分
     * - 非当前版本: +10 分
     * - 有历史运行记录: +10 分
     * - 回滚次数少: +10 分/次（从 100 扣减）
     *
     * @param version 版本实体
     * @param item 候选项
     * @param deployRecord 部署记录
     * @param hadRollbackFailure 是否曾有回滚失败
     * @return 推荐分值（0-100）
     */
    private int calculateRecommendationScore(ReleaseVersion version, RollbackCandidateItem item,
                                              RuntimeDeployRecord deployRecord, boolean hadRollbackFailure) {
        int score = 100;

        // 回滚次数扣减：每回滚一次扣 10 分
        int rollbackCount = item.getRollbackCount() != null ? item.getRollbackCount() : 0;
        score -= rollbackCount * 10;

        // 回滚失败版本额外扣减 20 分
        if (hadRollbackFailure) {
            score -= 20;
        }

        // 最近部署成功: +30 分（但基于扣减后的基础分）
        if (deployRecord != null && "SUCCESS".equals(deployRecord.getDeployStatus())) {
            score += 30;
        }

        // 从未回滚过: +20 分
        if (rollbackCount == 0) {
            score += 20;
        }

        // 版本号稳定（非快照）: +10 分
        String versionNo = version.getVersionNo();
        if (versionNo != null && !isSnapshotVersion(versionNo)) {
            score += 10;
        }

        // 非当前版本: +10 分（已在 findCandidateVersions 中排除当前版本，此处统一加分）
        if (!Boolean.TRUE.equals(item.getIsCurrent())) {
            score += 10;
        }

        // 有历史运行记录: +10 分
        if (deployRecord != null) {
            score += 10;
        }

        // 确保分值在 0-100 范围内
        return Math.max(0, Math.min(100, score));
    }

    /**
     * 判断是否为快照版本。
     *
     * 快照版本通常包含 SNAPSHOT、-dev、-alpha、-beta 等标识。
     */
    private boolean isSnapshotVersion(String versionNo) {
        if (versionNo == null) {
            return false;
        }
        String lower = versionNo.toLowerCase();
        return lower.contains("snapshot")
                || lower.contains("-dev")
                || lower.contains("-alpha")
                || lower.contains("-beta")
                || lower.contains("-rc")
                || lower.contains("-preview");
    }

    /**
     * 生成推荐理由。
     *
     * Contract Section 7.3 推荐理由模板：
     * - "最近一次成功部署版本"
     * - "从未回滚的稳定版本"
     * - "历史运行时间最长的版本"
     * - "上一次成功回滚的目标版本"
     */
    private String generateRecommendationReason(RollbackCandidateItem item, RuntimeDeployRecord deployRecord,
                                                 boolean hadRollbackFailure) {
        List<String> reasons = new ArrayList<>();

        // 从未回滚
        if (item.getRollbackCount() != null && item.getRollbackCount() == 0) {
            reasons.add("从未回滚的稳定版本");
        }

        // 有成功部署记录
        if (deployRecord != null && "SUCCESS".equals(deployRecord.getDeployStatus())) {
            reasons.add("有成功部署记录");
        }

        // 版本号稳定
        String versionNo = item.getVersionNo();
        if (versionNo != null && !isSnapshotVersion(versionNo)) {
            reasons.add("正式版本");
        }

        // 回滚失败的版本
        if (hadRollbackFailure) {
            reasons.add("曾有回滚失败记录（建议谨慎选择）");
        }

        // 组合理由
        if (reasons.isEmpty()) {
            return "历史版本";
        }
        return String.join("，", reasons);
    }
}
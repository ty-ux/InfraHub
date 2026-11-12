package com.infrahub.release.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.infrahub.common.error.ErrorCode;
import com.infrahub.common.exception.BizException;
import com.infrahub.release.entity.RuntimeDeployRecord;
import com.infrahub.release.entity.RuntimeExecutionUnknown;
import com.infrahub.release.mapper.RuntimeDeployRecordMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.OffsetDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class RuntimeDeployRecordService extends ServiceImpl<RuntimeDeployRecordMapper, RuntimeDeployRecord> {

    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_FAILED = "FAILED";
    private static final String STATUS_UNKNOWN = "UNKNOWN";

    /** 兜底日志：执行被中断且没有留下任何原始失败原因时使用，保证失败记录日志非空。 */
    private static final String INTERRUPTED_LOG = "发布执行中断，未返回详细日志";

    private final RuntimeExecutionUnknownService runtimeExecutionUnknownService;
    private final RuntimeDeployRecordInstanceService runtimeDeployRecordInstanceService;

    /**
     * 外部执行前的 RUNNING 落库，独立短事务（REQUIRES_NEW）提交。
     *
     * <p>该记录必须在调用外部执行器（SSH / 执行器 / kubectl）之前就已经对外可见，
     * 否则外部连接只能看到“订单 CREATED、部署记录为空”。同时独立事务意味着
     * 调用方即使处于长事务中，等待外部动作期间也不会让这条 RUNNING 记录跟着未提交。
     *
     * <p><b>归属在创建时确定</b>：记录带 {@code executionClaimId} 时，就在本事务内按记录 ID
     * 把它绑定到那次执行认领。绑定与插入同事务提交，因此不存在“记录已存在但归属未知”的中间态；
     * 也不按发布单回填，历史记录、回滚记录和别的执行者的记录不会被归给本次认领。
     * 没有认领的执行路径（直接发布、灰度批次）不写该列：它们的凭证是订单/批次的条件更新，
     * 平台不去伪造一条并不存在的归属。
     *
     * <p><b>实例归属同样在创建时确定</b>：记录带实例身份（{@code instanceId}）时，本事务内把它
     * 写入 {@code runtime_deploy_record_instance}。这条归属是成功回调判定“版本该更新给哪些实例”的
     * 唯一依据：事后按服务+环境重新枚举会带上执行期间新增、实际并未部署的实例，因此不允许推断。
     * 写入失败（含 V34 未应用）会让整个短事务回滚，外部执行尚未开始就被拒绝。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void startRunning(RuntimeDeployRecord record) {
        saveWithInstance(record);
    }

    @Transactional
    public void saveWithInstance(RuntimeDeployRecord record) {
        save(record);
        bindToExecutionClaimIfPresent(record);
        runtimeDeployRecordInstanceService.bindRecordToInstance(record);
    }

    /**
     * 记录带认领身份时按记录 ID 精确绑定。列只存在于 V32 之后，
     * 而认领身份只可能来自 {@code release_execution_run_claim}（取得认领时 V32 必须已应用），
     * 因此这里不需要额外的迁移探测。
     */
    private void bindToExecutionClaimIfPresent(RuntimeDeployRecord record) {
        Long claimId = record.getExecutionClaimId();
        if (claimId == null) {
            return;
        }
        if (record.getId() == null) {
            throw new BizException(ErrorCode.INTERNAL_ERROR,
                    "部署记录未返回身份，无法绑定执行认领，已拒绝继续执行");
        }
        if (baseMapper.bindRecordToExecutionClaim(record.getId(), claimId) != 1) {
            // 刚插入的行不可能已经归属别的认领；绑定失败说明这条记录的归属存疑，
            // 必须让整个短事务回滚（RUNNING 记录一起消失），而不是带着错误归属继续执行。
            throw new BizException(ErrorCode.INTERNAL_ERROR,
                    "部署记录 " + record.getId() + " 绑定执行认领 " + claimId
                            + " 未生效，已拒绝继续执行");
        }
    }

    /**
     * 单机终态落库，同样独立短事务提交：外部动作已经发生，其成功/失败证据不得因外围事务回滚而消失。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finish(RuntimeDeployRecord record) {
        finishIfRunning(record);
    }

    /**
     * 结果未知的终态落库：写 UNKNOWN 与登记“未解除的未知事实”在**同一个**独立事务内提交。
     *
     * <p>不能用 {@link #finish} 之后再单独登记：两次提交之间存在空窗，订单可能已经收敛为 FAILED、
     * 部署记录已是 UNKNOWN，但阻断查询还看不到任何未解除事实，新发布/回滚就能穿过。
     * 这里若登记失败，整个事务回滚，记录保持 RUNNING，仍被“执行中”检查挡住。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finishUnknown(RuntimeDeployRecord record, String detectionSource, String reason) {
        finishIfRunning(record);
        runtimeExecutionUnknownService.registerFromDeployRecord(record, detectionSource, reason);
    }

    private void finishIfRunning(RuntimeDeployRecord record) {
        if (record == null || record.getId() == null || !update(record,
                new LambdaQueryWrapper<RuntimeDeployRecord>()
                        .eq(RuntimeDeployRecord::getId, record.getId())
                        .eq(RuntimeDeployRecord::getDeployStatus, STATUS_RUNNING))) {
            throw new BizException(ErrorCode.INTERNAL_ERROR,
                    "部署记录已不处于 RUNNING，拒绝覆盖已有结果；请人工核对");
        }
    }

    /**
     * 失败兜底证据：把指定发布单下仍停在 RUNNING 的部署记录收敛为 FAILED，
     * 补齐 endedAt 与非空日志；已有日志（原始失败原因）只追加、不覆盖。
     *
     * <p>用于执行异常导致终态没有写成的情况（异常抛出、结果未回写），
     * 保证失败一定有可查证据；不做重试、不做自动回滚、不改动成功记录。
     *
     * @return 实际被收敛的记录数
     */
    @Transactional
    public int failRunningRecordsByReleaseOrderId(Long releaseOrderId, String failureReason) {
        List<RuntimeDeployRecord> runningRecords = lambdaQuery()
                .eq(RuntimeDeployRecord::getReleaseOrderId, releaseOrderId)
                .eq(RuntimeDeployRecord::getDeployStatus, STATUS_RUNNING)
                .list();
        if (runningRecords == null || runningRecords.isEmpty()) {
            return 0;
        }

        String reason = StringUtils.hasText(failureReason) ? failureReason : INTERRUPTED_LOG;
        int patched = 0;
        for (RuntimeDeployRecord record : runningRecords) {
            // 条件更新：并发写入的 UNKNOWN（以及它带来的未解除阻断事实）不会被这次失败覆盖成 FAILED。
            if (baseMapper.markFailedIfRunning(record.getId(), reason) != 1) {
                continue;
            }
            record.setDeployStatus(STATUS_FAILED);
            if (record.getEndedAt() == null) {
                record.setEndedAt(OffsetDateTime.now());
            }
            record.setDeployLog(appendFailureReason(record.getDeployLog(), reason));
            patched++;
        }
        return patched;
    }

    /** 保留原始失败原因：已有日志时追加而不是覆盖。 */
    private static String appendFailureReason(String existingLog, String failureReason) {
        if (!StringUtils.hasText(existingLog)) {
            return failureReason;
        }
        if (!StringUtils.hasText(failureReason) || existingLog.contains(failureReason)) {
            return existingLog;
        }
        return existingLog + "\n\n" + failureReason;
    }

    /**
     * 结果未知兜底：把指定发布单下仍停在 RUNNING 的部署记录收敛为 UNKNOWN。
     *
     * <p>与 {@link #failRunningRecordsByReleaseOrderId} 的区别是**不把未知写成失败**：异常中断、
     * 进程崩溃或终态写入失败时，远端动作可能已经执行或仍在运行，只有 UNKNOWN 是对事实的诚实表示。
     * 前端把 UNKNOWN 登记为“尚未确认”，不会显示成“失败”。
     *
     * @return 实际被收敛的记录数
     */
    @Transactional
    public int markRunningRecordsUnknownByReleaseOrderId(Long releaseOrderId, String reason) {
        List<RuntimeDeployRecord> runningRecords = lambdaQuery()
                .eq(RuntimeDeployRecord::getReleaseOrderId, releaseOrderId)
                .eq(RuntimeDeployRecord::getDeployStatus, STATUS_RUNNING)
                .list();
        return markUnknown(runningRecords, reason, "发布单 " + releaseOrderId);
    }

    /**
     * 回滚执行的结果未知兜底：与发布单版本相同，UNKNOWN 写入与未解除登记在同一事务内完成。
     */
    @Transactional
    public int markRunningRecordsUnknownByRollbackOrderId(Long rollbackOrderId, String reason) {
        List<RuntimeDeployRecord> runningRecords = lambdaQuery()
                .eq(RuntimeDeployRecord::getRollbackOrderId, rollbackOrderId)
                .eq(RuntimeDeployRecord::getDeployStatus, STATUS_RUNNING)
                .list();
        return markUnknown(runningRecords, reason, "回滚单 " + rollbackOrderId);
    }

    private int markUnknown(List<RuntimeDeployRecord> runningRecords, String reason, String targetLabel) {
        if (runningRecords == null || runningRecords.isEmpty()) {
            return 0;
        }
        String note = StringUtils.hasText(reason) ? reason : INTERRUPTED_LOG;
        int patched = 0;
        for (RuntimeDeployRecord record : runningRecords) {
            // 条件更新：并发的终态写入已经落下时不覆盖，也不重复登记。
            if (baseMapper.markUnknownIfRunning(record.getId(), note) == 1) {
                record.setDeployStatus(STATUS_UNKNOWN);
                runtimeExecutionUnknownService.registerFromDeployRecord(
                        record, RuntimeExecutionUnknown.DETECTION_TERMINAL_CLEANUP,
                        targetLabel + " 终态兜底：" + note);
                patched++;
            }
        }
        return patched;
    }

    public List<RuntimeDeployRecord> listByReleaseOrderId(Long releaseOrderId) {
        return lambdaQuery()
                .eq(RuntimeDeployRecord::getReleaseOrderId, releaseOrderId)
                .orderByDesc(RuntimeDeployRecord::getCreatedAt)
                .list();
    }

    /**
     * 某次执行认领**产出**的部署记录（升序）。
     *
     * <p>归属在记录创建时按记录 ID 写入，之后不再回填，因此这份结果集不会随发布单的其它记录变化：
     * 它是核对“这次认领到底部署了什么”的唯一口径，也是流水线成功回调的判断依据。
     * 不能按发布单查询代替——同一发布单可能有历史记录、回滚记录，或不由认领产生的记录。
     *
     * <p>引用 {@code execution_claim_id} 列，只有存在认领行（V32 已应用）时才可调用；
     * 调用方在认领为 null 时不应调用本方法。
     */
    public List<RuntimeDeployRecord> listByExecutionClaimId(Long executionClaimId) {
        return baseMapper.listByExecutionClaimId(executionClaimId);
    }

    public List<RuntimeDeployRecord> listByRollbackOrderId(Long rollbackOrderId) {
        List<RuntimeDeployRecord> records = lambdaQuery()
                .eq(RuntimeDeployRecord::getRollbackOrderId, rollbackOrderId)
                .orderByDesc(RuntimeDeployRecord::getCreatedAt)
                .list();
        var instanceByRecord = runtimeDeployRecordInstanceService.listByDeployRecordIds(records.stream().map(RuntimeDeployRecord::getId).toList())
                .stream().collect(java.util.stream.Collectors.toMap(binding -> binding.getDeployRecordId(), binding -> binding));
        for (RuntimeDeployRecord record : records) {
            var instance = instanceByRecord.get(record.getId());
            if (instance != null) {
                record.setInstanceId(instance.getInstanceId());
                record.setInstanceName(instance.getInstanceName());
                record.setInstanceType(instance.getInstanceType());
            }
        }
        return records;
    }

    public RuntimeDeployRecord getLatestFailedByRollbackOrderId(Long rollbackOrderId) {
        return lambdaQuery()
                .eq(RuntimeDeployRecord::getRollbackOrderId, rollbackOrderId)
                .eq(RuntimeDeployRecord::getDeployStatus, "FAILED")
                .orderByDesc(RuntimeDeployRecord::getCreatedAt)
                .last("LIMIT 1")
                .one();
    }

    public RuntimeDeployRecord getLatestFailedByReleaseOrderId(Long releaseOrderId) {
        return lambdaQuery()
                .eq(RuntimeDeployRecord::getReleaseOrderId, releaseOrderId)
                .eq(RuntimeDeployRecord::getDeployStatus, "FAILED")
                .orderByDesc(RuntimeDeployRecord::getCreatedAt)
                .last("LIMIT 1")
                .one();
    }

    public RuntimeDeployRecord getLatestByReleaseOrderId(Long releaseOrderId) {
        return lambdaQuery()
                .eq(RuntimeDeployRecord::getReleaseOrderId, releaseOrderId)
                .orderByDesc(RuntimeDeployRecord::getCreatedAt)
                .last("LIMIT 1")
                .one();
    }

    public RuntimeDeployRecord getLatestByRollbackOrderId(Long rollbackOrderId) {
        return lambdaQuery()
                .eq(RuntimeDeployRecord::getRollbackOrderId, rollbackOrderId)
                .orderByDesc(RuntimeDeployRecord::getCreatedAt)
                .last("LIMIT 1")
                .one();
    }

    public boolean existsByReleaseOrderId(Long releaseOrderId) {
        return lambdaQuery()
                .eq(RuntimeDeployRecord::getReleaseOrderId, releaseOrderId)
                .exists();
    }

    public boolean existsByRollbackOrderId(Long rollbackOrderId) {
        return lambdaQuery()
                .eq(RuntimeDeployRecord::getRollbackOrderId, rollbackOrderId)
                .exists();
    }
}

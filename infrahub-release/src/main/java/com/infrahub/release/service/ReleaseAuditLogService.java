package com.infrahub.release.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.infrahub.common.context.UserContextHolder;
import com.infrahub.common.error.ErrorCode;
import com.infrahub.common.exception.BizException;
import com.infrahub.release.mapper.SysAuditLogMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ReleaseAuditLogService {

    private static final String BIZ_TYPE_RELEASE_ORDER = "RELEASE_ORDER";

    private final SysAuditLogMapper sysAuditLogMapper;
    private final ObjectMapper objectMapper;

    public void recordPipelineReleaseTriggered(Long releaseOrderId, Long pipelineId, Long pipelineRunId) {
        record(releaseOrderId, "PIPELINE_RELEASE_TRIGGERED", "SUCCESS",
                Map.of("status", "CREATED_OR_APPROVED"),
                auditData("status", "RUNNING", "pipelineId", pipelineId, "pipelineRunId", pipelineRunId));
    }

    public void recordPipelineReleaseSuccess(Long releaseOrderId, Long pipelineRunId) {
        record(releaseOrderId, "PIPELINE_RELEASE_SUCCESS", "SUCCESS",
                Map.of("status", "RUNNING"),
                auditData("status", "SUCCESS", "pipelineRunId", pipelineRunId));
    }

    /**
     * 流水线成功终态被采纳时的审计，附带**版本实际更新到了哪些实例**。
     *
     * <p>记录 {@code updatedInstanceIds} 是这次执行自己产出的部署记录所证明的实例集合，
     * 不是回调时按服务+环境重新枚举的结果；{@code currentInstanceIdsNotDeployed} 是当时该服务/环境
     * 存在但本次未部署的实例（例如执行期间新增）：它们**没有**被更新版本，单独列出来供事后核对，
     * 避免“服务/环境整体已升级”的错误印象。
     */
    public void recordPipelineReleaseSuccess(Long releaseOrderId, Long pipelineRunId, Long claimId,
                                            List<Long> updatedInstanceIds,
                                            List<Long> currentInstanceIdsNotDeployed) {
        record(releaseOrderId, "PIPELINE_RELEASE_SUCCESS", "SUCCESS",
                Map.of("status", "RUNNING"),
                auditData("status", "SUCCESS",
                        "pipelineRunId", pipelineRunId,
                        "claimId", claimId,
                        "updatedInstanceIds", updatedInstanceIds,
                        "currentInstanceIdsNotDeployed", currentInstanceIdsNotDeployed,
                        "versionUpdatedFrom", "本执行产出的部署记录所属实例",
                        "note", currentInstanceIdsNotDeployed == null || currentInstanceIdsNotDeployed.isEmpty()
                                ? "版本已更新到本次执行实际部署成功的实例"
                                : "版本只更新到本次执行实际部署成功的实例；该服务/环境下仍有未被本次部署覆盖的实例，"
                                + "它们保持原版本，需另行处理"));
    }

    /**
     * 流水线**成功**结果因实例归属证据不足被拒绝采纳时的审计。
     *
     * <p>订单保持执行中（不写任何终态），也不更新任何实例版本；只如实记录拒绝理由，
     * 由人工核对后走恢复/收口路径。
     */
    public void recordPipelineReleaseEvidenceRejected(Long releaseOrderId, Long pipelineRunId, Long claimId,
                                                      String reason) {
        record(releaseOrderId, "PIPELINE_RELEASE_EVIDENCE_REJECTED", "IGNORED",
                Map.of("status", "RUNNING"),
                auditData("applied", false,
                        "pipelineRunId", pipelineRunId,
                        "claimId", claimId,
                        "reason", reason == null ? "" : reason,
                        "note", "无法证明这次部署实际成功落在哪些实例上，未采纳成功；订单保持执行中，需人工核对"));
    }

    public void recordPipelineReleaseFailed(Long releaseOrderId, Long pipelineRunId, String errorMessage) {
        record(releaseOrderId, "PIPELINE_RELEASE_FAILED", "FAILED",
                Map.of("status", "RUNNING"),
                auditData("status", "FAILED", "pipelineRunId", pipelineRunId, "errorMessage", errorMessage == null ? "" : errorMessage));
    }

    /**
     * 流水线失败终态被采纳时的审计，附带**实际残留事实**：还有多少条部署记录停在 RUNNING、
     * 同一服务/环境是否存在未解除的结果未知事实。
     *
     * <p>记录这些字段是为了让事后核对看出“这次失败是不是干净的失败”：订单已 FAILED 并不等于
     * 远端动作已经结束，也不等于没有未解除的未知事实。这里不改写任何状态，只如实记录。
     */
    public void recordPipelineReleaseFailed(Long releaseOrderId, Long pipelineRunId, String errorMessage,
                                           long runningDeployRecords, boolean unresolvedUnknownExists) {
        record(releaseOrderId, "PIPELINE_RELEASE_FAILED", "FAILED",
                Map.of("status", "RUNNING"),
                auditData("status", "FAILED",
                        "pipelineRunId", pipelineRunId,
                        "errorMessage", errorMessage == null ? "" : errorMessage,
                        "runningDeployRecords", runningDeployRecords,
                        "unresolvedUnknownExists", unresolvedUnknownExists,
                        "note", runningDeployRecords > 0 || unresolvedUnknownExists
                                ? "失败终态已采纳，但仍存在未收口的执行事实，需人工核对"
                                : "失败终态已采纳，无残留执行事实"));
    }

    /**
     * 迟到的流水线终态没有被采纳（订单已经不在执行中）。
     *
     * <p>典型场景：人工把卡住的执行恢复为“结果未知”并收敛为 FAILED 之后，流水线结束事件才到达。
     * 这里只记录送达事实与订单当时的实际状态，不改写订单、不解除未知事实、不重试也不回滚；
     * 该结论仍然需要人工核对。
     */
    public void recordPipelineReleaseTerminalIgnored(Long releaseOrderId, Long pipelineRunId,
                                                     String observedStatus, String orderStatus,
                                                     String errorMessage) {
        record(releaseOrderId, "PIPELINE_RELEASE_TERMINAL_IGNORED", "IGNORED",
                Map.of("status", orderStatus == null ? "UNKNOWN" : orderStatus),
                auditData("observedStatus", observedStatus,
                        "applied", false,
                        "pipelineRunId", pipelineRunId,
                        "errorMessage", errorMessage == null ? "" : errorMessage,
                        "note", "订单已不在执行中，迟到的流水线结果未采纳，需人工核对"));
    }

    /**
     * 操作人放弃一条仍为 HELD 的发布执行认领。
     *
     * <p>只记录“谁在什么时候以什么理由放弃了执行权”。它不声明远端已经停止，也不解除任何未解除的
     * 结果未知事实；事后核对仍必须以未知事实页面与部署记录为准。
     */
    public void recordExecutionClaimAbandoned(Long releaseOrderId, Long claimId, String note) {
        record(releaseOrderId, "RELEASE_EXECUTION_CLAIM_ABANDONED", "SUCCESS",
                Map.of("claimStatus", "HELD"),
                auditData("claimId", claimId,
                        "claimStatus", "RELEASED",
                        "releaseOutcome", "ABANDONED",
                        "abandonNote", note == null ? "" : note,
                        "remoteStateVerified", false,
                        "disclaimer", "只表示平台不再认为这次执行在飞，不代表远端已停止"));
    }

    /**
     * 操作人对一个卡住的发布单给出**人工结论**并收口时的审计。
     *
     * <p>{@code outcome} 是操作人的结论（不是平台对远端的探测）：平台不连接远端、不重试、不回滚，
     * 也不从超时推断远端已停止。审计里同时记下平台当时能证明的事实（更新了哪些实例的版本、
     * 还剩多少未解除的未知事实、阻断是否仍然存在），以便事后区分“操作人结论”与“平台证据”。
     */
    public void recordManualReleaseClosure(Long releaseOrderId, String outcome, Long claimId, String note,
                                           List<Long> updatedInstanceIds,
                                           List<Long> currentInstanceIdsNotDeployed,
                                           int unresolvedFactsKept, boolean blockingRemains) {
        record(releaseOrderId, "RELEASE_ORDER_MANUAL_CLOSURE", "SUCCESS",
                Map.of("status", "RUNNING"),
                auditData("status", "SUCCEEDED".equalsIgnoreCase(outcome) ? "SUCCESS" : "FAILED",
                        "outcome", outcome,
                        "claimId", claimId,
                        "operatorNote", note == null ? "" : note,
                        "updatedInstanceIds", updatedInstanceIds,
                        "currentInstanceIdsNotDeployed", currentInstanceIdsNotDeployed,
                        "unresolvedFactsKept", unresolvedFactsKept,
                        "blockingRemains", blockingRemains,
                        "remoteStateVerified", false,
                        "disclaimer", "结论来自操作人；平台未连接远端、未重试、未回滚，也不从超时推断远端已停止"));
    }

    private void record(Long releaseOrderId, String action, String result, Map<String, ?> beforeData, Map<String, ?> afterData) {        sysAuditLogMapper.insertAuditLog(
                UserContextHolder.getUserIdOrDefault(1L),
                BIZ_TYPE_RELEASE_ORDER,
                releaseOrderId,
                action,
                toJson(beforeData),
                toJson(afterData),
                result,
                null
        );
    }

    private String toJson(Map<String, ?> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "审计日志序列化失败");
        }
    }

    private static Map<String, Object> auditData(Object... entries) {
        Map<String, Object> value = new LinkedHashMap<>();
        for (int index = 0; index < entries.length; index += 2) {
            value.put((String) entries[index], entries[index + 1]);
        }
        return value;
    }
}

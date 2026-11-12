package com.infrahub.release.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.infrahub.release.dto.*;
import com.infrahub.release.entity.ResourceGovernanceActionExecution;
import com.infrahub.release.entity.ResourceGovernanceActionPlan;
import com.infrahub.release.enums.ActionExecutionStatus;
import com.infrahub.release.enums.ActionPlanStatus;
import com.infrahub.release.enums.ExecutionMode;
import com.infrahub.release.mapper.ResourceGovernanceActionExecutionMapper;
import com.infrahub.release.mapper.ResourceGovernanceActionPlanMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Service
@RequiredArgsConstructor
public class ResourceGovernanceActionExecutionService {

    private final ResourceGovernanceActionExecutionMapper executionMapper;
    private final ResourceGovernanceActionPlanMapper actionPlanMapper;
    private final ResourceGovernanceRecordService governanceRecordService;

    /**
     * 查询指定计划的所有执行记录
     */
    public Page<ResourceGovernanceActionExecutionResponse> listByPlan(Long planId, ResourceGovernanceActionExecutionQuery query) {
        Page<ResourceGovernanceActionExecution> page = new Page<>(query.getPageNum(), query.getPageSize());
        Page<ResourceGovernanceActionExecution> resultPage = executionMapper.selectByPlanId(page, planId);

        Page<ResourceGovernanceActionExecutionResponse> responsePage = new Page<>(
                resultPage.getCurrent(),
                resultPage.getSize(),
                resultPage.getTotal()
        );
        responsePage.setRecords(resultPage.getRecords().stream()
                .map(this::toResponse)
                .toList());
        return responsePage;
    }

    /**
     * 根据ID获取执行记录
     */
    public ResourceGovernanceActionExecutionResponse getById(Long id) {
        ResourceGovernanceActionExecution execution = executionMapper.selectById(id);
        if (execution == null) {
            return null;
        }
        return toResponse(execution);
    }

    /**
     * 启动执行
     * 只有 CONFIRMED 状态的计划可以启动
     * 同一个计划不能同时存在 RUNNING 的执行记录
     */
    @Transactional
    public ResourceGovernanceActionExecutionResponse start(Long planId, Long operatorId) {
        ResourceGovernanceActionPlan plan = actionPlanMapper.selectById(planId);
        if (plan == null) {
            throw new IllegalStateException("执行计划不存在: id=" + planId);
        }

        if (plan.getStatus() != ActionPlanStatus.CONFIRMED) {
            throw new IllegalStateException("只有已确认的计划可以启动执行: planId=" + planId + ", status=" + plan.getStatus());
        }

        ResourceGovernanceActionExecution existingRunning = executionMapper.selectRunningByPlanId(planId);
        if (existingRunning != null) {
            throw new IllegalStateException("该计划已有正在执行的记录: planId=" + planId + ", executionId=" + existingRunning.getId());
        }

        ResourceGovernanceActionExecution execution = new ResourceGovernanceActionExecution();
        execution.setExecutionNo(generateExecutionNo());
        execution.setPlanId(planId);
        execution.setSuggestionId(plan.getSuggestionId());
        execution.setActionType(plan.getActionType());
        execution.setTargetType(plan.getTargetType());
        execution.setTargetId(plan.getTargetId());
        execution.setStatus(ActionExecutionStatus.RUNNING);
        execution.setExecutionMode(ExecutionMode.MANUAL_ONLY);
        execution.setOperatorId(operatorId);
        execution.setStartedAt(OffsetDateTime.now());

        executionMapper.insert(execution);

        recordExecutionStarted(execution, operatorId);

        return toResponse(execution);
    }

    /**
     * 完成执行
     * 只允许 RUNNING -> SUCCESS，同时将计划状态改为 EXECUTED
     */
    @Transactional
    public void complete(Long executionId, CompleteActionExecutionRequest request, Long operatorId) {
        ResourceGovernanceActionExecution execution = executionMapper.selectById(executionId);
        if (execution == null) {
            throw new IllegalStateException("执行记录不存在: id=" + executionId);
        }

        if (execution.getStatus() != ActionExecutionStatus.RUNNING) {
            throw new IllegalStateException("只有运行中的执行记录可以完成: executionId=" + executionId + ", status=" + execution.getStatus());
        }

        execution.setStatus(ActionExecutionStatus.SUCCESS);
        execution.setFinishedAt(OffsetDateTime.now());
        if (request != null) {
            execution.setResultSummary(request.getResultSummary());
            execution.setEvidencePayload(request.getEvidencePayload());
        }
        executionMapper.updateById(execution);

        ResourceGovernanceActionPlan plan = actionPlanMapper.selectById(execution.getPlanId());
        if (plan != null) {
            plan.setStatus(ActionPlanStatus.EXECUTED);
            plan.setExecutedAt(OffsetDateTime.now());
            actionPlanMapper.updateById(plan);
        }

        recordExecutionCompleted(execution, request, operatorId);
    }

    /**
     * 标记执行失败
     * 只允许 RUNNING -> FAILED，同时将计划状态改为 FAILED
     */
    @Transactional
    public void fail(Long executionId, FailActionExecutionRequest request, Long operatorId) {
        ResourceGovernanceActionExecution execution = executionMapper.selectById(executionId);
        if (execution == null) {
            throw new IllegalStateException("执行记录不存在: id=" + executionId);
        }

        if (execution.getStatus() != ActionExecutionStatus.RUNNING) {
            throw new IllegalStateException("只有运行中的执行记录可以标记失败: executionId=" + executionId + ", status=" + execution.getStatus());
        }

        execution.setStatus(ActionExecutionStatus.FAILED);
        execution.setFinishedAt(OffsetDateTime.now());
        if (request != null) {
            execution.setFailureReason(request.getFailureReason());
            execution.setEvidencePayload(request.getEvidencePayload());
        }
        executionMapper.updateById(execution);

        ResourceGovernanceActionPlan plan = actionPlanMapper.selectById(execution.getPlanId());
        if (plan != null) {
            plan.setStatus(ActionPlanStatus.FAILED);
            actionPlanMapper.updateById(plan);
        }

        recordExecutionFailed(execution, request, operatorId);
    }

    /**
     * 取消执行
     * 只允许 RUNNING -> CANCELLED，不自动修改计划状态
     */
    @Transactional
    public void cancel(Long executionId, CancelActionExecutionRequest request, Long operatorId) {
        ResourceGovernanceActionExecution execution = executionMapper.selectById(executionId);
        if (execution == null) {
            throw new IllegalStateException("执行记录不存在: id=" + executionId);
        }

        if (execution.getStatus() != ActionExecutionStatus.RUNNING) {
            throw new IllegalStateException("只有运行中的执行记录可以取消: executionId=" + executionId + ", status=" + execution.getStatus());
        }

        execution.setStatus(ActionExecutionStatus.CANCELLED);
        execution.setFinishedAt(OffsetDateTime.now());
        executionMapper.updateById(execution);

        recordExecutionCancelled(execution, request, operatorId);
    }

    /**
     * K-A: 记录执行影响
     * 用于人工回填执行后的实际影响信息
     * 不改变执行状态，仅追加影响信息并写入治理记录
     */
    @Transactional
    public ActionExecutionImpactResponse recordImpact(Long executionId, RecordImpactRequest request, Long operatorId) {
        ResourceGovernanceActionExecution execution = executionMapper.selectById(executionId);
        if (execution == null) {
            throw new IllegalStateException("执行记录不存在: id=" + executionId);
        }

        // 更新影响信息
        execution.setImpactType(request.getImpactType());
        execution.setImpactSummary(request.getImpactSummary());
        execution.setImpactPayload(request.getImpactPayload());
        execution.setImpactRecordedAt(OffsetDateTime.now());

        // 合并 before/after/evidence 到 impactPayload（如果需要单独保存可扩展实体）
        // 这里按 Contract 只存储 impactPayload，before/after/evidence 写入治理记录
        executionMapper.updateById(execution);

        // 写入治理记录（best-effort）
        recordImpactRecorded(execution, request, operatorId);

        return ActionExecutionImpactResponse.builder()
                .executionId(execution.getId())
                .executionNo(execution.getExecutionNo())
                .impactType(request.getImpactType())
                .impactSummary(request.getImpactSummary())
                .impactPayload(request.getImpactPayload())
                .beforePayload(request.getBeforePayload())
                .afterPayload(request.getAfterPayload())
                .evidencePayload(request.getEvidencePayload())
                .resultStatus(request.getResultStatus())
                .operatorId(request.getOperatorId() != null ? request.getOperatorId() : operatorId)
                .operatorName(request.getOperatorName())
                .recordedAt(execution.getImpactRecordedAt())
                .build();
    }

    private String generateExecutionNo() {
        String datePrefix = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        int randomNum = ThreadLocalRandom.current().nextInt(1000, 10000);
        return "AE-" + datePrefix + randomNum;
    }

    private ResourceGovernanceActionExecutionResponse toResponse(ResourceGovernanceActionExecution execution) {
        return ResourceGovernanceActionExecutionResponse.builder()
                .id(execution.getId())
                .executionNo(execution.getExecutionNo())
                .planId(execution.getPlanId())
                .suggestionId(execution.getSuggestionId())
                .actionType(execution.getActionType())
                .targetType(execution.getTargetType())
                .targetId(execution.getTargetId())
                .status(execution.getStatus())
                .executionMode(execution.getExecutionMode())
                .operatorId(execution.getOperatorId())
                .startedAt(execution.getStartedAt())
                .finishedAt(execution.getFinishedAt())
                .resultSummary(execution.getResultSummary())
                .failureReason(execution.getFailureReason())
                .evidencePayload(execution.getEvidencePayload())
                .createdAt(execution.getCreatedAt())
                .updatedAt(execution.getUpdatedAt())
                .build();
    }

    private void recordExecutionStarted(ResourceGovernanceActionExecution execution, Long operatorId) {
        try {
            ResourceGovernanceActionPlan plan = actionPlanMapper.selectById(execution.getPlanId());
            String planNo = plan != null ? plan.getPlanNo() : null;
            String suggestionNo = plan != null ? plan.getSuggestionNo() : null;

            governanceRecordService.recordWithEnrichment(
                    GovernanceEventType.RESOURCE_GOVERNANCE_ACTION_EXECUTION_STARTED,
                    null,
                    null,
                    null,
                    null,
                    null,
                    "USER",
                    null,
                    null,
                    null,
                    "GOVERNANCE_ACTION_EXECUTION",
                    execution.getId(),
                    planNo,
                    execution.getExecutionNo(),
                    suggestionNo,
                    null,
                    buildExecutionPayload(execution, ActionExecutionStatus.RUNNING, operatorId),
                    "SUCCESS",
                    null,
                    operatorId
            );
        } catch (Exception e) {
            log.warn("Failed to record governance event for execution start: executionNo={}, error={}",
                    execution.getExecutionNo(), e.getMessage());
        }
    }

    private void recordExecutionCompleted(ResourceGovernanceActionExecution execution, CompleteActionExecutionRequest request, Long operatorId) {
        try {
            ResourceGovernanceActionPlan plan = actionPlanMapper.selectById(execution.getPlanId());
            String planNo = plan != null ? plan.getPlanNo() : null;
            String suggestionNo = plan != null ? plan.getSuggestionNo() : null;

            Map<String, Object> afterPayload = new java.util.HashMap<>();
            afterPayload.put("status", ActionExecutionStatus.SUCCESS.name());
            afterPayload.put("executionNo", execution.getExecutionNo());
            afterPayload.put("planId", execution.getPlanId());
            afterPayload.put("suggestionId", execution.getSuggestionId());
            if (request != null) {
                if (request.getResultSummary() != null) {
                    afterPayload.put("resultSummary", request.getResultSummary());
                }
                if (request.getEvidencePayload() != null) {
                    afterPayload.put("evidencePayload", request.getEvidencePayload());
                }
            }

            governanceRecordService.recordWithEnrichment(
                    GovernanceEventType.RESOURCE_GOVERNANCE_ACTION_EXECUTION_COMPLETED,
                    null,
                    null,
                    null,
                    null,
                    null,
                    "USER",
                    null,
                    null,
                    null,
                    "GOVERNANCE_ACTION_EXECUTION",
                    execution.getId(),
                    planNo,
                    execution.getExecutionNo(),
                    suggestionNo,
                    Map.of("status", ActionExecutionStatus.RUNNING.name()),
                    afterPayload,
                    "SUCCESS",
                    request != null ? request.getResultSummary() : null,
                    operatorId
            );
        } catch (Exception e) {
            log.warn("Failed to record governance event for execution completion: executionNo={}, error={}",
                    execution.getExecutionNo(), e.getMessage());
        }
    }

    private void recordExecutionFailed(ResourceGovernanceActionExecution execution, FailActionExecutionRequest request, Long operatorId) {
        try {
            ResourceGovernanceActionPlan plan = actionPlanMapper.selectById(execution.getPlanId());
            String planNo = plan != null ? plan.getPlanNo() : null;
            String suggestionNo = plan != null ? plan.getSuggestionNo() : null;

            Map<String, Object> afterPayload = new java.util.HashMap<>();
            afterPayload.put("status", ActionExecutionStatus.FAILED.name());
            afterPayload.put("executionNo", execution.getExecutionNo());
            afterPayload.put("planId", execution.getPlanId());
            afterPayload.put("suggestionId", execution.getSuggestionId());
            if (request != null) {
                if (request.getFailureReason() != null) {
                    afterPayload.put("failureReason", request.getFailureReason());
                }
                if (request.getEvidencePayload() != null) {
                    afterPayload.put("evidencePayload", request.getEvidencePayload());
                }
            }

            governanceRecordService.recordWithEnrichment(
                    GovernanceEventType.RESOURCE_GOVERNANCE_ACTION_EXECUTION_FAILED,
                    null,
                    null,
                    null,
                    null,
                    null,
                    "USER",
                    null,
                    null,
                    null,
                    "GOVERNANCE_ACTION_EXECUTION",
                    execution.getId(),
                    planNo,
                    execution.getExecutionNo(),
                    suggestionNo,
                    Map.of("status", ActionExecutionStatus.RUNNING.name()),
                    afterPayload,
                    "FAILED",
                    request != null ? request.getFailureReason() : null,
                    operatorId
            );
        } catch (Exception e) {
            log.warn("Failed to record governance event for execution failure: executionNo={}, error={}",
                    execution.getExecutionNo(), e.getMessage());
        }
    }

    private void recordExecutionCancelled(ResourceGovernanceActionExecution execution, CancelActionExecutionRequest request, Long operatorId) {
        try {
            ResourceGovernanceActionPlan plan = actionPlanMapper.selectById(execution.getPlanId());
            String planNo = plan != null ? plan.getPlanNo() : null;
            String suggestionNo = plan != null ? plan.getSuggestionNo() : null;

            Map<String, Object> afterPayload = new java.util.HashMap<>();
            afterPayload.put("status", ActionExecutionStatus.CANCELLED.name());
            afterPayload.put("executionNo", execution.getExecutionNo());
            afterPayload.put("planId", execution.getPlanId());
            afterPayload.put("suggestionId", execution.getSuggestionId());
            if (request != null && request.getCancelReason() != null) {
                afterPayload.put("cancelReason", request.getCancelReason());
            }

            governanceRecordService.recordWithEnrichment(
                    GovernanceEventType.RESOURCE_GOVERNANCE_ACTION_EXECUTION_CANCELLED,
                    null,
                    null,
                    null,
                    null,
                    null,
                    "USER",
                    null,
                    null,
                    null,
                    "GOVERNANCE_ACTION_EXECUTION",
                    execution.getId(),
                    planNo,
                    execution.getExecutionNo(),
                    suggestionNo,
                    Map.of("status", ActionExecutionStatus.RUNNING.name()),
                    afterPayload,
                    "SUCCESS",
                    request != null ? request.getCancelReason() : null,
                    operatorId
            );
        } catch (Exception e) {
            log.warn("Failed to record governance event for execution cancellation: executionNo={}, error={}",
                    execution.getExecutionNo(), e.getMessage());
        }
    }

    private Map<String, Object> buildExecutionPayload(ResourceGovernanceActionExecution execution,
                                                        ActionExecutionStatus status, Long operatorId) {
        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("executionNo", execution.getExecutionNo());
        payload.put("planId", execution.getPlanId());
        payload.put("suggestionId", execution.getSuggestionId());
        if (execution.getActionType() != null) {
            payload.put("actionType", execution.getActionType().name());
        }
        if (execution.getTargetType() != null) {
            payload.put("targetType", execution.getTargetType().name());
        }
        if (execution.getTargetId() != null) {
            payload.put("targetId", execution.getTargetId());
        }
        payload.put("executionMode", ExecutionMode.MANUAL_ONLY.name());
        payload.put("status", status.name());
        payload.put("operatorId", operatorId);
        return payload;
    }

    /**
     * K-A: 记录 IMPACT_RECORDED 事件到治理记录
     * best-effort，失败不影响主流程
     */
    private void recordImpactRecorded(ResourceGovernanceActionExecution execution, RecordImpactRequest request, Long operatorId) {
        try {
            ResourceGovernanceActionPlan plan = actionPlanMapper.selectById(execution.getPlanId());
            String planNo = plan != null ? plan.getPlanNo() : null;
            String suggestionNo = plan != null ? plan.getSuggestionNo() : null;

            Map<String, Object> impactPayload = new java.util.HashMap<>();
            impactPayload.put("impactType", request.getImpactType().name());
            impactPayload.put("impactSummary", request.getImpactSummary());
            impactPayload.put("resultStatus", request.getResultStatus().name());
            if (request.getImpactPayload() != null) {
                impactPayload.put("impactPayload", request.getImpactPayload());
            }
            if (request.getEvidencePayload() != null) {
                impactPayload.put("evidencePayload", request.getEvidencePayload());
            }
            impactPayload.put("recordedAt", OffsetDateTime.now().toString());

            Map<String, Object> beforePayload = new java.util.HashMap<>();
            beforePayload.put("executionNo", execution.getExecutionNo());
            beforePayload.put("status", execution.getStatus().name());
            if (request.getBeforePayload() != null) {
                beforePayload.put("beforePayload", request.getBeforePayload());
            }

            Map<String, Object> afterPayload = new java.util.HashMap<>();
            afterPayload.put("executionNo", execution.getExecutionNo());
            afterPayload.put("impactType", request.getImpactType().name());
            afterPayload.put("impactSummary", request.getImpactSummary());
            afterPayload.put("resultStatus", request.getResultStatus().name());
            if (request.getAfterPayload() != null) {
                afterPayload.put("afterPayload", request.getAfterPayload());
            }
            if (request.getImpactPayload() != null) {
                afterPayload.put("impactPayload", request.getImpactPayload());
            }
            if (request.getEvidencePayload() != null) {
                afterPayload.put("evidencePayload", request.getEvidencePayload());
            }

            Long effectiveOperatorId = request.getOperatorId() != null ? request.getOperatorId() : operatorId;

            governanceRecordService.recordWithEnrichment(
                    GovernanceEventType.RESOURCE_GOVERNANCE_ACTION_IMPACT_RECORDED,
                    null,
                    null,
                    null,
                    null,
                    null,
                    "USER",
                    null,
                    null,
                    null,
                    "GOVERNANCE_ACTION_EXECUTION",
                    execution.getId(),
                    planNo,
                    execution.getExecutionNo(),
                    suggestionNo,
                    beforePayload,
                    afterPayload,
                    request.getResultStatus().name(),
                    request.getImpactSummary(),
                    effectiveOperatorId
            );
        } catch (Exception e) {
            log.warn("Failed to record governance event for impact recording: executionNo={}, error={}",
                    execution.getExecutionNo(), e.getMessage());
        }
    }
}
package com.infrahub.release.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.infrahub.base.entity.BaseEnv;
import com.infrahub.base.mapper.BaseEnvMapper;
import com.infrahub.release.dto.*;
import com.infrahub.release.entity.ResourceGovernanceActionPlan;
import com.infrahub.release.entity.ResourceGovernanceSuggestion;
import com.infrahub.release.entity.ResourcePool;
import com.infrahub.release.enums.ActionPlanStatus;
import com.infrahub.release.enums.ActionType;
import com.infrahub.release.enums.SuggestionStatus;
import com.infrahub.release.enums.SuggestionType;
import com.infrahub.release.enums.TargetType;
import com.infrahub.release.mapper.ResourceGovernanceActionPlanMapper;
import com.infrahub.release.mapper.ResourceGovernanceSuggestionMapper;
import com.infrahub.release.mapper.ResourcePoolMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Service
@RequiredArgsConstructor
public class ResourceGovernanceActionPlanService {

    private final ResourceGovernanceActionPlanMapper actionPlanMapper;
    private final ResourceGovernanceSuggestionMapper suggestionMapper;
    private final ResourceGovernanceRecordService governanceRecordService;
    private final ResourcePoolMapper resourcePoolMapper;
    private final BaseEnvMapper baseEnvMapper;
    private final ResourceGovernanceActionPlanPayloadBuilder payloadBuilder;

    /**
     * 从治理建议自动推导创建执行计划（无 request body 版本）
     * 根据 suggestion.suggestionType 自动推导 actionType、targetType、planPayload
     *
     * @param suggestionId 建议单ID
     * @param operatorId   操作人ID
     * @return 创建的计划响应
     */
    public ResourceGovernanceActionPlanResponse createFromSuggestion(Long suggestionId, Long operatorId) {
        ResourceGovernanceSuggestion suggestion = suggestionMapper.selectById(suggestionId);
        if (suggestion == null) {
            throw new IllegalStateException("治理建议不存在: id=" + suggestionId);
        }

        validateSuggestionStatus(suggestion);
        validateNoActivePlan(suggestionId);

        // 自动推导 actionType
        ActionType actionType = mapSuggestionTypeToActionType(suggestion.getSuggestionType());

        // 自动推导 targetType
        TargetType targetType = deriveTargetType(actionType);

        // 自动生成 planPayload
        String planPayload = payloadBuilder.buildPayload(
                suggestion.getSuggestionType(),
                suggestion.getResourcePoolId(),
                suggestion.getEnvId(),
                suggestion.getRiskLevel() != null ? suggestion.getRiskLevel().name() : null
        );

        ResourceGovernanceActionPlan plan = new ResourceGovernanceActionPlan();
        plan.setPlanNo(generatePlanNo());
        plan.setSuggestionId(suggestionId);
        plan.setSuggestionNo(suggestion.getSuggestionNo());
        plan.setActionType(actionType);
        plan.setResourcePoolId(suggestion.getResourcePoolId());
        plan.setEnvId(suggestion.getEnvId());
        plan.setTargetType(targetType);
        plan.setTargetId(suggestion.getResourcePoolId());
        plan.setPlanPayload(planPayload);
        plan.setRiskLevel(suggestion.getRiskLevel());
        plan.setStatus(ActionPlanStatus.DRAFT);
        plan.setReason(suggestion.getReason() != null ? suggestion.getReason() : suggestion.getDescription());
        plan.setCreatedBy(operatorId);

        actionPlanMapper.insert(plan);

        recordPlanCreated(plan);

        return toResponse(plan);
    }

    /**
     * 从治理建议创建执行计划（兼容带 request body 版本）
     * 如果 request 为空或未指定 actionType，自动推导
     *
     * @param suggestionId 建议单ID
     * @param request      创建请求（可选）
     * @param operatorId   操作人ID
     * @return 创建的计划响应
     */
    public ResourceGovernanceActionPlanResponse createFromSuggestion(Long suggestionId,
                                                                       CreateActionPlanRequest request,
                                                                       Long operatorId) {
        // 如果 request 为空或未指定 actionType，走自动推导路径
        if (request == null || request.getActionType() == null) {
            return createFromSuggestion(suggestionId, operatorId);
        }

        ResourceGovernanceSuggestion suggestion = suggestionMapper.selectById(suggestionId);
        if (suggestion == null) {
            throw new IllegalStateException("治理建议不存在: id=" + suggestionId);
        }

        validateSuggestionStatus(suggestion);
        validateNoActivePlan(suggestionId);

        // Auto-generate planPayload from suggestionType if not provided
        String planPayload = request.getPlanPayload();
        if (planPayload == null && suggestion.getSuggestionType() != null) {
            planPayload = payloadBuilder.buildPayload(
                    suggestion.getSuggestionType(),
                    suggestion.getResourcePoolId(),
                    suggestion.getEnvId(),
                    suggestion.getRiskLevel() != null ? suggestion.getRiskLevel().name() : null
            );
        }

        ResourceGovernanceActionPlan plan = new ResourceGovernanceActionPlan();
        plan.setPlanNo(generatePlanNo());
        plan.setSuggestionId(suggestionId);
        plan.setSuggestionNo(suggestion.getSuggestionNo());
        plan.setActionType(request.getActionType());
        plan.setResourcePoolId(suggestion.getResourcePoolId());
        plan.setEnvId(suggestion.getEnvId());
        plan.setTargetType(request.getTargetType() != null ? request.getTargetType() : deriveTargetType(request.getActionType()));
        plan.setTargetId(request.getTargetId() != null ? request.getTargetId() : suggestion.getResourcePoolId());
        plan.setPlanPayload(planPayload);
        plan.setRiskLevel(request.getRiskLevel() != null ? request.getRiskLevel() : suggestion.getRiskLevel());
        plan.setStatus(ActionPlanStatus.DRAFT);
        plan.setReason(request.getReason() != null ? request.getReason() : suggestion.getReason());
        plan.setCreatedBy(operatorId);

        actionPlanMapper.insert(plan);

        recordPlanCreated(plan);

        return toResponse(plan);
    }

    /**
     * 根据建议单ID查询活跃计划（DRAFT或CONFIRMED）
     *
     * @param suggestionId 建议单ID
     * @return 活跃计划响应，不存在则返回null
     */
    public ResourceGovernanceActionPlanResponse getActivePlanBySuggestionId(Long suggestionId) {
        ResourceGovernanceActionPlan plan = actionPlanMapper.selectActiveBySuggestionId(suggestionId);
        if (plan == null) {
            return null;
        }
        return toResponse(plan);
    }

    private void validateSuggestionStatus(ResourceGovernanceSuggestion suggestion) {
        if (suggestion.getStatus() == SuggestionStatus.RESOLVED ||
            suggestion.getStatus() == SuggestionStatus.IGNORED) {
            throw new IllegalStateException("治理建议已处理，无法创建计划: id=" + suggestion.getId() + ", status=" + suggestion.getStatus());
        }
    }

    private void validateNoActivePlan(Long suggestionId) {
        ResourceGovernanceActionPlan existingPlan = actionPlanMapper.selectActiveBySuggestionId(suggestionId);
        if (existingPlan != null) {
            throw new IllegalStateException("该建议已存在未完成的执行计划: suggestionId=" + suggestionId + ", planId=" + existingPlan.getId());
        }
    }

    private ActionType mapSuggestionTypeToActionType(SuggestionType suggestionType) {
        if (suggestionType == null) {
            return null;
        }
        return switch (suggestionType) {
            case SCALE_OUT -> ActionType.SCALE_OUT_PLAN;
            case CHECK_HOST -> ActionType.CHECK_HOST_PLAN;
            case INVESTIGATE_CAPACITY -> ActionType.INVESTIGATE_CAPACITY_PLAN;
            case CLEANUP_INSTANCE -> ActionType.CLEANUP_INSTANCE_PLAN;
        };
    }

    private TargetType deriveTargetType(ActionType actionType) {
        if (actionType == null) {
            return TargetType.NONE;
        }
        return switch (actionType) {
            case SCALE_OUT_PLAN -> TargetType.RESOURCE_POOL;
            case CHECK_HOST_PLAN -> TargetType.HOST;
            case INVESTIGATE_CAPACITY_PLAN -> TargetType.RESOURCE_POOL;
            case CLEANUP_INSTANCE_PLAN -> TargetType.RUNTIME_INSTANCE;
        };
    }

    public Page<ResourceGovernanceActionPlanResponse> query(ResourceGovernanceActionPlanQuery query) {
        Page<ResourceGovernanceActionPlan> page = new Page<>(query.getPageNum(), query.getPageSize());
        Page<ResourceGovernanceActionPlan> resultPage = actionPlanMapper.queryActionPlans(
                page,
                query.getResourcePoolId(),
                query.getEnvId(),
                query.getActionType(),
                query.getStatus()
        );

        List<ResourceGovernanceActionPlanResponse> records = resultPage.getRecords().stream()
                .map(this::toResponse)
                .toList();

        Page<ResourceGovernanceActionPlanResponse> responsePage = new Page<>(
                resultPage.getCurrent(),
                resultPage.getSize(),
                resultPage.getTotal()
        );
        responsePage.setRecords(records);
        return responsePage;
    }

    public ResourceGovernanceActionPlanResponse getById(Long id) {
        ResourceGovernanceActionPlan plan = actionPlanMapper.selectById(id);
        if (plan == null) {
            return null;
        }
        return toResponse(plan);
    }

    public void confirm(Long id, ConfirmActionPlanRequest request, Long operatorId) {
        ResourceGovernanceActionPlan plan = actionPlanMapper.selectById(id);
        if (plan == null) {
            throw new IllegalStateException("执行计划不存在: id=" + id);
        }

        if (plan.getStatus() != ActionPlanStatus.DRAFT) {
            throw new IllegalStateException("执行计划状态不是草稿，无法确认: id=" + id + ", status=" + plan.getStatus());
        }

        plan.setStatus(ActionPlanStatus.CONFIRMED);
        plan.setConfirmedBy(operatorId);
        plan.setConfirmedAt(OffsetDateTime.now());
        actionPlanMapper.updateById(plan);

        recordPlanConfirmed(plan, request != null ? request.getHandleNote() : null, operatorId);
    }

    public void cancel(Long id, CancelActionPlanRequest request, Long operatorId) {
        ResourceGovernanceActionPlan plan = actionPlanMapper.selectById(id);
        if (plan == null) {
            throw new IllegalStateException("执行计划不存在: id=" + id);
        }

        if (plan.getStatus() != ActionPlanStatus.DRAFT &&
            plan.getStatus() != ActionPlanStatus.CONFIRMED) {
            throw new IllegalStateException("执行计划状态不允许取消: id=" + id + ", status=" + plan.getStatus());
        }

        plan.setStatus(ActionPlanStatus.CANCELLED);
        plan.setCancelledBy(operatorId);
        plan.setCancelledAt(OffsetDateTime.now());
        if (request != null) {
            plan.setCancelReason(request.getCancelReason());
        }
        actionPlanMapper.updateById(plan);

        recordPlanCancelled(plan, operatorId);
    }

    private String generatePlanNo() {
        String datePrefix = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        int randomNum = ThreadLocalRandom.current().nextInt(1000, 10000);
        return "AP-" + datePrefix + randomNum;
    }

    private ResourceGovernanceActionPlanResponse toResponse(ResourceGovernanceActionPlan plan) {
        String resourcePoolName = null;
        String envName = null;

        if (plan.getResourcePoolId() != null) {
            try {
                ResourcePool pool = resourcePoolMapper.selectById(plan.getResourcePoolId());
                if (pool != null) {
                    resourcePoolName = pool.getPoolName();
                }
            } catch (Exception e) {
                log.debug("Failed to lookup resource pool name: id={}, error={}",
                        plan.getResourcePoolId(), e.getMessage());
            }
        }

        if (plan.getEnvId() != null) {
            try {
                BaseEnv env = baseEnvMapper.selectById(plan.getEnvId());
                if (env != null) {
                    envName = env.getEnvName();
                }
            } catch (Exception e) {
                log.debug("Failed to lookup env name: id={}, error={}",
                        plan.getEnvId(), e.getMessage());
            }
        }

        return ResourceGovernanceActionPlanResponse.builder()
                .id(plan.getId())
                .planNo(plan.getPlanNo())
                .suggestionId(plan.getSuggestionId())
                .suggestionNo(plan.getSuggestionNo())
                .actionType(plan.getActionType())
                .resourcePoolId(plan.getResourcePoolId())
                .resourcePoolName(resourcePoolName)
                .envId(plan.getEnvId())
                .envName(envName)
                .targetType(plan.getTargetType())
                .targetId(plan.getTargetId())
                .planPayload(plan.getPlanPayload())
                .riskLevel(plan.getRiskLevel())
                .status(plan.getStatus())
                .reason(plan.getReason())
                .createdBy(plan.getCreatedBy())
                .createdAt(plan.getCreatedAt())
                .updatedAt(plan.getUpdatedAt())
                .confirmedBy(plan.getConfirmedBy())
                .confirmedAt(plan.getConfirmedAt())
                .cancelledBy(plan.getCancelledBy())
                .cancelledAt(plan.getCancelledAt())
                .cancelReason(plan.getCancelReason())
                .executedAt(plan.getExecutedAt())
                .resultStatus(plan.getResultStatus())
                .resultMessage(plan.getResultMessage())
                .build();
    }

    private void recordPlanCreated(ResourceGovernanceActionPlan plan) {
        try {
            governanceRecordService.record(
                    "RESOURCE_GOVERNANCE_ACTION_PLAN_CREATED",
                    plan.getResourcePoolId(),
                    null,
                    null,
                    null,
                    null,
                    "USER",
                    plan.getEnvId(),
                    null,
                    null,
                    "ACTION_PLAN",
                    plan.getId(),
                    null,
                    Map.of(
                            "planNo", plan.getPlanNo(),
                            "actionType", plan.getActionType().name(),
                            "status", plan.getStatus().name()
                    ),
                    "SUCCESS",
                    plan.getReason(),
                    plan.getCreatedBy()
            );
        } catch (Exception e) {
            log.warn("Failed to record governance event for action plan creation: planNo={}, error={}",
                    plan.getPlanNo(), e.getMessage());
        }
    }

    private void recordPlanConfirmed(ResourceGovernanceActionPlan plan, String handleNote, Long operatorId) {
        try {
            governanceRecordService.record(
                    "RESOURCE_GOVERNANCE_ACTION_PLAN_CONFIRMED",
                    plan.getResourcePoolId(),
                    null,
                    null,
                    null,
                    null,
                    "USER",
                    plan.getEnvId(),
                    null,
                    null,
                    "ACTION_PLAN",
                    plan.getId(),
                    Map.of("status", ActionPlanStatus.DRAFT.name()),
                    Map.of("status", ActionPlanStatus.CONFIRMED.name()),
                    "SUCCESS",
                    handleNote,
                    operatorId
            );
        } catch (Exception e) {
            log.warn("Failed to record governance event for action plan confirmation: planNo={}, error={}",
                    plan.getPlanNo(), e.getMessage());
        }
    }

    private void recordPlanCancelled(ResourceGovernanceActionPlan plan, Long operatorId) {
        try {
            governanceRecordService.record(
                    "RESOURCE_GOVERNANCE_ACTION_PLAN_CANCELLED",
                    plan.getResourcePoolId(),
                    null,
                    null,
                    null,
                    null,
                    "USER",
                    plan.getEnvId(),
                    null,
                    null,
                    "ACTION_PLAN",
                    plan.getId(),
                    Map.of("status", plan.getStatus().name()),
                    Map.of("status", ActionPlanStatus.CANCELLED.name()),
                    "SUCCESS",
                    plan.getCancelReason(),
                    operatorId
            );
        } catch (Exception e) {
            log.warn("Failed to record governance event for action plan cancellation: planNo={}, error={}",
                    plan.getPlanNo(), e.getMessage());
        }
    }
}

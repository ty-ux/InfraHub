package com.infrahub.release.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.infrahub.release.dto.CapacityRiskItemResponse;
import com.infrahub.release.dto.ResourceGovernanceSuggestionQuery;
import com.infrahub.release.dto.ResourceGovernanceSuggestionResponse;
import com.infrahub.release.dto.ResourcePoolRiskResponse;
import com.infrahub.release.entity.ResourceGovernanceSuggestion;
import com.infrahub.release.enums.RiskLevel;
import com.infrahub.release.enums.SourceType;
import com.infrahub.release.enums.SuggestionStatus;
import com.infrahub.release.enums.SuggestionType;
import com.infrahub.release.mapper.ResourceGovernanceSuggestionMapper;
import com.infrahub.release.mapper.ResourcePoolMapper;
import com.infrahub.base.mapper.BaseEnvMapper;
import com.infrahub.release.entity.ResourcePool;
import com.infrahub.base.entity.BaseEnv;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Service
@RequiredArgsConstructor
public class ResourceGovernanceSuggestionService {

    private static final String METRIC_CPU = "CPU";
    private static final String METRIC_MEMORY = "MEMORY";
    private static final String METRIC_DISK = "DISK";
    private static final String METRIC_OFFLINE_HOST = "OFFLINE_HOST";
    private static final String METRIC_UNKNOWN_HOST = "UNKNOWN_HOST";

    private final ResourceGovernanceSuggestionMapper suggestionMapper;
    private final ResourceCapacityRiskService riskService;
    private final ResourceGovernanceRecordService governanceRecordService;
    private final ResourcePoolMapper resourcePoolMapper;
    private final BaseEnvMapper baseEnvMapper;
    private final ResourceGovernanceActionPlanService actionPlanService;

    /**
     * 为资源池生成治理建议
     *
     * @param resourcePoolId 资源池ID
     * @return 生成的建议DTO列表
     */
    public List<ResourceGovernanceSuggestionResponse> generateSuggestions(Long resourcePoolId) {
        ResourcePoolRiskResponse riskResponse = riskService.evaluateRisk(resourcePoolId);
        if (riskResponse == null || riskResponse.getRiskLevel() == RiskLevel.NORMAL) {
            return Collections.emptyList();
        }

        List<ResourceGovernanceSuggestion> generatedSuggestions = new ArrayList<>();
        List<CapacityRiskItemResponse> risks = riskResponse.getRisks();

        if (risks == null || risks.isEmpty()) {
            return Collections.emptyList();
        }

        for (CapacityRiskItemResponse risk : risks) {
            if (risk.getRiskLevel() == RiskLevel.NORMAL) {
                continue;
            }

            SuggestionType suggestionType = determineSuggestionType(risk);
            if (suggestionType == null) {
                continue;
            }

            // 检查是否已存在相同类型的开启状态建议
            if (existsOpenSuggestion(resourcePoolId, suggestionType)) {
                log.debug("Skipping suggestion creation: resourcePoolId={}, suggestionType={} already has OPEN suggestion",
                        resourcePoolId, suggestionType);
                continue;
            }

            ResourceGovernanceSuggestion suggestion = createSuggestion(
                    resourcePoolId,
                    riskResponse.getEnvId(),
                    suggestionType,
                    risk,
                    riskResponse
            );
            suggestionMapper.insert(suggestion);
            generatedSuggestions.add(suggestion);

            // Best-effort 写入治理记录
            recordSuggestionCreated(suggestion);
        }

        return generatedSuggestions.stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * 查询治理建议列表
     *
     * @param query 查询参数
     * @return 分页响应DTO列表
     */
    public Page<ResourceGovernanceSuggestionResponse> query(ResourceGovernanceSuggestionQuery query) {
        Page<ResourceGovernanceSuggestion> page = new Page<>(query.getPageNum(), query.getPageSize());
        Page<ResourceGovernanceSuggestion> resultPage = suggestionMapper.querySuggestions(
                page,
                query.getResourcePoolId(),
                query.getEnvId(),
                query.getRiskLevel(),
                query.getSuggestionType(),
                query.getStatus()
        );

        List<ResourceGovernanceSuggestionResponse> records = resultPage.getRecords().stream()
                .map(this::toResponse)
                .toList();

        Page<ResourceGovernanceSuggestionResponse> responsePage = new Page<>(
                resultPage.getCurrent(),
                resultPage.getSize(),
                resultPage.getTotal()
        );
        responsePage.setRecords(records);
        return responsePage;
    }

    /**
     * 根据ID获取治理建议
     *
     * @param id 建议ID
     * @return 建议响应DTO，不存在则返回null
     */
    public ResourceGovernanceSuggestionResponse getById(Long id) {
        ResourceGovernanceSuggestion suggestion = suggestionMapper.selectById(id);
        if (suggestion == null) {
            return null;
        }
        return toResponse(suggestion);
    }

    /**
     * 确认治理建议
     *
     * @param id          建议ID
     * @param handleNote  处理备注
     * @param operatorId  操作人ID
     * @throws IllegalStateException 如果建议不存在或状态不是OPEN
     */
    public void ack(Long id, String handleNote, Long operatorId) {
        ResourceGovernanceSuggestion suggestion = suggestionMapper.selectById(id);
        if (suggestion == null) {
            throw new IllegalStateException("治理建议不存在: id=" + id);
        }

        if (suggestion.getStatus() != SuggestionStatus.OPEN) {
            throw new IllegalStateException("治理建议状态不是待处理，无法确认: id=" + id + ", status=" + suggestion.getStatus());
        }

        suggestion.setStatus(SuggestionStatus.ACKED);
        suggestion.setHandledBy(operatorId);
        suggestion.setHandledAt(OffsetDateTime.now());
        suggestion.setHandleNote(handleNote);
        suggestionMapper.updateById(suggestion);

        recordSuggestionAcked(suggestion);
    }

    /**
     * 忽略治理建议
     *
     * @param id          建议ID
     * @param handleNote  处理备注
     * @param operatorId  操作人ID
     * @throws IllegalStateException 如果建议不存在或状态已经是RESOLVED/IGNORED
     */
    public void ignore(Long id, String handleNote, Long operatorId) {
        ResourceGovernanceSuggestion suggestion = suggestionMapper.selectById(id);
        if (suggestion == null) {
            throw new IllegalStateException("治理建议不存在: id=" + id);
        }

        if (suggestion.getStatus() == SuggestionStatus.RESOLVED ||
            suggestion.getStatus() == SuggestionStatus.IGNORED) {
            throw new IllegalStateException("治理建议已处理，无法忽略: id=" + id + ", status=" + suggestion.getStatus());
        }

        suggestion.setStatus(SuggestionStatus.IGNORED);
        suggestion.setHandledBy(operatorId);
        suggestion.setHandledAt(OffsetDateTime.now());
        suggestion.setHandleNote(handleNote);
        suggestionMapper.updateById(suggestion);

        recordSuggestionIgnored(suggestion);
    }

    /**
     * 解决治理建议
     *
     * @param id          建议ID
     * @param handleNote  处理备注
     * @param operatorId  操作人ID
     * @throws IllegalStateException 如果建议不存在或状态已经是RESOLVED/IGNORED
     */
    public void resolve(Long id, String handleNote, Long operatorId) {
        ResourceGovernanceSuggestion suggestion = suggestionMapper.selectById(id);
        if (suggestion == null) {
            throw new IllegalStateException("治理建议不存在: id=" + id);
        }

        if (suggestion.getStatus() == SuggestionStatus.RESOLVED ||
            suggestion.getStatus() == SuggestionStatus.IGNORED) {
            throw new IllegalStateException("治理建议已处理，无法解决: id=" + id + ", status=" + suggestion.getStatus());
        }

        suggestion.setStatus(SuggestionStatus.RESOLVED);
        suggestion.setHandledBy(operatorId);
        suggestion.setHandledAt(OffsetDateTime.now());
        suggestion.setHandleNote(handleNote);
        suggestionMapper.updateById(suggestion);

        recordSuggestionResolved(suggestion);
    }

    private SuggestionType determineSuggestionType(CapacityRiskItemResponse risk) {
        String metricType = risk.getMetricType();
        RiskLevel riskLevel = risk.getRiskLevel();

        return switch (metricType) {
            case METRIC_CPU, METRIC_MEMORY, METRIC_DISK -> {
                if (riskLevel == RiskLevel.WARNING) {
                    yield SuggestionType.INVESTIGATE_CAPACITY;
                } else if (riskLevel == RiskLevel.CRITICAL) {
                    yield SuggestionType.SCALE_OUT;
                }
                yield null;
            }
            case METRIC_OFFLINE_HOST -> SuggestionType.CHECK_HOST;
            case METRIC_UNKNOWN_HOST -> SuggestionType.INVESTIGATE_CAPACITY;
            default -> null;
        };
    }

    private boolean existsOpenSuggestion(Long resourcePoolId, SuggestionType suggestionType) {
        // 使用分页查询检查是否存在开启状态的建议
        Page<ResourceGovernanceSuggestion> page = new Page<>(1, 1);
        Page<ResourceGovernanceSuggestion> result = suggestionMapper.querySuggestions(
                page,
                resourcePoolId,
                null,
                null,
                suggestionType.name(),
                SuggestionStatus.OPEN.name()
        );
        return result.getTotal() > 0;
    }

    private ResourceGovernanceSuggestion createSuggestion(Long resourcePoolId, Long envId,
                                                           SuggestionType suggestionType,
                                                           CapacityRiskItemResponse risk,
                                                           ResourcePoolRiskResponse riskResponse) {
        ResourceGovernanceSuggestion suggestion = new ResourceGovernanceSuggestion();
        suggestion.setSuggestionNo(generateSuggestionNo());
        suggestion.setSuggestionType(suggestionType);
        suggestion.setSourceType(SourceType.RESOURCE_POOL_RISK);
        suggestion.setSourceId(resourcePoolId);
        suggestion.setResourcePoolId(resourcePoolId);
        suggestion.setEnvId(envId);
        suggestion.setRiskLevel(risk.getRiskLevel());
        suggestion.setTitle(buildSuggestionTitle(suggestionType, risk));
        suggestion.setDescription(buildSuggestionDescription(risk, riskResponse));
        suggestion.setStatus(SuggestionStatus.OPEN);
        suggestion.setReason(risk.getReason());
        return suggestion;
    }

    private String generateSuggestionNo() {
        String datePrefix = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        int randomNum = ThreadLocalRandom.current().nextInt(1000, 10000);
        return "GS-" + datePrefix + randomNum;
    }

    private String buildSuggestionTitle(SuggestionType suggestionType, CapacityRiskItemResponse risk) {
        return switch (suggestionType) {
            case SCALE_OUT -> "扩容建议: " + risk.getMetricType() + " 使用率达到临界值";
            case CHECK_HOST -> "主机检查: 发现离线主机";
            case CLEANUP_INSTANCE -> "实例清理建议";
            case INVESTIGATE_CAPACITY -> "容量调查: " + risk.getMetricType() + " 需要关注";
        };
    }

    private String buildSuggestionDescription(CapacityRiskItemResponse risk, ResourcePoolRiskResponse riskResponse) {
        StringBuilder sb = new StringBuilder();
        sb.append("资源池: ").append(riskResponse.getResourcePoolName()).append("\n");
        sb.append("指标: ").append(risk.getMetricType()).append("\n");
        if (risk.getCurrentValue() != null) {
            sb.append("当前值: ").append(risk.getCurrentValue()).append("\n");
        }
        if (risk.getThreshold() != null) {
            sb.append("触发阈值: ").append(risk.getThreshold()).append("\n");
        }
        sb.append("风险等级: ").append(risk.getRiskLevel()).append("\n");
        sb.append("原因: ").append(risk.getReason());
        return sb.toString();
    }

    private ResourceGovernanceSuggestionResponse toResponse(ResourceGovernanceSuggestion suggestion) {
        String resourcePoolName = null;
        String envName = null;

        // Best-effort 查询资源池名称
        if (suggestion.getResourcePoolId() != null) {
            try {
                ResourcePool pool = resourcePoolMapper.selectById(suggestion.getResourcePoolId());
                if (pool != null) {
                    resourcePoolName = pool.getPoolName();
                }
            } catch (Exception e) {
                log.debug("Failed to lookup resource pool name: id={}, error={}",
                        suggestion.getResourcePoolId(), e.getMessage());
            }
        }

        // Best-effort 查询环境名称
        if (suggestion.getEnvId() != null) {
            try {
                BaseEnv env = baseEnvMapper.selectById(suggestion.getEnvId());
                if (env != null) {
                    envName = env.getEnvName();
                }
            } catch (Exception e) {
                log.debug("Failed to lookup env name: id={}, error={}",
                        suggestion.getEnvId(), e.getMessage());
            }
        }

        return ResourceGovernanceSuggestionResponse.builder()
                .id(suggestion.getId())
                .suggestionNo(suggestion.getSuggestionNo())
                .suggestionType(suggestion.getSuggestionType())
                .sourceType(suggestion.getSourceType())
                .sourceId(suggestion.getSourceId())
                .resourcePoolId(suggestion.getResourcePoolId())
                .resourcePoolName(resourcePoolName)
                .envId(suggestion.getEnvId())
                .envName(envName)
                .riskLevel(suggestion.getRiskLevel())
                .title(suggestion.getTitle())
                .description(suggestion.getDescription())
                .status(suggestion.getStatus())
                .reason(suggestion.getReason())
                .createdBy(suggestion.getCreatedBy())
                .createdAt(suggestion.getCreatedAt())
                .updatedAt(suggestion.getUpdatedAt())
                .handledBy(suggestion.getHandledBy())
                .handledAt(suggestion.getHandledAt())
                .handleNote(suggestion.getHandleNote())
                .existingPlan(actionPlanService.getActivePlanBySuggestionId(suggestion.getId()))
                .build();
    }

    private void recordSuggestionCreated(ResourceGovernanceSuggestion suggestion) {
        try {
            governanceRecordService.record(
                    "RESOURCE_GOVERNANCE_SUGGESTION_CREATED",
                    suggestion.getResourcePoolId(),
                    null,
                    null,
                    null,
                    null,
                    "SYSTEM",
                    suggestion.getEnvId(),
                    null,
                    null,
                    "SUGGESTION",
                    suggestion.getId(),
                    null,
                    Map.of(
                            "suggestionNo", suggestion.getSuggestionNo(),
                            "suggestionType", suggestion.getSuggestionType().name(),
                            "riskLevel", suggestion.getRiskLevel().name()
                    ),
                    "SUCCESS",
                    suggestion.getReason(),
                    null
            );
        } catch (Exception e) {
            log.warn("Failed to record governance event for suggestion creation: suggestionNo={}, error={}",
                    suggestion.getSuggestionNo(), e.getMessage());
        }
    }

    private void recordSuggestionAcked(ResourceGovernanceSuggestion suggestion) {
        try {
            governanceRecordService.record(
                    "RESOURCE_GOVERNANCE_SUGGESTION_ACKED",
                    suggestion.getResourcePoolId(),
                    null,
                    null,
                    null,
                    null,
                    "USER",
                    suggestion.getEnvId(),
                    null,
                    null,
                    "SUGGESTION",
                    suggestion.getId(),
                    Map.of("status", SuggestionStatus.OPEN.name()),
                    Map.of("status", SuggestionStatus.ACKED.name()),
                    "SUCCESS",
                    suggestion.getHandleNote(),
                    suggestion.getHandledBy()
            );
        } catch (Exception e) {
            log.warn("Failed to record governance event for suggestion ack: suggestionNo={}, error={}",
                    suggestion.getSuggestionNo(), e.getMessage());
        }
    }

    private void recordSuggestionIgnored(ResourceGovernanceSuggestion suggestion) {
        try {
            governanceRecordService.record(
                    "RESOURCE_GOVERNANCE_SUGGESTION_IGNORED",
                    suggestion.getResourcePoolId(),
                    null,
                    null,
                    null,
                    null,
                    "USER",
                    suggestion.getEnvId(),
                    null,
                    null,
                    "SUGGESTION",
                    suggestion.getId(),
                    Map.of("status", suggestion.getStatus().name()),
                    Map.of("status", SuggestionStatus.IGNORED.name()),
                    "SUCCESS",
                    suggestion.getHandleNote(),
                    suggestion.getHandledBy()
            );
        } catch (Exception e) {
            log.warn("Failed to record governance event for suggestion ignore: suggestionNo={}, error={}",
                    suggestion.getSuggestionNo(), e.getMessage());
        }
    }

    private void recordSuggestionResolved(ResourceGovernanceSuggestion suggestion) {
        try {
            governanceRecordService.record(
                    "RESOURCE_GOVERNANCE_SUGGESTION_RESOLVED",
                    suggestion.getResourcePoolId(),
                    null,
                    null,
                    null,
                    null,
                    "USER",
                    suggestion.getEnvId(),
                    null,
                    null,
                    "SUGGESTION",
                    suggestion.getId(),
                    Map.of("status", suggestion.getStatus().name()),
                    Map.of("status", SuggestionStatus.RESOLVED.name()),
                    "SUCCESS",
                    suggestion.getHandleNote(),
                    suggestion.getHandledBy()
            );
        } catch (Exception e) {
            log.warn("Failed to record governance event for suggestion resolve: suggestionNo={}, error={}",
                    suggestion.getSuggestionNo(), e.getMessage());
        }
    }
}

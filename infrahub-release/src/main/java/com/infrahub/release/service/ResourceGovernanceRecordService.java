package com.infrahub.release.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.infrahub.release.dto.GovernanceRecordSummaryResponse;
import com.infrahub.release.dto.TimelineNodeDto;
import com.infrahub.release.dto.TimelineQueryParams;
import com.infrahub.release.dto.TimelineResponse;
import com.infrahub.release.entity.ResourceGovernanceRecord;
import com.infrahub.release.mapper.ResourceGovernanceRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ResourceGovernanceRecordService {

    private final ResourceGovernanceRecordMapper resourceGovernanceRecordMapper;
    private final ObjectMapper objectMapper;

    public void record(String eventType, Long resourcePoolId, Long hostId, Long runtimeInstanceId,
                       Long releaseOrderId, Long rollbackOrderId, String executorType,
                       Long envId, Long serviceId, Long versionId,
                       String subjectType, Long subjectId,
                       Map<String, ?> beforePayload, Map<String, ?> afterPayload,
                       String resultStatus, String reason, Long createdBy) {
        recordWithEnrichment(eventType, resourcePoolId, hostId, runtimeInstanceId,
                releaseOrderId, rollbackOrderId, executorType,
                envId, serviceId, versionId,
                subjectType, subjectId,
                null, null, null,
                beforePayload, afterPayload,
                resultStatus, reason, createdBy);
    }

    public void recordWithEnrichment(String eventType, Long resourcePoolId, Long hostId, Long runtimeInstanceId,
                                      Long releaseOrderId, Long rollbackOrderId, String executorType,
                                      Long envId, Long serviceId, Long versionId,
                                      String subjectType, Long subjectId,
                                      String actionPlanNo, String actionExecutionNo, String suggestionNo,
                                      Map<String, ?> beforePayload, Map<String, ?> afterPayload,
                                      String resultStatus, String reason, Long createdBy) {
        try {
            ResourceGovernanceRecord record = new ResourceGovernanceRecord();
            record.setEventType(eventType);
            record.setResourcePoolId(resourcePoolId);
            record.setHostId(hostId);
            record.setRuntimeInstanceId(runtimeInstanceId);
            record.setReleaseOrderId(releaseOrderId);
            record.setRollbackOrderId(rollbackOrderId);
            record.setExecutorType(executorType);
            record.setEnvId(envId);
            record.setServiceId(serviceId);
            record.setVersionId(versionId);
            record.setSubjectType(subjectType);
            record.setSubjectId(subjectId);
            record.setActionPlanNo(actionPlanNo);
            record.setActionExecutionNo(actionExecutionNo);
            record.setSuggestionNo(suggestionNo);
            if (beforePayload != null) {
                record.setBeforePayload(objectMapper.writeValueAsString(beforePayload));
            }
            if (afterPayload != null) {
                record.setAfterPayload(objectMapper.writeValueAsString(afterPayload));
            }
            record.setResultStatus(resultStatus);
            record.setReason(reason);
            record.setCreatedBy(createdBy);
            resourceGovernanceRecordMapper.insert(record);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize governance record payload: {}", e.getMessage());
        } catch (Exception e) {
            log.warn("Failed to record governance event: eventType={}, error={}", eventType, e.getMessage());
        }
    }

    public Page<ResourceGovernanceRecord> query(Long resourcePoolId, Long hostId, Long runtimeInstanceId,
                                                   Long releaseOrderId, Long rollbackOrderId,
                                                   Long envId, Long serviceId,
                                                   String eventType, String resultStatus,
                                                   String subjectType, Long subjectId,
                                                   int pageNum, int pageSize) {
        return resourceGovernanceRecordMapper.queryRecords(
                new Page<>(pageNum, pageSize),
                resourcePoolId, hostId, runtimeInstanceId,
                releaseOrderId, rollbackOrderId, envId, serviceId,
                eventType, resultStatus, subjectType, subjectId);
    }

    /**
     * 时间线查询 - 支持时间范围和关联字段筛选
     * Contract: GET /api/assets/resource-governance/records/timeline
     * 支持 ID 和 No 双重筛选
     */
    public Page<ResourceGovernanceRecord> queryTimeline(TimelineQueryParams params) {
        return resourceGovernanceRecordMapper.queryTimeline(
                new Page<>(params.getPageNum(), params.getPageSize()),
                params.getSubjectType(),
                params.getSubjectId(),
                params.getActionPlanId(),
                params.getActionPlanNo(),
                params.getActionExecutionId(),
                params.getActionExecutionNo(),
                params.getSuggestionId(),
                params.getSuggestionNo(),
                params.getEventType(),
                params.getResultStatus(),
                params.getStartTime(),
                params.getEndTime()
        );
    }

    /**
     * 按动作计划编号查询时间线记录
     */
    public List<ResourceGovernanceRecord> queryTimelineByActionPlanNo(String actionPlanNo) {
        return resourceGovernanceRecordMapper.queryTimelineByActionPlanNo(actionPlanNo);
    }

    /**
     * 按执行记录编号查询时间线记录
     */
    public List<ResourceGovernanceRecord> queryTimelineByActionExecutionNo(String actionExecutionNo) {
        return resourceGovernanceRecordMapper.queryTimelineByActionExecutionNo(actionExecutionNo);
    }

    /**
     * 构建时间线响应
     */
    public TimelineResponse buildTimelineResponse(String subjectType, Long subjectId, String subjectNo,
                                                   List<ResourceGovernanceRecord> records) {
        List<TimelineNodeDto> nodes = records.stream()
                .map(this::toTimelineNode)
                .toList();

        return TimelineResponse.builder()
                .subjectType(subjectType)
                .subjectId(subjectId)
                .subjectNo(subjectNo)
                .nodes(nodes)
                .totalCount(nodes.size())
                .build();
    }

    /**
     * 将治理记录转换为时间线节点
     * 包含 payload 字段以支持 IMPACT_RECORDED 事件
     */
    private TimelineNodeDto toTimelineNode(ResourceGovernanceRecord record) {
        TimelineNodeDto.TimelineNodeDtoBuilder builder = TimelineNodeDto.builder()
                .key(String.valueOf(record.getId()))
                .title(buildNodeTitle(record))
                .status(mapResultStatusToNodeStatus(record.getResultStatus()))
                .time(record.getCreatedAt())
                .summary(record.getReason())
                .actionType(record.getEventType())
                .resultStatus(record.getResultStatus());

        // 解析 payload 字段
        Map<String, Object> beforePayload = parseJsonToMap(record.getBeforePayload());
        Map<String, Object> afterPayload = parseJsonToMap(record.getAfterPayload());

        builder.beforePayload(beforePayload);
        builder.afterPayload(afterPayload);

        // 对于 IMPACT_RECORDED 事件，从 afterPayload 中提取影响相关字段
        if ("RESOURCE_GOVERNANCE_ACTION_IMPACT_RECORDED".equals(record.getEventType())) {
            if (afterPayload != null) {
                builder.impactType((String) afterPayload.get("impactType"));
                builder.impactSummary((String) afterPayload.get("impactSummary"));
                builder.impactPayload((Map<String, Object>) afterPayload.get("impactPayload"));
                builder.evidencePayload((Map<String, Object>) afterPayload.get("evidencePayload"));
            }
        }

        return builder.build();
    }

    /**
     * 解析 JSON 字符串为 Map
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonToMap(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse JSON payload: {}", e.getMessage());
            return null;
        }
    }

    private String buildNodeTitle(ResourceGovernanceRecord record) {
        String eventType = record.getEventType();
        if (eventType == null) {
            return "治理事件";
        }
        return switch (eventType) {
            case "RESOURCE_GOVERNANCE_ACTION_EXECUTION_STARTED" -> "执行开始";
            case "RESOURCE_GOVERNANCE_ACTION_EXECUTION_COMPLETED" -> "执行完成";
            case "RESOURCE_GOVERNANCE_ACTION_EXECUTION_FAILED" -> "执行失败";
            case "RESOURCE_GOVERNANCE_ACTION_EXECUTION_CANCELLED" -> "执行取消";
            case "RESOURCE_GOVERNANCE_ACTION_IMPACT_RECORDED" -> "影响已记录";
            case "RESOURCE_GOVERNANCE_ACTION_PLAN_CREATED" -> "计划创建";
            case "RESOURCE_GOVERNANCE_ACTION_PLAN_CONFIRMED" -> "计划确认";
            case "RESOURCE_GOVERNANCE_ACTION_PLAN_CANCELLED" -> "计划取消";
            default -> eventType;
        };
    }

    private String mapResultStatusToNodeStatus(String resultStatus) {
        if (resultStatus == null) {
            return "pending";
        }
        return switch (resultStatus) {
            case "SUCCESS" -> "success";
            case "FAILED" -> "failed";
            case "RUNNING" -> "running";
            case "CANCELLED" -> "failed";
            default -> "pending";
        };
    }

    /**
     * 汇总统计查询
     * Contract: GET /api/assets/resource-governance/records/summary
     */
    public GovernanceRecordSummaryResponse querySummary(Long resourcePoolId, String eventType,
                                                         String resultStatus, String subjectType) {
        List<Map<String, Object>> summaryData = resourceGovernanceRecordMapper.querySummaryByResourcePool(
                resourcePoolId, eventType, resultStatus, subjectType);

        if (summaryData.isEmpty()) {
            return GovernanceRecordSummaryResponse.builder()
                    .resourcePoolId(resourcePoolId)
                    .totalExecutions(0L)
                    .successExecutions(0L)
                    .failedExecutions(0L)
                    .cancelledExecutions(0L)
                    .avgDurationMs(0.0)
                    .impactByType(new HashMap<>())
                    .build();
        }

        Map<String, Object> data = summaryData.get(0);

        Map<String, Long> impactByType = new HashMap<>();
        List<Map<String, Object>> impactData = resourceGovernanceRecordMapper.queryImpactByType(
                resourcePoolId, subjectType);
        for (Map<String, Object> impact : impactData) {
            String type = (String) impact.get("eventType");
            Long count = ((Number) impact.get("count")).longValue();
            impactByType.put(type, count);
        }

        return GovernanceRecordSummaryResponse.builder()
                .resourcePoolId(resourcePoolId)
                .totalExecutions(getLongValue(data, "totalExecutions"))
                .successExecutions(getLongValue(data, "successExecutions"))
                .failedExecutions(getLongValue(data, "failedExecutions"))
                .cancelledExecutions(getLongValue(data, "cancelledExecutions"))
                .avgDurationMs(0.0)
                .lastExecutionAt((OffsetDateTime) data.get("lastExecutionAt"))
                .impactByType(impactByType)
                .build();
    }

    private Long getLongValue(Map<String, Object> data, String key) {
        Object value = data.get(key);
        if (value == null) {
            return 0L;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return 0L;
    }
}

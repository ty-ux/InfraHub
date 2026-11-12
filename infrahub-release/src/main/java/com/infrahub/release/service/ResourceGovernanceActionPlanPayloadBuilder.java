package com.infrahub.release.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.infrahub.release.enums.SuggestionType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class ResourceGovernanceActionPlanPayloadBuilder {

    private static final String EXECUTION_MODE_MANUAL_ONLY = "MANUAL_ONLY";
    private static final String RECOMMENDATION_SCALE_OUT = "增加资源容量或新增实例";
    private static final String RECOMMENDATION_CHECK_HOST = "检查离线或异常主机";
    private static final String RECOMMENDATION_INVESTIGATE_CAPACITY = "调查容量风险来源";
    private static final String RECOMMENDATION_CLEANUP_INSTANCE = "检查候选异常实例";

    private final ObjectMapper objectMapper;

    public String buildPayload(SuggestionType suggestionType, Long resourcePoolId, Long envId, String riskLevel) {
        if (suggestionType == null) {
            return null;
        }

        Map<String, Object> payload = switch (suggestionType) {
            case SCALE_OUT -> buildScaleOutPayload(resourcePoolId, envId, riskLevel);
            case CHECK_HOST -> buildCheckHostPayload(resourcePoolId, envId);
            case INVESTIGATE_CAPACITY -> buildInvestigateCapacityPayload();
            case CLEANUP_INSTANCE -> buildCleanupInstancePayload();
        };

        return toJsonString(payload);
    }

    private Map<String, Object> buildScaleOutPayload(Long resourcePoolId, Long envId, String riskLevel) {
        return Map.of(
                "recommendation", RECOMMENDATION_SCALE_OUT,
                "resourcePoolId", resourcePoolId != null ? resourcePoolId : "",
                "envId", envId != null ? envId : "",
                "riskLevel", riskLevel != null ? riskLevel : "",
                "suggestedActions", List.of(
                        "review_capacity",
                        "prepare_capacity_change",
                        "manual_approval_required"
                ),
                "executionMode", EXECUTION_MODE_MANUAL_ONLY
        );
    }

    private Map<String, Object> buildCheckHostPayload(Long resourcePoolId, Long envId) {
        return Map.of(
                "recommendation", RECOMMENDATION_CHECK_HOST,
                "resourcePoolId", resourcePoolId != null ? resourcePoolId : "",
                "envId", envId != null ? envId : "",
                "suggestedActions", List.of(
                        "check_agent_status",
                        "check_host_connectivity",
                        "review_recent_alerts"
                ),
                "executionMode", EXECUTION_MODE_MANUAL_ONLY
        );
    }

    private Map<String, Object> buildInvestigateCapacityPayload() {
        return Map.of(
                "recommendation", RECOMMENDATION_INVESTIGATE_CAPACITY,
                "suggestedActions", List.of(
                        "review_capacity_metrics",
                        "review_runtime_instances",
                        "review_monitor_snapshots"
                ),
                "executionMode", EXECUTION_MODE_MANUAL_ONLY
        );
    }

    private Map<String, Object> buildCleanupInstancePayload() {
        return Map.of(
                "recommendation", RECOMMENDATION_CLEANUP_INSTANCE,
                "suggestedActions", List.of(
                        "review_runtime_instance",
                        "confirm_owner",
                        "manual_cleanup_only"
                ),
                "executionMode", EXECUTION_MODE_MANUAL_ONLY
        );
    }

    private String toJsonString(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize action plan payload: {}", e.getMessage());
            return "{}";
        }
    }
}

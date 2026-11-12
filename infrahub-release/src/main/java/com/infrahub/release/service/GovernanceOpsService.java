package com.infrahub.release.service;

import com.infrahub.release.dto.GovernanceOpsOverviewResponse;
import com.infrahub.release.dto.GovernanceOpsQuery;
import com.infrahub.release.dto.GovernanceTrendPoint;
import com.infrahub.release.enums.OpsInterval;
import com.infrahub.release.mapper.GovernanceOpsMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 治理运营服务
 * Contract: v2-stage-l-contract.md
 * L-A: 治理运营总览与趋势 API 后端实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GovernanceOpsService {

    private final GovernanceOpsMapper governanceOpsMapper;

    private static final double DEFAULT_AVG_DURATION_MS = 0.0;

    /**
     * 查询治理运营总览
     * Contract: GET /api/assets/governance-ops/overview
     */
    public GovernanceOpsOverviewResponse queryOverview(GovernanceOpsQuery query) {
        Long envId = query.getEnvId();
        Long resourcePoolId = query.getResourcePoolId();
        OffsetDateTime startTime = query.getStartTime();
        OffsetDateTime endTime = query.getEndTime();

        Long totalRiskCount = governanceOpsMapper.countTotalRisks(envId, resourcePoolId, startTime, endTime);
        Long criticalRiskCount = governanceOpsMapper.countRisksByLevel(envId, resourcePoolId, "CRITICAL", startTime, endTime);
        Long warningRiskCount = governanceOpsMapper.countRisksByLevel(envId, resourcePoolId, "WARNING", startTime, endTime);
        Long openSuggestionCount = governanceOpsMapper.countOpenSuggestions(envId, resourcePoolId, startTime, endTime);
        Long confirmedPlanCount = governanceOpsMapper.countConfirmedPlans(envId, resourcePoolId, startTime, endTime);
        Long runningExecutionCount = governanceOpsMapper.countRunningExecutions(envId, resourcePoolId, startTime, endTime);
        Long completedExecutionCount = governanceOpsMapper.countExecutionsByStatus(envId, resourcePoolId, "SUCCESS", startTime, endTime);
        Long failedExecutionCount = governanceOpsMapper.countExecutionsByStatus(envId, resourcePoolId, "FAILED", startTime, endTime);
        Long impactRecordedCount = governanceOpsMapper.countImpactRecorded(envId, resourcePoolId, startTime, endTime);
        Double avgCloseDurationMs = governanceOpsMapper.calculateAvgCloseDurationMs(envId, resourcePoolId, startTime, endTime);

        GovernanceOpsOverviewResponse response = new GovernanceOpsOverviewResponse();
        response.setTotalRiskCount(totalRiskCount != null ? totalRiskCount : 0L);
        response.setCriticalRiskCount(criticalRiskCount != null ? criticalRiskCount : 0L);
        response.setWarningRiskCount(warningRiskCount != null ? warningRiskCount : 0L);
        response.setOpenSuggestionCount(openSuggestionCount != null ? openSuggestionCount : 0L);
        response.setConfirmedPlanCount(confirmedPlanCount != null ? confirmedPlanCount : 0L);
        response.setRunningExecutionCount(runningExecutionCount != null ? runningExecutionCount : 0L);
        response.setCompletedExecutionCount(completedExecutionCount != null ? completedExecutionCount : 0L);
        response.setFailedExecutionCount(failedExecutionCount != null ? failedExecutionCount : 0L);
        response.setImpactRecordedCount(impactRecordedCount != null ? impactRecordedCount : 0L);
        response.setAvgCloseDurationMs(avgCloseDurationMs != null ? avgCloseDurationMs : DEFAULT_AVG_DURATION_MS);
        response.setManualOnly(true);
        return response;
    }

    /**
     * 查询治理趋势数据
     * Contract: GET /api/assets/governance-ops/trends
     */
    public List<GovernanceTrendPoint> queryTrends(GovernanceOpsQuery query) {
        Long envId = query.getEnvId();
        Long resourcePoolId = query.getResourcePoolId();

        OffsetDateTime startTime = query.getStartTime();
        OffsetDateTime endTime = query.getEndTime();

        if (startTime == null) {
            startTime = OffsetDateTime.now().minusDays(7);
        }
        if (endTime == null) {
            endTime = OffsetDateTime.now();
        }

        OpsInterval interval = parseInterval(query.getInterval());
        List<Map<String, Object>> results;

        if (interval == OpsInterval.HOUR) {
            results = governanceOpsMapper.queryTrendsByHour(envId, resourcePoolId, startTime, endTime);
        } else if (interval == OpsInterval.DAY) {
            results = governanceOpsMapper.queryTrendsByDay(envId, resourcePoolId, startTime, endTime);
        } else if (interval == OpsInterval.WEEK) {
            results = governanceOpsMapper.queryTrendsByWeek(envId, resourcePoolId, startTime, endTime);
        } else if (interval == OpsInterval.MONTH) {
            results = governanceOpsMapper.queryTrendsByMonth(envId, resourcePoolId, startTime, endTime);
        } else {
            results = Collections.emptyList();
        }

        return results.stream()
                .map(this::mapToTrendPoint)
                .toList();
    }

    private OpsInterval parseInterval(String interval) {
        if (interval == null || interval.isEmpty()) {
            return OpsInterval.DAY;
        }
        try {
            return OpsInterval.valueOf(interval);
        } catch (IllegalArgumentException e) {
            log.warn("Invalid interval value: {}, defaulting to DAY", interval);
            return OpsInterval.DAY;
        }
    }

    private GovernanceTrendPoint mapToTrendPoint(Map<String, Object> row) {
        GovernanceTrendPoint point = new GovernanceTrendPoint();
        point.setTimeBucket(getStringValue(row, "timebucket"));
        point.setRiskCount(getLongValue(row, "riskcount"));
        point.setSuggestionCount(getLongValue(row, "suggestioncount"));
        point.setActionPlanCount(getLongValue(row, "actionplancount"));
        point.setExecutionCount(getLongValue(row, "executioncount"));
        point.setImpactCount(getLongValue(row, "impactcount"));
        point.setSuccessCount(getLongValue(row, "successcount"));
        point.setFailedCount(getLongValue(row, "failedcount"));
        return point;
    }

    private String getStringValue(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value != null ? value.toString() : null;
    }

    private Long getLongValue(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value == null) {
            return 0L;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}

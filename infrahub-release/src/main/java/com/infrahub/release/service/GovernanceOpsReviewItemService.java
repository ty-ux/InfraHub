package com.infrahub.release.service;

import com.infrahub.release.dto.GovernanceReviewItemPageResponse;
import com.infrahub.release.dto.GovernanceReviewItemQuery;
import com.infrahub.release.dto.GovernanceReviewItemResponse;
import com.infrahub.release.mapper.GovernanceOpsMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 治理运营复盘条目服务
 * Contract: v2-stage-l-contract.md
 * L-C: 复盘条目列表 API 后端实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GovernanceOpsReviewItemService {

    private final GovernanceOpsMapper governanceOpsMapper;

    /**
     * 查询复盘条目分页列表
     * Contract: GET /api/assets/governance-ops/review-items
     */
    public GovernanceReviewItemPageResponse queryReviewItems(GovernanceReviewItemQuery query) {
        Long envId = query.getEnvId();
        Long resourcePoolId = query.getResourcePoolId();
        Long serviceId = query.getServiceId();
        String riskLevel = query.getRiskLevel();
        String suggestionStatus = query.getSuggestionStatus();
        String actionPlanStatus = query.getActionPlanStatus();
        String executionStatus = query.getExecutionStatus();
        String impactType = query.getImpactType();

        int pageNum = query.getPageNum() != null ? query.getPageNum() : 1;
        int pageSize = query.getPageSize() != null ? query.getPageSize() : 20;

        List<GovernanceReviewItemResponse> allItems = new ArrayList<>();

        List<Map<String, Object>> riskItems = governanceOpsMapper.queryRiskReviewItems(
                envId, resourcePoolId, serviceId, riskLevel, suggestionStatus);
        allItems.addAll(mapToResponses(riskItems));

        List<Map<String, Object>> suggestionItems = governanceOpsMapper.querySuggestionReviewItems(
                envId, resourcePoolId, serviceId, riskLevel, suggestionStatus);
        allItems.addAll(mapToResponses(suggestionItems));

        List<Map<String, Object>> actionPlanItems = governanceOpsMapper.queryActionPlanReviewItems(
                envId, resourcePoolId, actionPlanStatus);
        allItems.addAll(mapToResponses(actionPlanItems));

        List<Map<String, Object>> executionItems = governanceOpsMapper.queryExecutionReviewItems(
                envId, resourcePoolId, executionStatus);
        allItems.addAll(mapToResponses(executionItems));

        List<Map<String, Object>> impactItems = governanceOpsMapper.queryImpactReviewItems(
                envId, resourcePoolId, impactType);
        allItems.addAll(mapToResponses(impactItems));

        allItems.sort(Comparator.comparing(
                GovernanceReviewItemResponse::getCreatedAt,
                Comparator.nullsLast(Comparator.reverseOrder())
        ));

        long total = allItems.size();
        int fromIndex = (pageNum - 1) * pageSize;
        int toIndex = Math.min(fromIndex + pageSize, allItems.size());

        List<GovernanceReviewItemResponse> pagedRecords;
        if (fromIndex >= allItems.size()) {
            pagedRecords = new ArrayList<>();
        } else {
            pagedRecords = allItems.subList(fromIndex, toIndex);
        }

        GovernanceReviewItemPageResponse response = new GovernanceReviewItemPageResponse();
        response.setRecords(pagedRecords);
        response.setTotal(total);
        response.setPageNum(pageNum);
        response.setPageSize(pageSize);
        return response;
    }

    private List<GovernanceReviewItemResponse> mapToResponses(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return new ArrayList<>();
        }
        return rows.stream()
                .map(this::mapToResponse)
                .toList();
    }

    private GovernanceReviewItemResponse mapToResponse(Map<String, Object> row) {
        GovernanceReviewItemResponse response = new GovernanceReviewItemResponse();
        response.setItemType(getStringValue(row, "itemtype"));
        response.setItemId(getLongValue(row, "itemid"));
        response.setItemNo(getStringValue(row, "itemno"));
        response.setTitle(getStringValue(row, "title"));
        response.setRiskLevel(getStringValue(row, "risklevel"));
        response.setStatus(getStringValue(row, "status"));
        response.setReason(getStringValue(row, "reason"));
        response.setOwnerName(getStringValue(row, "ownername"));
        response.setCreatedAt(getOffsetDateTimeValue(row, "createdat"));
        response.setDueAt(getOffsetDateTimeValue(row, "dueat"));
        response.setRelatedActionPlanNo(getStringValue(row, "relatedactionplanno"));
        response.setRelatedExecutionNo(getStringValue(row, "relatedexecutionno"));
        response.setRelatedSuggestionNo(getStringValue(row, "relatedsuggestionno"));
        return response;
    }

    private String getStringValue(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value != null ? value.toString() : null;
    }

    private Long getLongValue(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private OffsetDateTime getOffsetDateTimeValue(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof OffsetDateTime) {
            return (OffsetDateTime) value;
        }
        return null;
    }
}

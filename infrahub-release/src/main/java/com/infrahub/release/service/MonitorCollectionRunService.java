package com.infrahub.release.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.infrahub.release.dto.CollectMonitorTargetResponse;
import com.infrahub.release.dto.MonitorCollectionItemResult;
import com.infrahub.release.dto.RunOnceMonitorCollectionRequest;
import com.infrahub.release.dto.RunOnceMonitorCollectionResponse;
import com.infrahub.release.entity.MonitorMetricTarget;
import com.infrahub.release.mapper.MonitorMetricTargetMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

import static com.infrahub.release.service.MonitorAuditLogService.*;

/**
 * Service for run-once batch monitor collection.
 * Scans all enabled monitor targets and collects metrics one by one.
 * E3.3: Added collection retry mechanism.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MonitorCollectionRunService {

    private static final int MAX_RETRIES = 2;

    private final MonitorMetricTargetMapper targetMapper;
    private final MonitorCollectionService collectionService;
    private final MonitorAuditLogService auditLogService;

    /**
     * Run batch collection on all enabled monitor targets.
     *
     * @param request optional filter by target type
     * @return collection summary with results
     */
    public RunOnceMonitorCollectionResponse runOnce(RunOnceMonitorCollectionRequest request) {
        // Query enabled targets
        LambdaQueryWrapper<MonitorMetricTarget> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MonitorMetricTarget::getStatus, "ENABLED");

        if (request != null && request.getTargetType() != null && !request.getTargetType().isBlank()) {
            wrapper.eq(MonitorMetricTarget::getTargetType, request.getTargetType());
        }

        List<MonitorMetricTarget> targets = targetMapper.selectList(wrapper);

        if (targets.isEmpty()) {
            return RunOnceMonitorCollectionResponse.builder()
                    .totalCount(0)
                    .successCount(0)
                    .failureCount(0)
                    .results(List.of())
                    .build();
        }

        // Collect from each target
        List<MonitorCollectionItemResult> results = new ArrayList<>();
        int successCount = 0;
        int failureCount = 0;

        for (MonitorMetricTarget target : targets) {
            // E3.3: 添加重试机制
            MonitorCollectionItemResult itemResult = collectWithRetry(target);
            results.add(itemResult);

            if (itemResult.isSuccess()) {
                successCount++;
            } else {
                failureCount++;
            }
        }

        RunOnceMonitorCollectionResponse response = RunOnceMonitorCollectionResponse.builder()
                .totalCount(targets.size())
                .successCount(successCount)
                .failureCount(failureCount)
                .results(results)
                .build();

        // Record audit log for run-once collection
        String result = failureCount == 0 ? RESULT_SUCCESS : RESULT_FAILED;
        auditLogService.recordRunOnceCollect(result,
                auditData("totalCount", response.getTotalCount(), "successCount", response.getSuccessCount(),
                        "failureCount", response.getFailureCount()));

        return response;
    }

    /**
     * E3.3: 采集带重试机制
     * 失败后最多重试 MAX_RETRIES 次
     */
    private MonitorCollectionItemResult collectWithRetry(MonitorMetricTarget target) {
        int retryCount = 0;
        String lastMessage = null;

        while (retryCount <= MAX_RETRIES) {
            try {
                CollectMonitorTargetResponse response = collectionService.collect(target.getId());

                if (response.isSuccess()) {
                    return MonitorCollectionItemResult.builder()
                            .monitorTargetId(target.getId())
                            .targetType(response.getTargetType())
                            .targetId(response.getTargetId())
                            .success(true)
                            .snapshotId(response.getSnapshotId())
                            .message(response.getMessage() + (retryCount > 0 ? " (重试" + retryCount + "次成功)" : ""))
                            .build();
                }

                lastMessage = response.getMessage();
                if (retryCount < MAX_RETRIES) {
                    log.warn("Collection failed for target {}, retrying ({}/{}): {}",
                            target.getId(), retryCount + 1, MAX_RETRIES, lastMessage);
                }
            } catch (Exception e) {
                lastMessage = "采集异常: " + e.getMessage();
                if (retryCount < MAX_RETRIES) {
                    log.warn("Exception collecting from target {}, retrying ({}/{}): {}",
                            target.getId(), retryCount + 1, MAX_RETRIES, e.getMessage());
                } else {
                    log.error("Unexpected error collecting from target {} after {} retries: {}",
                            target.getId(), MAX_RETRIES, e.getMessage());
                }
            }

            retryCount++;
        }

        // 所有重试都失败
        return MonitorCollectionItemResult.builder()
                .monitorTargetId(target.getId())
                .targetType(target.getTargetType())
                .targetId(target.getTargetId())
                .success(false)
                .message(lastMessage + " (重试" + MAX_RETRIES + "次均失败)")
                .build();
    }
}

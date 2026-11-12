package com.infrahub.release.service;

import com.infrahub.release.dto.MonitorCollectionScheduleStatus;
import com.infrahub.release.dto.RunOnceMonitorCollectionRequest;
import com.infrahub.release.dto.RunOnceMonitorCollectionResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;

/**
 * Scheduled collection service for automatic batch monitoring.
 * Only active when infrahub.monitor.collection.enabled=true.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "infrahub.monitor.collection.enabled", havingValue = "true")
public class MonitorScheduledCollectionService {

    private final MonitorCollectionRunService runService;

    @Value("${infrahub.monitor.collection.fixed-delay-ms:60000}")
    private long fixedDelayMs;

    private volatile MonitorCollectionScheduleStatus lastStatus;

    /**
     * Scheduled collection task - runs automatically based on fixed-delay-ms.
     * Called by Spring Scheduler when enabled=true.
     */
    @Scheduled(fixedDelayString = "${infrahub.monitor.collection.fixed-delay-ms:60000}")
    public void scheduledCollection() {
        log.info("Starting scheduled collection run...");
        OffsetDateTime startTime = OffsetDateTime.now();

        try {
            RunOnceMonitorCollectionResponse response = runService.runOnce(new RunOnceMonitorCollectionRequest());

            lastStatus = MonitorCollectionScheduleStatus.builder()
                    .enabled(true)
                    .fixedDelayMs(fixedDelayMs)
                    .lastRunAt(startTime)
                    .lastTotalCount(response.getTotalCount())
                    .lastSuccessCount(response.getSuccessCount())
                    .lastFailureCount(response.getFailureCount())
                    .lastMessage(String.format("成功 %d / 失败 %d / 总计 %d",
                            response.getSuccessCount(),
                            response.getFailureCount(),
                            response.getTotalCount()))
                    .build();

            log.info("Scheduled collection completed: total={}, success={}, failure={}",
                    response.getTotalCount(), response.getSuccessCount(), response.getFailureCount());

        } catch (Exception e) {
            log.error("Scheduled collection failed: {}", e.getMessage(), e);

            lastStatus = MonitorCollectionScheduleStatus.builder()
                    .enabled(true)
                    .fixedDelayMs(fixedDelayMs)
                    .lastRunAt(startTime)
                    .lastTotalCount(0)
                    .lastSuccessCount(0)
                    .lastFailureCount(0)
                    .lastMessage("执行异常: " + e.getMessage())
                    .build();
        }
    }

    /**
     * Get current scheduled collection status.
     * Returns status with enabled=false when never run.
     */
    public MonitorCollectionScheduleStatus getStatus() {
        if (lastStatus == null) {
            return MonitorCollectionScheduleStatus.builder()
                    .enabled(false)
                    .fixedDelayMs(fixedDelayMs)
                    .lastRunAt(null)
                    .lastTotalCount(0)
                    .lastSuccessCount(0)
                    .lastFailureCount(0)
                    .lastMessage("未运行")
                    .build();
        }
        return lastStatus;
    }
}
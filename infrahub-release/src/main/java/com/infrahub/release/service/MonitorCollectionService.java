package com.infrahub.release.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.infrahub.release.dto.CollectMonitorTargetResponse;
import com.infrahub.release.entity.MonitorMetricTarget;
import com.infrahub.release.entity.MonitorSnapshot;
import com.infrahub.release.mapper.MonitorSnapshotMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;

import static com.infrahub.release.service.MonitorAuditLogService.*;

/**
 * Service for collecting metrics from monitor targets.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MonitorCollectionService {

    private final MonitorMetricTargetService targetService;
    private final MonitorMetricCollector collector;
    private final MonitorSnapshotMapper snapshotMapper;
    private final ObjectMapper objectMapper;
    private final MonitorAuditLogService auditLogService;

    /**
     * Collect metrics from a monitor target and save as snapshot.
     *
     * @param targetId the monitor target ID
     * @return collection response with snapshot ID and status
     */
    public CollectMonitorTargetResponse collect(Long targetId) {
        // Find target
        MonitorMetricTarget target = targetService.getById(targetId);
        if (target == null) {
            return CollectMonitorTargetResponse.builder()
                    .success(false)
                    .message("监控目标不存在")
                    .build();
        }

        // Check if target is enabled
        if (!"ENABLED".equalsIgnoreCase(target.getStatus())) {
            return CollectMonitorTargetResponse.builder()
                    .targetType(target.getTargetType())
                    .targetId(target.getTargetId())
                    .success(false)
                    .message("监控目标未启用")
                    .build();
        }

        // Parse scrape config
        String scrapeConfig = target.getScrapeConfig();
        if (scrapeConfig == null || scrapeConfig.isBlank()) {
            return CollectMonitorTargetResponse.builder()
                    .targetType(target.getTargetType())
                    .targetId(target.getTargetId())
                    .success(false)
                    .message("监控目标缺少采集配置")
                    .build();
        }

        String url;
        int timeoutSeconds = 5;
        String format = "json";
        try {
            JsonNode configNode = objectMapper.readTree(scrapeConfig);
            JsonNode urlNode = configNode.get("url");
            if (urlNode == null || urlNode.asText().isBlank()) {
                return CollectMonitorTargetResponse.builder()
                        .targetType(target.getTargetType())
                        .targetId(target.getTargetId())
                        .success(false)
                        .message("采集配置缺少 URL")
                        .build();
            }
            url = urlNode.asText();
            JsonNode timeoutNode = configNode.get("timeoutSeconds");
            if (timeoutNode != null && timeoutNode.isNumber()) {
                timeoutSeconds = timeoutNode.asInt();
            }
            JsonNode formatNode = configNode.get("format");
            if (formatNode != null && !formatNode.asText().isBlank()) {
                format = formatNode.asText();
            }
        } catch (Exception e) {
            log.warn("Failed to parse scrape config for target {}: {}", targetId, e.getMessage());
            return CollectMonitorTargetResponse.builder()
                    .targetType(target.getTargetType())
                    .targetId(target.getTargetId())
                    .success(false)
                    .message("采集配置格式错误")
                    .build();
        }

        // Collect metrics
        String metricPayload;
        try {
            metricPayload = collector.collect(url, timeoutSeconds, format);
        } catch (MonitorMetricCollector.CollectionException e) {
            log.warn("Collection failed for target {}: {}", targetId, e.getMessage());
            return CollectMonitorTargetResponse.builder()
                    .targetType(target.getTargetType())
                    .targetId(target.getTargetId())
                    .success(false)
                    .message(e.getMessage())
                    .build();
        }

        // Save snapshot
        MonitorSnapshot snapshot = new MonitorSnapshot();
        snapshot.setTargetType(target.getTargetType());
        snapshot.setTargetId(target.getTargetId());
        snapshot.setMetricPayload(metricPayload);
        snapshot.setSource("COLLECTOR");
        snapshot.setSnapshotTime(OffsetDateTime.now());
        snapshot.setCreatedAt(OffsetDateTime.now());
        snapshotMapper.insertManualSnapshot(snapshot);

        log.info("Successfully collected and saved snapshot {} for target {}", snapshot.getId(), targetId);

        // Record audit log for manual collection
        auditLogService.recordManualCollect(targetId, RESULT_SUCCESS,
                auditData("snapshotId", snapshot.getId(), "targetType", snapshot.getTargetType(),
                        "targetId", snapshot.getTargetId(), "source", snapshot.getSource()));

        return CollectMonitorTargetResponse.builder()
                .snapshotId(snapshot.getId())
                .targetType(target.getTargetType())
                .targetId(target.getTargetId())
                .source("COLLECTOR")
                .success(true)
                .message("采集成功")
                .build();
    }
}

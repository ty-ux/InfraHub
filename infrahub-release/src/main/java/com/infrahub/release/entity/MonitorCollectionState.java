package com.infrahub.release.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 主机/实例状态采集状态：每个目标一行。
 *
 * <p>本表**不存放 Agent 心跳**。{@code lastHeartbeat} 仍在 asset_host /
 * runtime_service_instance 上，且采集永远不写它。
 *
 * @see docs/release/v2-host-instance-status-collection-contract.md 第 3.1 节
 */
@Data
@TableName("monitor_collection_state")
public class MonitorCollectionState {

    private Long id;
    private String targetType;
    private Long targetId;
    private String observerType;
    private Boolean configPresent;
    private String lastSource;
    private String lastResult;
    private OffsetDateTime lastAttemptAt;
    private OffsetDateTime lastSuccessAt;
    private Integer consecutiveFailureCount;
    private String lastErrorCategory;
    private String lastErrorSummary;
    private String lastObservedStatus;
    private String lastObservedHealth;
    private OffsetDateTime observedAt;
    private Integer discardedSampleCount;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
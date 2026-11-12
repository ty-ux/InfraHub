package com.infrahub.release.dto;

import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 移除运行实例登记的结果。
 *
 * <p>{@code processStopped} 恒为 false：本操作只移除 InfraHub 的登记记录，
 * 不对服务器上的进程做任何操作，也不得被表述为"服务已停止"。
 */
@Data
public class RuntimeInstanceRemovalResponse {

    private Long instanceId;
    private String instanceName;
    private Long serviceId;
    private Long envId;
    private Long hostId;

    private boolean removed;
    /** 恒为 false：移除登记不等于停止进程 */
    private boolean processStopped;
    /** 移除前登记表里记录的生命周期状态；这是历史记录值，不是实时探测结果 */
    private String lastRecordedInstanceStatus;
    private String lastRecordedHealthStatus;
    /** 是否同时清理了该实例的采集状态行（monitor_collection_state） */
    private boolean collectionStateRemoved;

    private String message;
    private OffsetDateTime removedAt;
}

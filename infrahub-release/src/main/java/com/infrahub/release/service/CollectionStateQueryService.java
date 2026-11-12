package com.infrahub.release.service;

import com.infrahub.common.error.ErrorCode;
import com.infrahub.common.exception.BizException;
import com.infrahub.release.config.HostInstanceCollectionProperties;
import com.infrahub.release.dto.CollectionStateListResponse;
import com.infrahub.release.dto.CollectionStateView;
import com.infrahub.release.entity.MonitorCollectionState;
import com.infrahub.release.mapper.MonitorCollectionStateMapper;
import com.infrahub.release.support.CollectionFreshness;
import com.infrahub.release.support.CollectionResult;
import com.infrahub.release.support.CollectionSource;
import com.infrahub.release.support.CollectionTargetType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 采集状态读模型。**只读数据库，不发起任何探测**。
 *
 * <p>每一个被请求的目标都会返回一条记录：没有采集行时返回"尚未采集"的默认视图，
 * 让界面能区分"没采过"与"采过但失败"，而不是靠字段缺失去猜。
 */
@Service
@RequiredArgsConstructor
public class CollectionStateQueryService {

    private final MonitorCollectionStateMapper monitorCollectionStateMapper;
    private final HostInstanceCollectionProperties properties;
    private final Clock collectionClock;
    private final HostInstanceCollectionControlService control;


    public CollectionStateListResponse listStates(String targetType, List<Long> targetIds) {
        if (!CollectionTargetType.isSupported(targetType)) {
            throw new BizException(ErrorCode.VALIDATION_ERROR,
                    "不支持的目标类型: " + targetType + "，仅支持 " + CollectionTargetType.HOST
                            + " / " + CollectionTargetType.SERVICE_INSTANCE);
        }

        List<Long> distinctIds = targetIds == null ? List.of() : targetIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (distinctIds.isEmpty()) {
            return new CollectionStateListResponse(isCollectionEnabled(), properties.getStaleThresholdMs(), List.of());
        }

        Map<Long, MonitorCollectionState> byTarget = new LinkedHashMap<>();
        for (MonitorCollectionState state : monitorCollectionStateMapper.selectByTargets(targetType, distinctIds)) {
            byTarget.put(state.getTargetId(), state);
        }

        OffsetDateTime now = OffsetDateTime.now(collectionClock);
        List<CollectionStateView> views = new ArrayList<>(distinctIds.size());
        for (Long targetId : distinctIds) {
            views.add(toView(targetType, targetId, byTarget.get(targetId), now));
        }
        return new CollectionStateListResponse(isCollectionEnabled(), properties.getStaleThresholdMs(), views);
    }

    /** 与调度器读取同一运行时开关。 */
    public boolean isCollectionEnabled() {
        return control.isEnabled();
    }

    private CollectionStateView toView(String targetType, Long targetId, MonitorCollectionState state,
                                       OffsetDateTime now) {
        if (state == null) {
            return new CollectionStateView(targetType, targetId,
                    HostInstanceCollectionService.OBSERVER_NONE, CollectionSource.NONE.name(), false,
                    CollectionResult.NONE.name(), CollectionFreshness.NONE.name(),
                    null, null, null, 0, null, null, null, null, 0);
        }
        return new CollectionStateView(
                targetType,
                targetId,
                state.getObserverType(),
                state.getLastSource(),
                Boolean.TRUE.equals(state.getConfigPresent()),
                state.getLastResult(),
                CollectionFreshness.of(state.getLastSuccessAt(), now, properties.getStaleThresholdMs()).name(),
                state.getLastAttemptAt(),
                state.getLastSuccessAt(),
                state.getObservedAt(),
                state.getConsecutiveFailureCount(),
                state.getLastErrorCategory(),
                state.getLastErrorSummary(),
                state.getLastObservedStatus(),
                state.getLastObservedHealth(),
                state.getDiscardedSampleCount());
    }
}
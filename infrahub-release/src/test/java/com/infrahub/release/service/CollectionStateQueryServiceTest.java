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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * 采集状态读模型。
 *
 * <p>这个类被单独钉住的理由是它**不得发起探测**：前端轮询它只读数据库。它还得回答一个界面上
 * 必须能回答的问题——"自动采集到底开没开"，否则用户会对着一个永远不变的数字以为状态是新鲜的。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CollectionStateQueryServiceTest {

    private static final OffsetDateTime T0 = OffsetDateTime.of(2026, 1, 1, 12, 0, 0, 0, ZoneOffset.UTC);
    private static final Clock FIXED_CLOCK = Clock.fixed(T0.toInstant(), ZoneOffset.UTC);
    private static final long STALE_MS = 90_000L;

    @Mock
    private MonitorCollectionStateMapper monitorCollectionStateMapper;

    private HostInstanceCollectionProperties properties;
    private CollectionStateQueryService service;
    private HostInstanceCollectionControlService control;

    @BeforeEach
    void setUp() {
        properties = new HostInstanceCollectionProperties();
        properties.setStaleThresholdMs(STALE_MS);
        control = new HostInstanceCollectionControlService(properties, false);
        service = new CollectionStateQueryService(monitorCollectionStateMapper, properties, FIXED_CLOCK, control);
        when(monitorCollectionStateMapper.selectByTargets(anyString(), any())).thenReturn(List.of());
    }

    // ---------- 入参校验 ----------

    @Test
    @DisplayName("不支持的目标类型：拒绝，并在文案里点名支持哪两种")
    void unsupportedTargetTypeIsRejected() {
        assertThatThrownBy(() -> service.listStates("INSTANCE", List.of(1L)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不支持的目标类型")
                .hasMessageContaining(CollectionTargetType.HOST)
                .hasMessageContaining(CollectionTargetType.SERVICE_INSTANCE);
        assertThatThrownBy(() -> service.listStates(null, List.of(1L)))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> service.listStates("INSTANCE", List.of(1L)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    @Test
    @DisplayName("目标 id 列表为空：返回空视图，但仍汇报采集开关与过期阈值")
    void emptyTargetIdsReturnNoViewsButStillReportThresholds() {
        CollectionStateListResponse response = service.listStates(CollectionTargetType.HOST, List.of());

        assertThat(response.states()).isEmpty();
        assertThat(response.staleThresholdMs()).as("阈值要能汇报，界面才知道多少算过期").isEqualTo(STALE_MS);
        verify(monitorCollectionStateMapper, never()).selectByTargets(anyString(), any());
    }

    @Test
    @DisplayName("目标 id 列表为 null 且全为 null 元素：不抛异常、不查库")
    void nullAndAllNullIdsAreSafe() {
        assertThat(service.listStates(CollectionTargetType.HOST, null).states()).isEmpty();
        assertThat(service.listStates(CollectionTargetType.HOST, Arrays.asList(null, null)).states()).isEmpty();
        verify(monitorCollectionStateMapper, never()).selectByTargets(anyString(), any());
    }

    // ---------- 默认视图 ----------

    @Test
    @DisplayName("从未采集过的目标：返回'尚未采集'默认视图，而不是靠字段缺失去猜")
    void neverCollectedTargetGetsExplicitDefaultView() {
        CollectionStateListResponse response = service.listStates(CollectionTargetType.HOST, List.of(7L));

        assertThat(response.states()).hasSize(1);
        CollectionStateView view = response.states().get(0);
        assertThat(view.targetType()).isEqualTo(CollectionTargetType.HOST);
        assertThat(view.targetId()).isEqualTo(7L);
        assertThat(view.observerType()).isEqualTo(HostInstanceCollectionService.OBSERVER_NONE);
        assertThat(view.source()).isEqualTo(CollectionSource.NONE.name());
        assertThat(view.result()).isEqualTo(CollectionResult.NONE.name());
        assertThat(view.freshness()).as("没成功观测过不等于过期").isEqualTo(CollectionFreshness.NONE.name());
        assertThat(view.configPresent()).isFalse();
        assertThat(view.lastAttemptAt()).isNull();
        assertThat(view.lastSuccessAt()).isNull();
        assertThat(view.observedAt()).isNull();
        assertThat(view.consecutiveFailureCount()).isZero();
        assertThat(view.errorCategory()).isNull();
        assertThat(view.errorSummary()).isNull();
        assertThat(view.observedStatus()).isNull();
        assertThat(view.observedHealth()).isNull();
        assertThat(view.discardedSampleCount()).isZero();
    }

    @Test
    @DisplayName("已采集目标：库里的字段逐个映射到视图，一个不漏")
    void observedStateIsMappedFieldByField() {
        MonitorCollectionState row = new MonitorCollectionState();
        row.setTargetType(CollectionTargetType.HOST);
        row.setTargetId(7L);
        row.setObserverType("SSH_PROCESS");
        row.setConfigPresent(Boolean.TRUE);
        row.setLastSource(CollectionSource.SSH_PROBE.name());
        row.setLastResult(CollectionResult.FAILED.name());
        row.setLastAttemptAt(T0.minusSeconds(5));
        row.setLastSuccessAt(T0.minusSeconds(30));
        row.setObservedAt(T0.minusSeconds(5));
        row.setConsecutiveFailureCount(2);
        row.setLastErrorCategory("NETWORK_TIMEOUT");
        row.setLastErrorSummary("connect timed out");
        row.setLastObservedStatus("ONLINE");
        row.setLastObservedHealth("UP");
        row.setDiscardedSampleCount(4);
        when(monitorCollectionStateMapper.selectByTargets(anyString(), any())).thenReturn(List.of(row));

        CollectionStateView view = service.listStates(CollectionTargetType.HOST, List.of(7L)).states().get(0);

        assertThat(view.observerType()).isEqualTo("SSH_PROCESS");
        assertThat(view.configPresent()).isTrue();
        assertThat(view.source()).isEqualTo(CollectionSource.SSH_PROBE.name());
        assertThat(view.result()).isEqualTo(CollectionResult.FAILED.name());
        assertThat(view.lastAttemptAt()).isEqualTo(T0.minusSeconds(5));
        assertThat(view.lastSuccessAt())
                .as("失败不清除最近成功时间，界面照样能看到上一次成功的时刻").isEqualTo(T0.minusSeconds(30));
        assertThat(view.observedAt()).isEqualTo(T0.minusSeconds(5));
        assertThat(view.consecutiveFailureCount()).isEqualTo(2);
        assertThat(view.errorCategory()).isEqualTo("NETWORK_TIMEOUT");
        assertThat(view.errorSummary()).isEqualTo("connect timed out");
        assertThat(view.observedStatus()).isEqualTo("ONLINE");
        assertThat(view.observedHealth()).isEqualTo("UP");
        assertThat(view.discardedSampleCount()).isEqualTo(4);
    }

    @Test
    @DisplayName("config_present 为 null 的旧行按'无配置'处理，不按 null 透传")
    void nullConfigPresentIsTreatedAsAbsent() {
        MonitorCollectionState row = new MonitorCollectionState();
        row.setTargetId(7L);
        row.setConfigPresent(null);
        when(monitorCollectionStateMapper.selectByTargets(anyString(), any())).thenReturn(List.of(row));

        assertThat(service.listStates(CollectionTargetType.HOST, List.of(7L)).states().get(0).configPresent())
                .isFalse();
    }

    // ---------- 新鲜度 ----------

    @Test
    @DisplayName("在过期阈值内：新鲜")
    void recentSuccessIsFresh() {
        when(monitorCollectionStateMapper.selectByTargets(anyString(), any()))
                .thenReturn(List.of(stateWithLastSuccess(T0.minusSeconds(30))));

        assertThat(freshnessOf(1L)).isEqualTo(CollectionFreshness.FRESH.name());
    }

    @Test
    @DisplayName("恰好等于过期阈值：仍算新鲜（边界闭合，避免临界抖动乱报过期）")
    void exactlyAtThresholdIsStillFresh() {
        when(monitorCollectionStateMapper.selectByTargets(anyString(), any()))
                .thenReturn(List.of(stateWithLastSuccess(T0.minus(Duration.ofMillis(STALE_MS)))));

        assertThat(freshnessOf(1L)).isEqualTo(CollectionFreshness.FRESH.name());
    }

    @Test
    @DisplayName("超过过期阈值：过期")
    void successBeyondThresholdIsStale() {
        when(monitorCollectionStateMapper.selectByTargets(anyString(), any()))
                .thenReturn(List.of(stateWithLastSuccess(T0.minus(Duration.ofMillis(STALE_MS + 1)))));

        assertThat(freshnessOf(1L)).isEqualTo(CollectionFreshness.STALE.name());
    }

    @Test
    @DisplayName("从未成功观测：新鲜度为 NONE，不是 STALE")
    void neverSucceededIsNoneNotStale() {
        MonitorCollectionState row = new MonitorCollectionState();
        row.setTargetId(1L);
        row.setLastSuccessAt(null);
        row.setLastResult(CollectionResult.FAILED.name());
        when(monitorCollectionStateMapper.selectByTargets(anyString(), any())).thenReturn(List.of(row));

        assertThat(freshnessOf(1L))
                .as("'从没成功过'与'曾经成功但已经过期'是两件事").isEqualTo(CollectionFreshness.NONE.name());
    }

    @Test
    @DisplayName("过期阈值取自配置：改配置后读模型立刻汇报新阈值")
    void thresholdIsReportedFromProperties() {
        properties.setStaleThresholdMs(120_000L);
        when(monitorCollectionStateMapper.selectByTargets(anyString(), any()))
                .thenReturn(List.of(stateWithLastSuccess(T0.minus(Duration.ofMillis(100_000)))));

        CollectionStateListResponse response = service.listStates(CollectionTargetType.HOST, List.of(1L));

        assertThat(response.staleThresholdMs()).isEqualTo(120_000L);
        assertThat(response.states().get(0).freshness())
                .as("同一条记录在 90s 阈值下过期、在 120s 阈值下新鲜，说明阈值真的生效")
                .isEqualTo(CollectionFreshness.FRESH.name());
    }

    // ---------- 顺序与去重 ----------

    @Test
    @DisplayName("目标 id 去重并去掉 null 后再查库，视图顺序跟随请求顺序")
    void idsAreDeduplicatedAndOrderFollowsRequest() {
        MonitorCollectionState row = new MonitorCollectionState();
        row.setTargetId(2L);
        when(monitorCollectionStateMapper.selectByTargets(anyString(), any())).thenReturn(List.of(row));

        CollectionStateListResponse response =
                service.listStates(CollectionTargetType.HOST, Arrays.asList(2L, 1L, 2L, null));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<Long>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(monitorCollectionStateMapper).selectByTargets(anyString(), captor.capture());
        assertThat(captor.getValue()).as("查库前先去掉 null 与重复").containsExactly(2L, 1L);

        assertThat(response.states()).hasSize(2);
        assertThat(response.states().stream().map(CollectionStateView::targetId))
                .as("库返回顺序与请求顺序无关，视图顺序必须跟随请求").containsExactly(2L, 1L);
    }

    @Test
    @DisplayName("查库之外不碰任何别的协作方：读模型只读数据库")
    void queryServiceTouchesNothingButTheMapper() {
        service.listStates(CollectionTargetType.HOST, List.of(1L));

        verify(monitorCollectionStateMapper).selectByTargets(anyString(), any());
        verifyNoMoreInteractions(monitorCollectionStateMapper);
    }

    // ---------- 采集开关 ----------

    @Test
    @DisplayName("采集启用状态要两个开关同时为真，且与是否传了目标 id 无关")
    void collectionEnabledRequiresBothSwitches() {
        assertThat(service.isCollectionEnabled())
                .as("两个开关都关着，界面必须能告诉用户这些数字不会自己更新").isFalse();
        assertThat(service.listStates(CollectionTargetType.HOST, List.of()).collectionEnabled()).isFalse();

        control = new HostInstanceCollectionControlService(properties, true);
        service = new CollectionStateQueryService(monitorCollectionStateMapper, properties, FIXED_CLOCK, control);
        assertThat(service.isCollectionEnabled()).as("只有总开关还不够").isFalse();

        properties.setEnabled(true);
        assertThat(service.isCollectionEnabled()).isTrue();
        assertThat(service.listStates(CollectionTargetType.HOST, List.of()).collectionEnabled()).isTrue();
    }

    @Test
    @DisplayName("只开了本功能开关、没开总开关：仍算未启用")
    void hostInstanceSwitchAloneIsNotEnough() {
        properties.setEnabled(true);

        assertThat(service.isCollectionEnabled()).isFalse();
    }

    // ---------- fixtures ----------

    private String freshnessOf(Long targetId) {
        return service.listStates(CollectionTargetType.HOST, List.of(targetId)).states().get(0).freshness();
    }

    private static MonitorCollectionState stateWithLastSuccess(OffsetDateTime lastSuccessAt) {
        MonitorCollectionState row = new MonitorCollectionState();
        row.setTargetId(1L);
        row.setLastSuccessAt(lastSuccessAt);
        return row;
    }
}
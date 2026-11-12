package com.infrahub.release.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 采集配置的 fail-closed 校验。
 *
 * <p>这些断言存在的理由：写错的阈值不会报错，只会让"过期"判定永远不成立（stale 太小则天天误报过期，
 * 太大则界面长期显示新鲜），或者让单目标超时超过采集周期导致永远堆积。因此非法配置必须在启动时
 * 抛出并指名是哪个配置项，而不是静默回退默认值。
 */
class HostInstanceCollectionPropertiesTest {

    private static final String PREFIX = "采集配置不合法：";

    @Test
    @DisplayName("默认配置合法，且默认值就是契约里写的那组")
    void defaultsAreLegalAndMatchContract() {
        HostInstanceCollectionProperties properties = new HostInstanceCollectionProperties();

        assertThatCode(properties::validate).doesNotThrowAnyException();

        assertThat(properties.isEnabled()).as("默认关闭：开发运行不得自动对现场发起探测").isFalse();
        assertThat(properties.getPeriodMs()).isEqualTo(30_000L);
        assertThat(properties.getSingleTargetTimeoutMs()).isEqualTo(5_000L);
        assertThat(properties.getMaxConcurrency()).isEqualTo(4);
        assertThat(properties.getFailureThreshold()).isEqualTo(3);
        assertThat(properties.getStaleThresholdMs()).isEqualTo(90_000L);
    }

    @Test
    @DisplayName("采集周期小于 1 秒：拒绝")
    void periodBelowOneSecondIsRejected() {
        HostInstanceCollectionProperties properties = valid();
        properties.setPeriodMs(999L);

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith(PREFIX)
                .hasMessageContaining("period-ms");
    }

    @Test
    @DisplayName("单目标超时小于 1 毫秒：拒绝")
    void timeoutBelowOneMillisecondIsRejected() {
        HostInstanceCollectionProperties properties = valid();
        properties.setSingleTargetTimeoutMs(0L);

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith(PREFIX)
                .hasMessageContaining("single-target-timeout-ms");
    }

    @Test
    @DisplayName("单目标超时不小于采集周期：拒绝（否则每轮都来不及结束）")
    void timeoutNotBelowPeriodIsRejected() {
        HostInstanceCollectionProperties properties = valid();
        properties.setSingleTargetTimeoutMs(properties.getPeriodMs());

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith(PREFIX)
                .hasMessageContaining("必须小于 period-ms");
    }

    @Test
    @DisplayName("并发上限越界：拒绝")
    void maxConcurrencyOutOfRangeIsRejected() {
        HostInstanceCollectionProperties tooLow = valid();
        tooLow.setMaxConcurrency(0);
        assertThatThrownBy(tooLow::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max-concurrency");

        HostInstanceCollectionProperties tooHigh = valid();
        tooHigh.setMaxConcurrency(33);
        assertThatThrownBy(tooHigh::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max-concurrency");
    }

    @Test
    @DisplayName("并发上限边界值 1 与 32：接受")
    void maxConcurrencyBoundariesAreAccepted() {
        HostInstanceCollectionProperties lowest = valid();
        lowest.setMaxConcurrency(1);
        assertThatCode(lowest::validate).doesNotThrowAnyException();

        HostInstanceCollectionProperties highest = valid();
        highest.setMaxConcurrency(32);
        assertThatCode(highest::validate).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("连续失败阈值小于 1：拒绝（0 会让状态刚到第一次失败就被改写）")
    void failureThresholdBelowOneIsRejected() {
        HostInstanceCollectionProperties properties = valid();
        properties.setFailureThreshold(0);

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("failure-threshold");
    }

    @Test
    @DisplayName("过期阈值小于采集周期：拒绝（否则刚采到就算过期）")
    void staleThresholdBelowPeriodIsRejected() {
        HostInstanceCollectionProperties properties = valid();
        properties.setStaleThresholdMs(properties.getPeriodMs() - 1);

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stale-threshold-ms");
    }

    @Test
    @DisplayName("过期阈值等于采集周期：接受（边界闭合）")
    void staleThresholdEqualToPeriodIsAccepted() {
        HostInstanceCollectionProperties properties = valid();
        properties.setStaleThresholdMs(properties.getPeriodMs());

        assertThatCode(properties::validate).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("拒绝时把出错的值一并写进文案，运维不用猜")
    void rejectionMessageCarriesTheOffendingValue() {
        HostInstanceCollectionProperties properties = valid();
        properties.setMaxConcurrency(99);

        assertThatThrownBy(properties::validate).hasMessageContaining("99");
    }

    private static HostInstanceCollectionProperties valid() {
        return new HostInstanceCollectionProperties();
    }
}
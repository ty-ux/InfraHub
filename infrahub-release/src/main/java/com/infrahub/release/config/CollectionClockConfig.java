package com.infrahub.release.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 采集用的时钟。
 *
 * <p>显式注入而不是到处 {@code OffsetDateTime.now()}，是为了让"连续失败阈值""数据过期""乱序样本"
 * 三个时间相关判定能用假时钟精确验证（契约 §7.1），不必靠 sleep 去等真实时间。
 *
 * <p>用 {@code systemDefaultZone()} 而非 UTC，是为了与既有代码里 {@code OffsetDateTime.now()}
 * 写入的时间在同一时区基准上，避免新旧数据比较时出现偏移歧义。
 */
@Configuration
public class CollectionClockConfig {

    @Bean
    public Clock collectionClock() {
        return Clock.systemDefaultZone();
    }
}
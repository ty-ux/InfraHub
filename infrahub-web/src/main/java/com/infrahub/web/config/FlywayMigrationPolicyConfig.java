package com.infrahub.web.config;

import com.infrahub.web.migration.LegacyMissingMigrationGate;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 把迁移历史一致性闸门接到 Spring Boot 的 Flyway 启动流程上。
 *
 * <p>Spring Boot 的 {@code FlywayAutoConfiguration} 会在初始化时查找
 * {@link FlywayMigrationStrategy} bean，找到就用它替代默认的"直接 migrate"。
 * 因此闸门<b>不需要开发者记得执行任何命令</b>：只要应用启动，它先判定、后迁移。
 *
 * <p>失败时抛出异常，启动中止，数据库保持未改动——不会出现"先迁移再报错"的半程状态。
 * 这与 {@code ignore-migration-patterns} 的区别在于：那个配置只是让校验放过缺失，
 * 本闸门是主动拒绝，且拒绝范围收窄到已核实的历史身份。
 */
@Configuration(proxyBeanMethods = false)
public class FlywayMigrationPolicyConfig {

    @Bean
    public FlywayMigrationStrategy flywayMigrationStrategy() {
        return flyway -> {
            LegacyMissingMigrationGate.verify(flyway);
            flyway.migrate();
        };
    }
}
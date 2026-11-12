package com.infrahub.web;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

@SpringBootApplication(scanBasePackages = "com.infrahub")
@MapperScan({
        "com.infrahub.base.mapper",
        "com.infrahub.pipeline.mapper",
        "com.infrahub.release.mapper",
        "com.infrahub.system.mapper"
})
@EnableMethodSecurity
@EnableScheduling
public class InfrahubApplication {

    public static void main(String[] args) {
        SpringApplication.run(InfrahubApplication.class, args);
    }
}

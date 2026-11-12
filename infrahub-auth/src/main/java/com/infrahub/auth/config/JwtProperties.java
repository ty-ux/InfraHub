package com.infrahub.auth.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "infrahub.jwt")
public class JwtProperties {

    private String issuer;
    private String secret;
    private long expireSeconds;
}

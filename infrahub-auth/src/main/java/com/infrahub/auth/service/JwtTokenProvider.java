package com.infrahub.auth.service;

import com.infrahub.auth.config.JwtProperties;
import com.infrahub.common.context.UserContext;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Set;

@Component
public class JwtTokenProvider {

    private final JwtProperties jwtProperties;
    private final SecretKey secretKey;

    public JwtTokenProvider(JwtProperties jwtProperties) {
        this.jwtProperties = jwtProperties;
        byte[] secretBytes = jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8);
        this.secretKey = Keys.hmacShaKeyFor(secretBytes);
    }

    public String generateToken(UserContext userContext) {
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(jwtProperties.getExpireSeconds());
        return Jwts.builder()
                .issuer(jwtProperties.getIssuer())
                .subject(userContext.getUsername())
                .claim("userId", userContext.getUserId())
                .claim("displayName", userContext.getDisplayName())
                .claim("authorities", userContext.getAuthorities())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(secretKey)
                .compact();
    }

    public UserContext parseToken(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();

        @SuppressWarnings("unchecked")
        List<String> authorities = claims.get("authorities", List.class);
        return UserContext.builder()
                .userId(((Number) claims.get("userId")).longValue())
                .username(claims.getSubject())
                .displayName(claims.get("displayName", String.class))
                .authorities(Set.copyOf(authorities))
                .build();
    }

    public long getExpireSeconds() {
        return jwtProperties.getExpireSeconds();
    }
}

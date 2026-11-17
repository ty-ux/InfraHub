package com.infrahub.auth.service;

import com.infrahub.auth.config.JwtProperties;
import com.infrahub.common.context.UserContext;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

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
                .claim("credentialVersion", userContext.getCredentialVersion())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(secretKey)
                .compact();
    }

    public Identity parseToken(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(secretKey)
                .requireIssuer(jwtProperties.getIssuer())
                .build()
                .parseSignedClaims(token)
                .getPayload();

        Object userId = claims.get("userId");
        Integer credentialVersion = claims.get("credentialVersion", Integer.class);
        if (!(userId instanceof Number id)) {
            throw new JwtException("Missing user ID");
        }
        return new Identity(id.longValue(), claims.getSubject(), credentialVersion == null ? 0 : credentialVersion);
    }

    public long getExpireSeconds() {
        return jwtProperties.getExpireSeconds();
    }

    public record Identity(long userId, String username, int credentialVersion) {
    }
}

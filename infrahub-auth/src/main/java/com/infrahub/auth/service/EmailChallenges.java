package com.infrahub.auth.service;

import com.infrahub.auth.config.JwtProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Locale;

@Service
public class EmailChallenges {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final SecretKeySpec key;

    public EmailChallenges(JdbcTemplate jdbc, TransactionTemplate transactions, JwtProperties jwt) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.key = new SecretKeySpec(jwt.getSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    public boolean allow(String bucket, int limit, int seconds) {
        return !jdbc.query("""
                INSERT INTO sys_auth_rate_limit (bucket, hits, expires_at)
                VALUES (?, 1, CURRENT_TIMESTAMP + (? * INTERVAL '1 second'))
                ON CONFLICT (bucket) DO UPDATE SET
                    hits = CASE WHEN sys_auth_rate_limit.expires_at <= CURRENT_TIMESTAMP THEN 1
                                ELSE sys_auth_rate_limit.hits + 1 END,
                    expires_at = CASE WHEN sys_auth_rate_limit.expires_at <= CURRENT_TIMESTAMP THEN EXCLUDED.expires_at
                                      ELSE sys_auth_rate_limit.expires_at END
                WHERE sys_auth_rate_limit.expires_at <= CURRENT_TIMESTAMP OR sys_auth_rate_limit.hits < ?
                RETURNING hits
                """, (row, index) -> row.getInt(1), bucket, seconds, limit).isEmpty();
    }

    public String issue(String purpose, String target) {
        String code = String.format(Locale.ROOT, "%06d", RANDOM.nextInt(1_000_000));
        jdbc.update("""
                INSERT INTO sys_auth_email_code (purpose, target, code_hash, expires_at)
                VALUES (?, ?, ?, CURRENT_TIMESTAMP + INTERVAL '10 minutes')
                ON CONFLICT (purpose, target) DO UPDATE SET code_hash = EXCLUDED.code_hash,
                    expires_at = EXCLUDED.expires_at, attempts = 0, consumed_at = NULL
                """, purpose, target, digest(purpose + ":" + target + ":" + code));
        return code;
    }

    public boolean consume(String purpose, String target, String code) {
        String actual = digest(purpose + ":" + target + ":" + code);
        return Boolean.TRUE.equals(transactions.execute(status -> {
            var hashes = jdbc.queryForList("""
                    SELECT code_hash FROM sys_auth_email_code WHERE purpose = ? AND target = ?
                    AND expires_at > CURRENT_TIMESTAMP AND consumed_at IS NULL AND attempts < 5 FOR UPDATE
                    """, String.class, purpose, target);
            if (hashes.isEmpty()) return false;
            if (MessageDigest.isEqual(hashes.get(0).getBytes(StandardCharsets.US_ASCII), actual.getBytes(StandardCharsets.US_ASCII))) {
                jdbc.update("UPDATE sys_auth_email_code SET consumed_at = CURRENT_TIMESTAMP WHERE purpose = ? AND target = ?", purpose, target);
                return true;
            }
            jdbc.update("UPDATE sys_auth_email_code SET attempts = attempts + 1 WHERE purpose = ? AND target = ?", purpose, target);
            return false;
        }));
    }

    public String digest(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Could not initialize email authentication", exception);
        }
    }

    @Scheduled(fixedDelay = 3600000, initialDelay = 3600000)
    public void deleteExpiredChallenges() {
        jdbc.update("DELETE FROM sys_auth_email_code WHERE expires_at < CURRENT_TIMESTAMP");
        jdbc.update("DELETE FROM sys_auth_rate_limit WHERE expires_at < CURRENT_TIMESTAMP");
        jdbc.update("DELETE FROM sys_auth_invitation WHERE expires_at < CURRENT_TIMESTAMP");
    }
}

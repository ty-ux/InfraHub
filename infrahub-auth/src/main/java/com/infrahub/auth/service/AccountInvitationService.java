package com.infrahub.auth.service;

import com.infrahub.auth.dto.EmailAuthRequests;
import com.infrahub.common.context.UserContextHolder;
import com.infrahub.common.error.ErrorCode;
import com.infrahub.common.exception.BizException;
import com.infrahub.system.service.SysAccessService;
import com.infrahub.system.service.SysUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashSet;

@Service
@RequiredArgsConstructor
public class AccountInvitationService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final EmailChallenges challenges;
    private final SysAccessService access;
    private final SysUserService users;
    private final AccountMail mail;

    public void invite(EmailAuthRequests.Invite request) {
        if (!mail.invitationsEnabled()) throw invalid("请先配置发件服务和控制台地址");
        String email = EmailAccountService.validatedAddress(request.email());
        if (!challenges.allow("invite-admin:" + UserContextHolder.getUserIdOrDefault(null), 30, 3600)
                || !challenges.allow("invite-email:" + email, 1, 60)) throw invalid("请求过于频繁，请稍后再试");
        byte[] secret = new byte[32];
        RANDOM.nextBytes(secret);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        transactions.executeWithoutResult(status -> {
            access.validateEnabledRoles(request.roleIds());
            jdbc.update("""
                    INSERT INTO sys_auth_invitation (email, token_hash, expires_at)
                    VALUES (?, ?, CURRENT_TIMESTAMP + INTERVAL '24 hours')
                    ON CONFLICT (email) DO UPDATE SET token_hash = EXCLUDED.token_hash,
                        expires_at = EXCLUDED.expires_at, used_at = NULL
                    """, email, tokenHash(token));
            if (users.getByVerifiedEmail(email) != null || users.getByUsername(email) != null) throw invalid("该邮箱已被账号使用");
            jdbc.update("DELETE FROM sys_auth_invitation_role WHERE email = ?", email);
            request.roleIds().forEach(id -> jdbc.update("INSERT INTO sys_auth_invitation_role (email, role_id) VALUES (?, ?)", email, id));
            mail.sendInvitation(email, token);
        });
    }

    public void register(EmailAuthRequests.Register request, String ip) {
        if (!challenges.allow("register-ip:" + ip, 30, 600)) throw invalid("请求过于频繁，请稍后再试");
        transactions.executeWithoutResult(status -> {
            var addresses = jdbc.queryForList("""
                    SELECT email FROM sys_auth_invitation WHERE token_hash = ?
                    AND used_at IS NULL AND expires_at > CURRENT_TIMESTAMP FOR UPDATE
                    """, String.class, tokenHash(request.token()));
            if (addresses.isEmpty()) throw invalid("邀请无效、已使用或已过期");
            String email = addresses.get(0);
            var roles = new HashSet<>(jdbc.queryForList("SELECT role_id FROM sys_auth_invitation_role WHERE email = ?", Long.class, email));
            access.createLocalUser(request.username(), request.displayName(), request.password(), roles, email);
            jdbc.update("UPDATE sys_auth_invitation SET used_at = CURRENT_TIMESTAMP WHERE email = ?", email);
        });
    }

    private String tokenHash(String token) {
        return challenges.digest("invitation:" + token);
    }

    private static BizException invalid(String message) {
        return new BizException(ErrorCode.VALIDATION_ERROR, message);
    }
}

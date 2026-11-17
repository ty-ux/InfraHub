package com.infrahub.auth.service;

import com.infrahub.auth.dto.AuthTokenResponse;
import com.infrahub.auth.dto.EmailAuthRequests;
import com.infrahub.common.context.UserContextHolder;
import com.infrahub.common.error.ErrorCode;
import com.infrahub.common.exception.BizException;
import com.infrahub.system.entity.SysUser;
import com.infrahub.system.service.SysUserService;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class EmailAccountService {
    private final AuthService auth;
    private final SysUserService users;
    private final EmailChallenges challenges;
    private final AccountMail mail;

    public Settings settings() {
        var user = users.getById(UserContextHolder.getUserIdOrDefault(null));
        if (user == null) throw new BizException(ErrorCode.UNAUTHORIZED);
        return new Settings(user.getEmail(), user.getEmailVerifiedAt() != null, mail.enabled());
    }

    public void requestLoginCode(String address, String ip) {
        mail.requireEnabled();
        String email = validatedAddress(address);
        if (!challenges.allow("login-send-ip:" + ip, 30, 600)
                || !challenges.allow("login-send-email:" + email, 1, 60)
                || !challenges.allow("login-send-hour:" + email, 5, 3600)) return;
        var user = users.getByVerifiedEmail(email);
        String code = canUseCode(user) ? challenges.issue("LOGIN", target(user, email)) : null;
        mail.sendLoginCodeLater(email, code);
    }

    public AuthTokenResponse loginWithCode(EmailAuthRequests.Code request, String ip) {
        mail.requireEnabled();
        String email = validatedAddress(request.email());
        if (!challenges.allow("login-check-ip:" + ip, 60, 600)
                || !challenges.allow("login-check-email:" + email, 15, 600)) throw invalidCode();
        var user = users.getByVerifiedEmail(email);
        if (!canUseCode(user) || !challenges.consume("LOGIN", target(user, email), request.code())) throw invalidCode();
        return auth.completeLogin(user);
    }

    public void requestBinding(EmailAuthRequests.BindRequest request) {
        mail.requireEnabled();
        requireBudget("bind-send", 5, 3600);
        var user = auth.verifyCurrentPassword(request.password());
        String email = validatedAddress(request.email());
        ensureAvailable(email, user);
        if (!challenges.allow("bind-address:" + email, 1, 60)) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "请等待 60 秒后再发送验证码");
        }
        mail.sendCode(email, challenges.issue("BIND", target(user, email)));
    }

    public AuthTokenResponse confirmBinding(EmailAuthRequests.BindConfirm request) {
        mail.requireEnabled();
        requireBudget("bind-check", 20, 600);
        var user = auth.verifyCurrentPassword(request.password());
        String email = validatedAddress(request.email());
        ensureAvailable(email, user);
        if (!challenges.consume("BIND", target(user, email), request.code())) throw invalidCode();
        int nextVersion = user.getCredentialVersion() + 1;
        OffsetDateTime now = OffsetDateTime.now();
        try {
            boolean updated = users.lambdaUpdate().eq(SysUser::getId, user.getId())
                    .eq(SysUser::getStatus, "ENABLED").eq(SysUser::getCredentialVersion, user.getCredentialVersion())
                    .set(SysUser::getEmail, email).set(SysUser::getEmailVerifiedAt, now)
                    .set(SysUser::getUpdatedAt, now).set(SysUser::getCredentialVersion, nextVersion).update();
            if (!updated) throw new BizException(ErrorCode.UNAUTHORIZED);
        } catch (DuplicateKeyException exception) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "该邮箱无法绑定");
        }
        if (user.getEmailVerifiedAt() != null) mail.notifyPreviousAddress(user.getEmail());
        user.setCredentialVersion(nextVersion);
        return auth.issueToken(user);
    }

    private void ensureAvailable(String email, SysUser user) {
        var usernameOwner = users.getByUsername(email);
        if (users.getByVerifiedEmail(email) != null || (usernameOwner != null && !usernameOwner.getId().equals(user.getId()))) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "该邮箱无法绑定");
        }
    }

    private void requireBudget(String action, int limit, int seconds) {
        if (!challenges.allow(action + ":" + UserContextHolder.getUserIdOrDefault(null), limit, seconds)) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "请求过于频繁，请稍后再试");
        }
    }

    static String normalize(String address) {
        // Preserve SMTP local-part casing and normalize only the domain.
        int separator = address.lastIndexOf('@') + 1;
        return address.substring(0, separator) + address.substring(separator).toLowerCase(Locale.ROOT);
    }

    static String validatedAddress(String address) {
        try {
            var parsed = InternetAddress.parse(address, true);
            if (parsed.length == 1 && !parsed[0].isGroup() && address.equals(parsed[0].getAddress())
                    && address.chars().noneMatch(Character::isISOControl)) {
                parsed[0].validate();
                return normalize(address);
            }
        } catch (AddressException ignored) {
            // Return the same validation error for all invalid mailbox forms.
        }
        throw new BizException(ErrorCode.VALIDATION_ERROR, "邮箱地址不合法");
    }

    private static String target(SysUser user, String email) {
        return user.getId() + ":" + user.getCredentialVersion() + ":" + email;
    }

    private static boolean canUseCode(SysUser user) {
        return user != null && "ENABLED".equals(user.getStatus()) && "LOCAL".equalsIgnoreCase(user.getAuthType())
                && !user.isMustChangePassword();
    }

    private static BizException invalidCode() {
        return new BizException(ErrorCode.VALIDATION_ERROR, "验证码无效或已过期");
    }

    public record Settings(String email, boolean verified, boolean enabled) {
    }
}

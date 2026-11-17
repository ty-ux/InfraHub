package com.infrahub.auth.service;

import com.infrahub.auth.dto.AuthLoginRequest;
import com.infrahub.auth.dto.AuthTokenResponse;
import com.infrahub.auth.dto.ChangePasswordRequest;
import com.infrahub.auth.dto.CurrentUserResponse;
import com.infrahub.common.context.UserContext;
import com.infrahub.common.context.UserContextHolder;
import com.infrahub.common.error.ErrorCode;
import com.infrahub.common.exception.BizException;
import com.infrahub.system.entity.SysUser;
import com.infrahub.system.service.LocalPasswordPolicy;
import com.infrahub.system.service.SysUserService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.OffsetDateTime;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Service
public class AuthService {
    private static final String BOOTSTRAP_MARKER = "CHANGE_ME_HASH";

    private final SysUserService sysUserService;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final String bootstrapPassword;
    private final String dummyHash;

    public AuthService(SysUserService sysUserService, PasswordEncoder passwordEncoder, JwtTokenProvider jwtTokenProvider,
                       @Value("${infrahub.auth.bootstrap-admin-password:}") String bootstrapPassword) {
        this.sysUserService = sysUserService;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
        this.bootstrapPassword = bootstrapPassword;
        this.dummyHash = passwordEncoder.encode(java.util.UUID.randomUUID().toString());
    }

    public AuthTokenResponse login(AuthLoginRequest request) {
        SysUser sysUser = sysUserService.getByUsername(request.getUsername());
        boolean local = sysUser != null && "ENABLED".equals(sysUser.getStatus()) && "LOCAL".equalsIgnoreCase(sysUser.getAuthType());
        String stored = local ? sysUser.getPasswordHash() : null;
        String password = request.getPassword();
        if (BOOTSTRAP_MARKER.equals(stored) && "admin".equals(sysUser.getUsername())) {
            matchesPassword(password, dummyHash);
            if (!validBootstrapPassword(password) || !replacePassword(sysUser, password, true)) {
                throw new BizException(ErrorCode.PASSWORD_MISMATCH);
            }
        } else {
            boolean matched = matchesPassword(password, StringUtils.hasText(stored) ? stored : dummyHash);
            if (!local || !StringUtils.hasText(stored) || !matched) {
                throw new BizException(ErrorCode.PASSWORD_MISMATCH);
            }
        }
        boolean updated = sysUserService.lambdaUpdate().eq(SysUser::getId, sysUser.getId())
                .eq(SysUser::getStatus, "ENABLED")
                .eq(SysUser::getCredentialVersion, sysUser.getCredentialVersion())
                .set(SysUser::getLastLoginAt, OffsetDateTime.now()).update();
        if (!updated) throw new BizException(ErrorCode.PASSWORD_MISMATCH);
        return issueToken(sysUser);
    }

    public AuthTokenResponse changePassword(ChangePasswordRequest request) {
        UserContext context = UserContextHolder.get();
        SysUser user = sysUserService.getById(context.getUserId());
        if (user == null || !"ENABLED".equals(user.getStatus()) || user.getCredentialVersion() != context.getCredentialVersion()) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        if (!"LOCAL".equalsIgnoreCase(user.getAuthType())) {
            throw new BizException(ErrorCode.FORBIDDEN, "该账号未启用本地密码");
        }
        if (!matchesPassword(request.currentPassword(), user.getPasswordHash())) {
            throw new BizException(ErrorCode.PASSWORD_MISMATCH, "当前密码不正确");
        }
        LocalPasswordPolicy.validate(request.newPassword());
        if (request.currentPassword().equals(request.newPassword())) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "新密码必须与当前密码不同");
        }
        if (!replacePassword(user, request.newPassword(), false)) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        return issueToken(user);
    }

    private boolean replacePassword(SysUser user, String password, boolean mustChange) {
        String hash = passwordEncoder.encode(password);
        int nextVersion = user.getCredentialVersion() + 1;
        boolean updated = sysUserService.lambdaUpdate().eq(SysUser::getId, user.getId())
                .eq(SysUser::getStatus, "ENABLED")
                .eq(SysUser::getCredentialVersion, user.getCredentialVersion())
                .eq(SysUser::getPasswordHash, user.getPasswordHash())
                .set(SysUser::getPasswordHash, hash).set(SysUser::isMustChangePassword, mustChange)
                .set(SysUser::getCredentialVersion, nextVersion).set(SysUser::getUpdatedAt, OffsetDateTime.now()).update();
        if (updated) {
            user.setPasswordHash(hash);
            user.setMustChangePassword(mustChange);
            user.setCredentialVersion(nextVersion);
        }
        return updated;
    }

    private AuthTokenResponse issueToken(SysUser user) {
        String token = jwtTokenProvider.generateToken(UserContext.builder().userId(user.getId()).username(user.getUsername())
                .credentialVersion(user.getCredentialVersion()).build());
        return AuthTokenResponse.builder()
                .accessToken(token)
                .tokenType("Bearer")
                .expireSeconds(jwtTokenProvider.getExpireSeconds())
                .build();
    }

    public CurrentUserResponse currentUser() {
        UserContext userContext = UserContextHolder.get();
        if (userContext == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        return CurrentUserResponse.builder()
                .userId(userContext.getUserId())
                .username(userContext.getUsername())
                .displayName(userContext.getDisplayName())
                .mustChangePassword(userContext.isMustChangePassword())
                .authorities(userContext.getAuthorities())
                .allAppPermissions(userContext.getAllAppPermissions())
                .applicationPermissions(userContext.getApplicationPermissions())
                .build();
    }

    private boolean matchesPassword(String rawPassword, String storedHash) {
        return StringUtils.hasText(storedHash) && !BOOTSTRAP_MARKER.equals(storedHash)
                && LocalPasswordPolicy.fitsBcrypt(rawPassword) && passwordEncoder.matches(rawPassword, storedHash);
    }

    private boolean validBootstrapPassword(String password) {
        return LocalPasswordPolicy.isValid(bootstrapPassword)
                && MessageDigest.isEqual(bootstrapPassword.getBytes(StandardCharsets.UTF_8), password.getBytes(StandardCharsets.UTF_8));
    }
}

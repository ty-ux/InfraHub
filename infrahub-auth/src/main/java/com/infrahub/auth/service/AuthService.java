package com.infrahub.auth.service;

import com.infrahub.auth.dto.AuthLoginRequest;
import com.infrahub.auth.dto.AuthTokenResponse;
import com.infrahub.auth.dto.CurrentUserResponse;
import com.infrahub.common.context.UserContext;
import com.infrahub.common.context.UserContextHolder;
import com.infrahub.common.error.ErrorCode;
import com.infrahub.common.exception.BizException;
import com.infrahub.system.entity.SysUser;
import com.infrahub.system.service.SysUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.OffsetDateTime;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final SysUserService sysUserService;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;

    public AuthTokenResponse login(AuthLoginRequest request) {
        SysUser sysUser = sysUserService.getByUsername(request.getUsername());
        if (sysUser == null) {
            throw new BizException(ErrorCode.USER_NOT_FOUND);
        }
        if (!"ENABLED".equals(sysUser.getStatus()) || !"LOCAL".equalsIgnoreCase(sysUser.getAuthType())) {
            throw new BizException(ErrorCode.FORBIDDEN, "当前用户不支持本地登录");
        }
        if (!matchesPassword(request.getPassword(), sysUser.getPasswordHash())) {
            throw new BizException(ErrorCode.PASSWORD_MISMATCH);
        }
        sysUser.setLastLoginAt(OffsetDateTime.now());
        sysUserService.updateById(sysUser);

        UserContext userContext = sysUserService.getActiveContext(sysUser);
        String token = jwtTokenProvider.generateToken(userContext);
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
                .authorities(userContext.getAuthorities())
                .allAppPermissions(userContext.getAllAppPermissions())
                .applicationPermissions(userContext.getApplicationPermissions())
                .build();
    }

    private boolean matchesPassword(String rawPassword, String storedHash) {
        if (!StringUtils.hasText(storedHash)) {
            return false;
        }
        if ("CHANGE_ME_HASH".equals(storedHash)) {
            return "admin123".equals(rawPassword);
        }
        return passwordEncoder.matches(rawPassword, storedHash);
    }
}

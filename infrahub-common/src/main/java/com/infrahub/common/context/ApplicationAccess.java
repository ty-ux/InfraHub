package com.infrahub.common.context;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.infrahub.common.error.ErrorCode;
import com.infrahub.common.exception.BizException;

import java.util.HashSet;
import java.util.Set;

public final class ApplicationAccess {
    public static final String APP_VIEW = "APP_VIEW";
    public static final String PIPELINE_VIEW = "PIPELINE_VIEW";
    public static final String PIPELINE_EXECUTE = "PIPELINE_EXECUTE";
    public static final String PIPELINE_DEPLOY = "PIPELINE_DEPLOY";
    public static final Set<String> SCOPED_PERMISSIONS = Set.of(APP_VIEW, PIPELINE_VIEW, PIPELINE_EXECUTE, PIPELINE_DEPLOY);

    private ApplicationAccess() {
    }

    public static boolean isAdmin() {
        var user = UserContextHolder.get();
        return user != null && user.getAuthorities().contains("SYSTEM_ADMIN");
    }

    public static Set<Long> allowedApps(String... permissions) {
        var user = UserContextHolder.get();
        if (user == null) return Set.of();
        if (isAdmin()) return null;
        var allowed = new HashSet<Long>();
        for (String permission : permissions) {
            if (!user.getAuthorities().contains(permission)) continue;
            if (user.getAllAppPermissions().contains(permission)) return null;
            allowed.addAll(user.getApplicationPermissions().getOrDefault(permission, Set.of()));
        }
        return allowed;
    }

    public static void require(Long appId, String... permissions) {
        if (!permits(appId, permissions)) {
            throw new BizException(ErrorCode.FORBIDDEN, "当前账号无权访问该应用");
        }
    }

    public static boolean permits(Long appId, String... permissions) {
        Set<Long> allowed = allowedApps(permissions);
        return allowed == null || (appId != null && allowed.contains(appId));
    }

    public static <T> void restrict(LambdaQueryWrapper<T> query, SFunction<T, Long> appId, Set<Long> allowed) {
        if (allowed == null) return;
        if (allowed.isEmpty()) {
            query.apply("1 = 0");
        } else {
            query.in(appId, allowed);
        }
    }
}

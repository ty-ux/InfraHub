package com.infrahub.common.context;

public final class UserContextHolder {

    private static final ThreadLocal<UserContext> HOLDER = new ThreadLocal<>();

    private UserContextHolder() {
    }

    public static void set(UserContext userContext) {
        HOLDER.set(userContext);
    }

    public static UserContext get() {
        return HOLDER.get();
    }

    public static Long getUserIdOrDefault(Long defaultUserId) {
        UserContext userContext = HOLDER.get();
        if (userContext == null || userContext.getUserId() == null) {
            return defaultUserId;
        }
        return userContext.getUserId();
    }

    public static void clear() {
        HOLDER.remove();
    }
}

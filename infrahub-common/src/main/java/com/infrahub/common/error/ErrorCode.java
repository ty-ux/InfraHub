package com.infrahub.common.error;

import lombok.Getter;

@Getter
public enum ErrorCode {
    SUCCESS("SUCCESS", "success"),
    UNAUTHORIZED("AUTH_401", "未登录或登录已过期"),
    FORBIDDEN("AUTH_403", "无权限访问"),
    VALIDATION_ERROR("COMMON_400", "请求参数不合法"),
    USER_NOT_FOUND("AUTH_404", "用户不存在"),
    PASSWORD_MISMATCH("AUTH_4001", "用户名或密码错误"),
    INTERNAL_ERROR("COMMON_500", "系统内部错误");

    private final String code;
    private final String message;

    ErrorCode(String code, String message) {
        this.code = code;
        this.message = message;
    }
}

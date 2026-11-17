package com.infrahub.system.service;

import com.infrahub.common.error.ErrorCode;
import com.infrahub.common.exception.BizException;

import java.nio.charset.StandardCharsets;

public final class LocalPasswordPolicy {
    private LocalPasswordPolicy() {
    }

    public static boolean fitsBcrypt(String password) {
        return password.getBytes(StandardCharsets.UTF_8).length <= 72;
    }

    public static void validate(String password) {
        if (!isValid(password)) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "密码至少 15 个字符，且 UTF-8 编码最多 72 字节");
        }
    }

    public static boolean isValid(String password) {
        return !password.isBlank() && password.codePointCount(0, password.length()) >= 15 && fitsBcrypt(password);
    }
}

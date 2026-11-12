package com.infrahub.common.exception;

import com.infrahub.common.error.ErrorCode;
import lombok.Getter;

@Getter
public class BizException extends RuntimeException {

    private final ErrorCode errorCode;

    public BizException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public BizException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    /**
     * 带原始异常的业务异常：用于把“面向用户的安全摘要”与“服务端保留的原始异常”分开。
     * 全局异常处理器只返回 {@code message}，原始异常仅留在日志与堆栈里。
     */
    public BizException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }
}

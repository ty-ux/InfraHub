package com.infrahub.common.exception;

import com.infrahub.common.error.ErrorCode;

public class UnauthorizedException extends BizException {

    public UnauthorizedException() {
        super(ErrorCode.UNAUTHORIZED);
    }
}

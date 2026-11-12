package com.infrahub.common.exception;

import com.infrahub.common.error.ErrorCode;

public class ForbiddenException extends BizException {

    public ForbiddenException() {
        super(ErrorCode.FORBIDDEN);
    }
}

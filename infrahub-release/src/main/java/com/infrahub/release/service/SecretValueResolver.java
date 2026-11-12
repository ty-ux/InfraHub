package com.infrahub.release.service;

import com.infrahub.base.entity.ConfigSecretRef;
import com.infrahub.base.service.ConfigSecretRefService;
import com.infrahub.common.error.ErrorCode;
import com.infrahub.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SecretValueResolver {

    private final ConfigSecretRefService configSecretRefService;

    public String resolve(Long secretRefId) {
        if (secretRefId == null) {
            return null;
        }
        ConfigSecretRef secretRef = configSecretRefService.getById(secretRefId);
        if (secretRef == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "密钥引用不存在");
        }
        return secretRef.getSecretPath();
    }
}

package com.infrahub.release.validation;

import com.infrahub.release.support.IpLiterals;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** {@link IpLiteral} 的判定实现；null 与空串留给 {@code @NotBlank} 报错。 */
public class IpLiteralValidator implements ConstraintValidator<IpLiteral, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isEmpty()) {
            return true;
        }
        return IpLiterals.isIpLiteral(value);
    }
}
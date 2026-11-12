package com.infrahub.release.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 字段必须是 IPv4 或 IPv6 字面量。
 *
 * <p>判定交给 {@link com.infrahub.release.support.IpLiterals}，与 service 入库前的检查同一实现。
 * 空值视为通过，由 {@code @NotBlank} 一类的注解负责。
 */
@Documented
@Constraint(validatedBy = IpLiteralValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface IpLiteral {

    String message() default "IP 地址格式不正确，仅支持 IPv4 或 IPv6 字面量";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
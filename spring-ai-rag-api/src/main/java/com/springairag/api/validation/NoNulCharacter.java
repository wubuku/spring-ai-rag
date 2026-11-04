package com.springairag.api.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 拒绝包含 NUL（U+0000）的文本。
 *
 * <p>PostgreSQL 的 text/jsonb 列不能保存 NUL 字符；如果让这类输入进入持久化层，
 * 会以数据库异常的形式变成 500，并可能污染日志与导出。该约束把非法输入在
 * API 边界转换为明确的 400。</p>
 */
@Documented
@Constraint(validatedBy = NoNulCharacterValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface NoNulCharacter {

    String message() default "Text must not contain NUL (U+0000) characters";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}

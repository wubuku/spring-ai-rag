package com.springairag.api.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 拒绝 JSON 树（Map / JsonNode / Iterable / 数组）中任意层级的 NUL（U+0000）。
 *
 * <p>structurally 传入的 metadata / jsonbPayload 最终会写入 PostgreSQL
 * {@code jsonb}；jsonb 不接受 NUL，若不在 API 边界拒绝就会变成 500。
 * 深度超过 256 层时 fail-closed。</p>
 */
@Documented
@Constraint(validatedBy = NoNulInJsonTreeValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface NoNulInJsonTree {

    String message() default "JSON payload must not contain NUL (U+0000) characters";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}

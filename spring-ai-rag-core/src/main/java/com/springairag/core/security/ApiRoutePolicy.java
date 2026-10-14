package com.springairag.core.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明 Controller route 的 API capability 语义。
 *
 * <p>类级声明覆盖该 Controller 的普通方法，方法级声明只覆盖当前 handler。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface ApiRoutePolicy {

    Access access() default Access.METHOD_DEFAULT;

    enum Access {
        METHOD_DEFAULT,
        READ,
        WRITE,
        MANAGEMENT
    }
}

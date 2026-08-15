package com.springairag.api.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Validates an external Collection business key.
 *
 * <p>Null is accepted by the constraint itself so callers can use it on
 * optional compatibility fields. Create/import/clone commands must enforce
 * requiredness separately.
 */
@Documented
@Constraint(validatedBy = CollectionKeyValidator.class)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER,
        ElementType.ANNOTATION_TYPE, ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidCollectionKey {

    String message() default "Collection key must contain 1-128 visible ASCII characters";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}

package com.springairag.api.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Validates an external source identity namespace.
 *
 * <p>Null and blank values remain valid because callers normalize them to the
 * compatibility namespace {@code default}. Requiredness must be enforced
 * separately when a specific API requires an explicit namespace.
 */
@Documented
@Constraint(validatedBy = SourceNamespaceValidator.class)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER,
        ElementType.ANNOTATION_TYPE, ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidSourceNamespace {

    String message() default
            "Source namespace must be blank or contain visible ASCII characters";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}

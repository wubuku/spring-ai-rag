package com.springairag.api.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Bean Validation implementation for external source namespaces.
 */
public final class SourceNamespaceValidator
        implements ConstraintValidator<ValidSourceNamespace, String> {

    public static boolean isValid(String value) {
        if (value == null || value.isBlank()) {
            return true;
        }
        String normalized = value.trim();
        for (int i = 0; i < normalized.length(); i++) {
            char current = normalized.charAt(i);
            if (current < 0x20 || current > 0x7e) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return isValid(value);
    }
}

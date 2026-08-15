package com.springairag.api.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Bean Validation implementation for Collection keys.
 */
public final class CollectionKeyValidator
        implements ConstraintValidator<ValidCollectionKey, String> {

    public static boolean isValid(String value) {
        if (value == null) {
            return true;
        }
        if (value.length() < 1 || value.length() > 128) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch < 0x21 || ch > 0x7e) {
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

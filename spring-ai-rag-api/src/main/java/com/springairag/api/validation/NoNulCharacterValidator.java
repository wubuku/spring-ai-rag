package com.springairag.api.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * {@link NoNulCharacter} 的 Bean Validation 实现。
 */
public final class NoNulCharacterValidator
        implements ConstraintValidator<NoNulCharacter, CharSequence> {

    /**
     * null 交给 {@code @NotBlank}/{@code @NotNull} 处理，此处只负责字符集。
     */
    public static boolean isValid(CharSequence value) {
        if (value == null) {
            return true;
        }
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) == '\u0000') {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean isValid(CharSequence value, ConstraintValidatorContext context) {
        return isValid(value);
    }
}

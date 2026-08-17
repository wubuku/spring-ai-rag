package com.springairag.core.chat;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Public chat session identifier contract.
 */
public final class SessionIdValidator {

    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9._~-]{1,36}");

    private SessionIdValidator() {
    }

    public static String resolve(String value) {
        if (value == null || value.isBlank()) {
            return UUID.randomUUID().toString();
        }
        if (!VALID.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "sessionId must contain 1-36 ASCII letters, digits, '.', '_', '~' or '-'");
        }
        return value;
    }

    public static boolean isValid(String value) {
        return value != null && VALID.matcher(value).matches();
    }
}

package com.springairag.core.chat;

import com.springairag.api.enums.ErrorCode;
import com.springairag.core.exception.RagException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * Validates and hashes the public {@code Idempotency-Key} header.
 */
public final class IdempotencyKeyValidator {

    private IdempotencyKeyValidator() {
    }

    public static String normalize(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        if (values.size() != 1) {
            throw invalid();
        }
        String value = values.getFirst();
        if (value == null) {
            throw invalid();
        }
        value = trimOws(value);
        if (value.length() < 1 || value.length() > 255) {
            throw invalid();
        }
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            if (c < 0x21 || c > 0x7e || c == ',' || Character.isWhitespace(c)) {
                throw invalid();
            }
        }
        return value;
    }

    public static String hash(String normalizedValue) {
        if (normalizedValue == null) {
            return null;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(normalizedValue.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static String trimOws(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && (value.charAt(start) == ' '
                || value.charAt(start) == '\t')) {
            start++;
        }
        while (end > start && (value.charAt(end - 1) == ' '
                || value.charAt(end - 1) == '\t')) {
            end--;
        }
        return value.substring(start, end);
    }

    private static RagException invalid() {
        return new RagException(
                ErrorCode.IDEMPOTENCY_KEY_INVALID,
                "Idempotency-Key must contain 1-255 visible ASCII characters");
    }
}

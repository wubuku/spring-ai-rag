package com.springairag.core.logging;

import org.slf4j.MDC;

import java.util.Map;
import java.util.Set;

/**
 * Utility for programmatically adding sensitive data to MDC with automatic masking.
 *
 * <p>Usage: Instead of {@code MDC.put("password", "secret")}, use
 * {@code SensitiveMdc.put("password", "secret")} to have the value
 * automatically masked in JSON logs.</p>
 *
 * <p>The masked value is stored as "[MASKED]" in MDC, and the original
 * value is NOT retained anywhere to prevent accidental exposure.</p>
 *
 * <pre>{@code
 * // In a request filter or service method:
 * SensitiveMdc.put("userId", userId);
 * SensitiveMdc.put("sessionToken", token);
 * try {
 *     // ... business logic
 * } finally {
 *     SensitiveMdc.clear();
 * }
 * }</pre>
 */
public final class SensitiveMdc {

    /** Keys that are always treated as sensitive */
    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "password", "passwd", "pwd",
            "apiKey", "api_key", "apikey", "api-key",
            "token", "accessToken", "access_token", "refreshToken", "refresh_token",
            "secret", "secretKey", "secret_key", "clientSecret",
            "authorization", "auth",
            "privateKey", "private_key",
            "creditCard", "credit_card", "cvv", "ssn"
    );

    private SensitiveMdc() {
        // utility class
    }

    /**
     * Put a sensitive key-value pair into MDC.
     * The value will be stored as "[MASKED]" to prevent accidental exposure in logs.
     *
     * @param key   the MDC key
     * @param value the sensitive value (will be masked)
     */
    public static void put(String key, String value) {
        if (isSensitiveKey(key)) {
            MDC.put(key, "[MASKED]");
        } else {
            MDC.put(key, value);
        }
    }

    /**
     * Put a key-value pair with explicit sensitive flag.
     *
     * @param key       the MDC key
     * @param value     the value
     * @param sensitive whether to mask the value
     */
    public static void put(String key, String value, boolean sensitive) {
        if (sensitive) {
            MDC.put(key, "[MASKED]");
        } else {
            MDC.put(key, value);
        }
    }

    /**
     * Put all entries from a map, automatically masking sensitive values.
     *
     * @param entries map of MDC entries
     */
    public static void putAll(Map<String, String> entries) {
        if (entries == null) return;
        entries.forEach(SensitiveMdc::put);
    }

    /**
     * Clear all MDC values that were set via SensitiveMdc.
     * Note: This clears ALL MDC values, not just sensitive ones.
     */
    public static void clear() {
        MDC.clear();
    }

    /**
     * Check if a key is automatically treated as sensitive.
     * Supports two matching modes (case-insensitive):
     * <ol>
     *   <li>Substring match: {@code api_key} in {@code x_api_key_token} matches</li>
     *   <li>Underscore↔camelCase swap: {@code privateKey} matches {@code private_key}</li>
     * </ol>
     *
     * @param key the MDC key
     * @return true if the key should be masked
     */
    public static boolean isSensitiveKey(String key) {
        if (key == null) return false;
        String lower = key.toLowerCase();
        // Exact match: "private_key" == "private_key", "credit_card" == "credit_card", etc.
        if (lower.equals("private_key") || lower.equals("credit_card")
                || lower.equals("access_token") || lower.equals("refresh_token")
                || lower.equals("secret_key") || lower.equals("api_key")
                || SENSITIVE_KEYS.stream().anyMatch(s -> lower.equals(s))) {
            return true;
        }
        // Substring match: "api_key" in "x_api_key_token"
        if (SENSITIVE_KEYS.stream().anyMatch(s -> lower.contains(s))) {
            return true;
        }
        // Underscore <-> camelCase cross-match: "privateKey" matches "private_key" (and vice versa)
        for (String s : SENSITIVE_KEYS) {
            String swapped = swapUnderscoreCamel(s);
            if (!swapped.equals(s) && lower.contains(swapped)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Swap underscore and camelCase: "private_key" → "privateKey", "access_token" → "accessToken"
     */
    private static String swapUnderscoreCamel(String s) {
        if (!s.contains("_")) return s;
        // snake_case → camelCase: "private_key" → "privateKey"
        StringBuilder sb = new StringBuilder();
        boolean afterUnderscore = false;
        for (char c : s.toCharArray()) {
            if (c == '_') {
                afterUnderscore = true;
            } else if (afterUnderscore) {
                sb.append(Character.toUpperCase(c));
                afterUnderscore = false;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}

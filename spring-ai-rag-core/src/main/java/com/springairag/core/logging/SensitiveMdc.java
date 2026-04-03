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
     * Check if a key is automatically treated as sensitive (case-insensitive).
     *
     * @param key the MDC key
     * @return true if the key should be masked
     */
    public static boolean isSensitiveKey(String key) {
        if (key == null) return false;
        String lower = key.toLowerCase();
        // Case 1: exact match in SENSITIVE_KEYS
        if (SENSITIVE_KEYS.contains(lower)) return true;
        // Case 2: substring match (key is a substring of a sensitive name, case-insensitive)
        for (String s : SENSITIVE_KEYS) {
            if (s.toLowerCase().contains(lower)) return true;
        }
        // Case 3: underscore ↔ camelCase bidirectional swap
        if (lower.contains("_")) {
            // underscore key → camelCase variant
            String camel = swapUnderscoreCamel(lower);
            if (SENSITIVE_KEYS.contains(camel)) return true;
        } else {
            // camelCase key → underscore variant
            String snake = swapCamelToUnderscore(lower);
            if (!snake.equals(lower) && SENSITIVE_KEYS.contains(snake)) return true;
        }
        return false;
    }

    /** snake_case → camelCase: "private_key" → "privatekey" */
    private static String swapUnderscoreCamel(String s) {
        StringBuilder sb = new StringBuilder();
        boolean nextUpper = false;
        for (char c : s.toCharArray()) {
            if (c == '_') { nextUpper = true; }
            else if (nextUpper) { sb.append(Character.toUpperCase(c)); nextUpper = false; }
            else { sb.append(c); }
        }
        return sb.toString();
    }

    /** camelCase → snake_case: "privateKey" → "private_key" */
    private static String swapCamelToUnderscore(String s) {
        if (!s.matches(".*[A-Z].*")) return s;
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (Character.isUpperCase(c)) { sb.append('_').append(Character.toLowerCase(c)); }
            else { sb.append(c); }
        }
        return sb.toString();
    }
}

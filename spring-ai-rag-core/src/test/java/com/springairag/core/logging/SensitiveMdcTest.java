package com.springairag.core.logging;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SensitiveMdc utility.
 * Verifies automatic masking of sensitive MDC values.
 */
class SensitiveMdcTest {

    @BeforeEach
    @AfterEach
    void cleanup() {
        MDC.clear();
    }

    // ===== Automatic Sensitive Key Detection =====

    @ParameterizedTest
    @CsvSource({
        "password, true",
        "PASSWORD, true",
        "Password, true",
        "passwd, true",
        "pwd, true",
        "apiKey, true",
        "API_KEY, true",
        "apikey, true",
        "api-key, true",
        "token, true",
        "accessToken, true",
        "access_token, true",
        "refreshToken, true",
        "refresh_token, true",
        "secret, true",
        "secretKey, true",
        "secret_key, true",
        "clientSecret, true",
        "authorization, true",
        "auth, true",
        "privateKey, true",
        "private_key, true",
        "creditCard, true",
        "credit_card, true",
        "cvv, true",
        "ssn, true",
        "username, false",
        "userId, false",
        "email, false",
        "name, false",
        "traceId, false",
        "sessionId, false",
        "domainId, false"
    })
    void isSensitiveKey(String key, boolean expected) {
        assertEquals(expected, SensitiveMdc.isSensitiveKey(key));
    }

    // ===== put() automatic masking =====

    @Test
    void put_passwordAutoMasked() {
        SensitiveMdc.put("password", "MySecret123");
        assertEquals("[MASKED]", MDC.get("password"));
    }

    @Test
    void put_apiKeyAutoMasked() {
        SensitiveMdc.put("apiKey", "sk-live-abcdefghijklmnop");
        assertEquals("[MASKED]", MDC.get("apiKey"));
    }

    @Test
    void put_tokenAutoMasked() {
        SensitiveMdc.put("token", "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9");
        assertEquals("[MASKED]", MDC.get("token"));
    }

    @Test
    void put_nonSensitiveNotMasked() {
        SensitiveMdc.put("userId", "user-123");
        assertEquals("user-123", MDC.get("userId"));
    }

    @Test
    void put_nullValueHandled() {
        SensitiveMdc.put("password", null);
        assertEquals("[MASKED]", MDC.get("password"));
    }

    // ===== put() with explicit sensitive flag =====

    @Test
    void put_explicitSensitiveTrue() {
        SensitiveMdc.put("customKey", "myValue", true);
        assertEquals("[MASKED]", MDC.get("customKey"));
    }

    @Test
    void put_explicitSensitiveFalse() {
        SensitiveMdc.put("customKey", "myValue", false);
        assertEquals("myValue", MDC.get("customKey"));
    }

    // ===== putAll() =====

    @Test
    void putAll_masksSensitiveValues() {
        Map<String, String> entries = Map.of(
                "userId", "user-123",
                "password", "secret456",
                "apiKey", "sk-live-xyz",
                "action", "login"
        );
        SensitiveMdc.putAll(entries);

        assertEquals("user-123", MDC.get("userId"));
        assertEquals("[MASKED]", MDC.get("password"));
        assertEquals("[MASKED]", MDC.get("apiKey"));
        assertEquals("login", MDC.get("action"));
    }

    @Test
    void putAll_nullMap() {
        // Should not throw
        SensitiveMdc.putAll(null);
    }

    // ===== clear() =====

    @Test
    void clear_removesAllMdc() {
        MDC.put("traceId", "abc123");
        MDC.put("password", "secret");
        SensitiveMdc.clear();
        assertNull(MDC.get("traceId"));
        assertNull(MDC.get("password"));
    }

    // ===== Case Insensitivity =====

    @ParameterizedTest
    @ValueSource(strings = {
        "PASSWORD", "Password", "PaSsWoRd",
        "APIKEY", "ApiKey", "API_KEY",
        "TOKEN", "Token", "ACCESS_TOKEN"
    })
    void put_caseInsensitiveSensitiveKeys(String key) {
        SensitiveMdc.put(key, "secret-value");
        assertEquals("[MASKED]", MDC.get(key));
    }
}

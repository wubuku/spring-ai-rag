package com.springairag.core.logging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SensitiveDataMaskingConverter.
 * Verifies that sensitive data patterns are properly masked in log messages.
 */
class SensitiveDataMaskingConverterTest {

    private static final String MASK = "***REDACTED***";

    // ===== JSON Field Masking =====

    @Test
    void maskSensitiveData_jsonPassword() {
        String input = "User login: {\"username\":\"admin\",\"password\":\"secret123\"}";
        String result = SensitiveDataMaskingConverter.maskSensitiveData(input);
        assertTrue(result.contains(MASK));
        assertFalse(result.contains("secret123"));
        assertTrue(result.contains("admin")); // non-sensitive data preserved
    }

    @Test
    void maskSensitiveData_jsonApiKey() {
        String input = "{\"apiKey\":\"sk-test-1234567890abcdef\"}";
        String result = SensitiveDataMaskingConverter.maskSensitiveData(input);
        assertTrue(result.contains(MASK));
        assertFalse(result.contains("sk-test-1234567890abcdef"));
    }

    @Test
    void maskSensitiveData_jsonToken() {
        String input = "{\"access_token\":\"eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U\"}";
        String result = SensitiveDataMaskingConverter.maskSensitiveData(input);
        assertTrue(result.contains(MASK));
        assertFalse(result.contains("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"));
    }

    @Test
    void maskSensitiveData_jsonSecret() {
        String input = "{\"secret\":\"my-super-secret-key\"}";
        String result = SensitiveDataMaskingConverter.maskSensitiveData(input);
        assertTrue(result.contains(MASK));
        assertFalse(result.contains("my-super-secret-key"));
    }

    @Test
    void maskSensitiveData_jsonAuthorization() {
        String input = "{\"authorization\":\"Bearer eyJhbGciOiJIUzI1NiJ9\"}";
        String result = SensitiveDataMaskingConverter.maskSensitiveData(input);
        assertTrue(result.contains(MASK));
        assertFalse(result.contains("Bearer eyJhbGciOiJIUzI1NiJ9"));
    }

    @Test
    void maskSensitiveData_jsonPrivateKey() {
        String input = "{\"privateKey\":\"-----BEGIN RSA PRIVATE KEY-----\\nMIIEowIBAAKCAQEA...\"}";
        String result = SensitiveDataMaskingConverter.maskSensitiveData(input);
        assertTrue(result.contains(MASK));
        assertFalse(result.contains("-----BEGIN RSA PRIVATE KEY-----"));
    }

    @Test
    void maskSensitiveData_jsonCaseInsensitive() {
        String input = "{\"PASSWORD\":\"MyPass123\",\"ApiKey\":\"key123\"}";
        String result = SensitiveDataMaskingConverter.maskSensitiveData(input);
        assertTrue(result.contains(MASK));
        assertFalse(result.contains("MyPass123"));
        assertFalse(result.contains("key123"));
    }

    @Test
    void maskSensitiveData_jsonMultipleFields() {
        String input = "{\"username\":\"john\",\"password\":\"pass123\",\"apiKey\":\"key456\"}";
        String result = SensitiveDataMaskingConverter.maskSensitiveData(input);
        assertTrue(result.contains("john")); // non-sensitive preserved
        assertTrue(result.contains(MASK));
        assertFalse(result.contains("pass123"));
        assertFalse(result.contains("key456"));
    }

    // ===== URL Query Parameter Masking =====

    @Test
    void maskSensitiveData_urlQueryPassword() {
        String input = "GET /api/login?username=admin&password=secret123 HTTP/1.1";
        String result = SensitiveDataMaskingConverter.maskSensitiveData(input);
        assertTrue(result.contains(MASK));
        assertFalse(result.contains("secret123"));
        assertTrue(result.contains("admin")); // non-sensitive preserved
    }

    @Test
    void maskSensitiveData_urlQueryApiKey() {
        String input = "GET /api/data?apiKey=sk-live-1234567890 HTTP/1.1";
        String result = SensitiveDataMaskingConverter.maskSensitiveData(input);
        assertTrue(result.contains(MASK));
        assertFalse(result.contains("sk-live-1234567890"));
    }

    @Test
    void maskSensitiveData_urlQueryToken() {
        String input = "GET /api/profile?access_token=abc123xyz&name=John";
        String result = SensitiveDataMaskingConverter.maskSensitiveData(input);
        assertTrue(result.contains(MASK));
        assertFalse(result.contains("abc123xyz"));
        assertTrue(result.contains("John")); // non-sensitive preserved
    }

    @Test
    void maskSensitiveData_urlQuerySecret() {
        String input = "POST /api/webhook?secret=myWebhookSecret123&event=update";
        String result = SensitiveDataMaskingConverter.maskSensitiveData(input);
        assertTrue(result.contains(MASK));
        assertFalse(result.contains("myWebhookSecret123"));
        assertTrue(result.contains("event=update")); // non-sensitive preserved
    }

    // ===== Key-Value Pair Masking =====

    @Test
    void maskSensitiveData_kvPassword() {
        String input = "Config: password=MySecretPass";
        String result = SensitiveDataMaskingConverter.maskSensitiveData(input);
        assertTrue(result.contains(MASK));
        assertFalse(result.contains("MySecretPass"));
    }

    @Test
    void maskSensitiveData_kvApiKey() {
        String input = "Setting apiKey=sk_test_1234567890";
        String result = SensitiveDataMaskingConverter.maskSensitiveData(input);
        assertTrue(result.contains(MASK));
        assertFalse(result.contains("sk_test_1234567890"));
    }

    @Test
    void maskSensitiveData_kvAccessToken() {
        String input = "Token: access_token=eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9";
        String result = SensitiveDataMaskingConverter.maskSensitiveData(input);
        assertTrue(result.contains(MASK));
        assertFalse(result.contains("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"));
    }

    @Test
    void maskSensitiveData_kvSecretKey() {
        String input = "secretKey=my-aws-secret-key-12345";
        String result = SensitiveDataMaskingConverter.maskSensitiveData(input);
        assertTrue(result.contains(MASK));
        assertFalse(result.contains("my-aws-secret-key-12345"));
    }

    // ===== Authorization Header Masking =====

    @Test
    void maskSensitiveData_bearerToken() {
        String input = "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0";
        String result = SensitiveDataMaskingConverter.maskSensitiveData(input);
        assertTrue(result.contains("Bearer " + MASK) || result.contains(MASK));
        assertFalse(result.contains("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"));
    }

    @Test
    void maskSensitiveData_basicAuth() {
        String input = "Authorization: Basic dXNlcm5hbWU6cGFzc3dvcmQ=";
        String result = SensitiveDataMaskingConverter.maskSensitiveData(input);
        assertTrue(result.contains("Basic " + MASK) || result.contains(MASK));
        assertFalse(result.contains("dXNlcm5hbWU6cGFzc3dvcmQ="));
    }

    @Test
    void maskSensitiveData_lowercaseBearer() {
        String input = "authorization: bearer my-token-12345";
        String result = SensitiveDataMaskingConverter.maskSensitiveData(input);
        assertTrue(result.contains(MASK));
        assertFalse(result.contains("my-token-12345"));
    }

    // ===== AWS Key Masking =====

    @Test
    void maskSensitiveData_awsAccessKey() {
        String input = "AWS credentials: AKIAIOSFODNN7EXAMPLE";
        String result = SensitiveDataMaskingConverter.maskSensitiveData(input);
        assertTrue(result.contains(MASK));
        assertFalse(result.contains("AKIAIOSFODNN7EXAMPLE"));
    }

    // ===== Non-Sensitive Data Preservation =====

    @Test
    void maskSensitiveData_noSensitiveData() {
        String input = "User admin logged in from IP 192.168.1.1 at 2024-01-15 10:30:00";
        String result = SensitiveDataMaskingConverter.maskSensitiveData(input);
        assertEquals(input, result); // unchanged
    }

    @Test
    void maskSensitiveData_onlyWhitespace() {
        assertEquals("   ", SensitiveDataMaskingConverter.maskSensitiveData("   "));
    }

    @Test
    void maskSensitiveData_null() {
        assertNull(SensitiveDataMaskingConverter.maskSensitiveData(null));
    }

    @Test
    void maskSensitiveData_empty() {
        assertEquals("", SensitiveDataMaskingConverter.maskSensitiveData(""));
    }

    @Test
    void maskSensitiveData_normalApiResponse() {
        String input = "GET /api/users/123 - 200 OK - Response time: 45ms";
        String result = SensitiveDataMaskingConverter.maskSensitiveData(input);
        assertEquals(input, result); // no sensitive data, unchanged
    }

    @Test
    void maskSensitiveData_sqlQuery() {
        String input = "SELECT * FROM users WHERE username='admin' AND password='secret'";
        String result = SensitiveDataMaskingConverter.maskSensitiveData(input);
        assertTrue(result.contains(MASK));
        assertFalse(result.contains("secret"));
        assertTrue(result.contains("admin")); // part of query, not a password field name pattern
    }

    // ===== Edge Cases =====

    @Test
    void maskSensitiveData_multipleSensitiveFieldsSameLine() {
        String input = "Login attempt: user=admin, pass=secret, key=api-key-123, token=bearer-token-456";
        String result = SensitiveDataMaskingConverter.maskSensitiveData(input);
        System.out.println("ACTUAL RESULT: [" + result + "]");
        System.out.println("contains 'secret': " + result.contains("secret"));
        // Should mask at least the clearly sensitive fields
        assertFalse(result.contains("secret"));
        assertFalse(result.contains("api-key-123"));
        assertFalse(result.contains("bearer-token-456"));
    }

    @ParameterizedTest
    @CsvSource({
        "'User login: {\\\"password\\\":\\\"secret123\\\"}', 'secret123'",
        "'apiKey=sk-live-abcdefghij', 'sk-live-abcdefghij'",
        "'Authorization: Bearer mytoken123', 'mytoken123'",
        "'GET /api?token=abc123', 'abc123'"
    })
    void maskSensitiveData_parameterized(String input, String sensitiveValue) {
        String result = SensitiveDataMaskingConverter.maskSensitiveData(input);
        assertFalse(result.contains(sensitiveValue), () -> "Sensitive value '" + sensitiveValue + "' should be masked in: " + result);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "No sensitive data here",
        "Just a normal log message",
        "URL: https://api.example.com/data",
        "HTTP/1.1 200 OK",
        "Content-Type: application/json"
    })
    void maskSensitiveData_nonSensitive(String input) {
        String result = SensitiveDataMaskingConverter.maskSensitiveData(input);
        assertEquals(input, result);
    }

    // ===== Mask Keep Type =====

    @Test
    void maskSensitiveDataKeepType_passwordField() {
        String input = "Login failed with password=wrong";
        String result = SensitiveDataMaskingConverter.maskSensitiveDataKeepType(input);
        assertTrue(result.contains("[SENSITIVE:PASSWORD]"));
        assertFalse(result.contains("wrong"));
    }

    @Test
    void maskSensitiveDataKeepType_noMatch() {
        String input = "Normal log message with no sensitive data";
        String result = SensitiveDataMaskingConverter.maskSensitiveDataKeepType(input);
        assertEquals(input, result);
    }
}

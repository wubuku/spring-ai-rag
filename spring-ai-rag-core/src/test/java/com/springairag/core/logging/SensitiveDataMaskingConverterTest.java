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

    // ===== Chinese National ID Masking =====

    @Test
    void maskSensitiveData_chineseNationalId_standard() {
        // Valid 18-digit national ID (birthdate: 1990-03-07, area: 110101)
        String input = "RAG query: search patient info, nationalId=110101199003071234";
        String result = SensitiveDataMaskingConverter.maskSensitiveData(input);
        assertFalse(result.contains("110101199003071234"));
        assertTrue(result.contains(MASK));
    }

    @Test
    void maskSensitiveData_chineseNationalId_xChecksum() {
        // National ID ending with X: 18 chars total
        // 110101 (area) + 19990512 (birthdate) + 012 (seq) + X (checksum) = 18
        String input = "User query: 查询身份证号 11010119990512012X 的信息";
        String result = SensitiveDataMaskingConverter.maskSensitiveData(input);
        assertFalse(result.contains("11010119990512012X"));
        assertTrue(result.contains(MASK));
        assertTrue(result.contains("查询"));  // non-sensitive preserved
    }

    @Test
    void maskSensitiveData_chineseNationalId_inJson() {
        // Valid: birthdate 1988-05-07, area 310101
        String input = "{\"query\":\"准备材料\",\"nationalId\":\"310101198805071234\"}";
        String result = SensitiveDataMaskingConverter.maskSensitiveData(input);
        assertFalse(result.contains("310101198805071234"));
        assertTrue(result.contains(MASK));
        assertTrue(result.contains("准备材料")); // non-sensitive preserved
    }

    @Test
    void maskSensitiveData_chineseNationalId_userQuery() {
        // Realistic RAG user query containing national ID (birthdate: 1994-08-12)
        String input = "RAG ask: sessionId=abc123, message=我的身份证号是 420106199408121234，请帮我查询";
        String result = SensitiveDataMaskingConverter.maskSensitiveData(input);
        assertFalse(result.contains("420106199408121234"));
        assertTrue(result.contains(MASK));
        assertTrue(result.contains("我的身份证号是")); // non-sensitive part preserved
    }

    @ParameterizedTest
    @CsvSource({
        // Valid IDs: 6-digit area + 8-digit birthdate (YYYYMMDD, month 01-12, day 01-29) + 3-digit seq + checksum
        "'110101199003071234', '110101199003071234', true",     // area=110101, birth=1990-03-07, seq=123
        "'110101199905121234', '110101199905121234', true",     // area=110101, birth=1999-05-12, seq=123 (checksum=4)
        "'310101198805071234', '310101198805071234', true",     // area=310101, birth=1988-05-07, seq=234
        "'420106199408121234', '420106199408121234', true",     // area=420106, birth=1994-08-12, seq=234
        // Invalid IDs
        "'000101201001011234', '000101201001011234', false",     // invalid: starts 000
        "'110101199900071234', '110101199900071234', false",     // invalid: month=00 (positions 10-11='00')
        "'110101199907001234', '110101199907001234', false",     // invalid: day=00 (positions 12-13='00')
        "'110101199905120X', '110101199905120X', false",         // invalid: len=16 (only 15 digits before X, seq needs 3 digits)
        "'11010119990307123', '11010119990307123', false",       // too short (17 chars, seq 2 digits)
        "'1101011999030712345', '1101011999030712345', false",  // too long (19 chars)
        "'11010119990307AB18', '11010119990307AB18', false",     // invalid: 'AB' in seq (positions 14-15='AB')
        "'123456789012345678', '123456789012345678', false",      // invalid: starts with 1 but month=90 at positions 10-11
    })
    void maskSensitiveData_chineseNationalId_validity(String id, String expected, boolean shouldMask) {
        String input = "query: " + id;
        String result = SensitiveDataMaskingConverter.maskSensitiveData(input);
        boolean masked = !result.contains(id);
        assertEquals(shouldMask, masked, () -> "ID " + id + " masking=" + masked + " (expected " + shouldMask + ")");
    }

    // ===== Chinese Mobile Phone Number Masking =====

    @Test
    void maskSensitiveData_chinesePhone_standard() {
        String input = "RAG query: search docs about 13800138000";
        String result = SensitiveDataMaskingConverter.maskSensitiveData(input);
        assertFalse(result.contains("13800138000"));
        assertTrue(result.contains(MASK));
    }

    @Test
    void maskSensitiveData_chinesePhone_variousPrefixes() {
        // All valid prefixes: 133, 134(0-9), 135-139, 141-149, 150-159, 160-169, 170-179, 180-189, 191-199
        String[] phones = {"13312345678", "13401234567", "13512345678", "13912345678",
                           "14712345678", "15012345678", "15912345678",
                           "16612345678", "17012345678", "17812345678",
                           "18012345678", "19912345678"};
        for (String phone : phones) {
            String input = "query: " + phone;
            String result = SensitiveDataMaskingConverter.maskSensitiveData(input);
            assertFalse(result.contains(phone), () -> "Phone " + phone + " should be masked: " + result);
            assertTrue(result.contains(MASK));
        }
    }

    @Test
    void maskSensitiveData_chinesePhone_userQuery() {
        // Realistic RAG user query containing phone number
        String input = "RAG ask: sessionId=sess-001, message=请联系我，手机号 13800138000";
        String result = SensitiveDataMaskingConverter.maskSensitiveData(input);
        assertFalse(result.contains("13800138000"));
        assertTrue(result.contains(MASK));
        assertTrue(result.contains("请联系我"));  // non-sensitive preserved
    }

    @Test
    void maskSensitiveData_chinesePhone_invalidNumbers() {
        // These should NOT be masked (invalid format)
        String[] invalid = {"12345678901", "10012345678", "12012345678", "138123456", "138001380001"};
        for (String phone : invalid) {
            String input = "query: " + phone;
            String result = SensitiveDataMaskingConverter.maskSensitiveData(input);
            assertEquals(input, result, () -> "Invalid phone " + phone + " should NOT be masked: " + result);
        }
    }

    @Test
    void maskSensitiveData_chinesePhone_withNationalId() {
        // Both patterns in one message (valid national ID: 1990-03-07)
        String input = "query: 身份证 110101199003071234，手机 13800138000";
        String result = SensitiveDataMaskingConverter.maskSensitiveData(input);
        assertFalse(result.contains("110101199003071234"));
        assertFalse(result.contains("13800138000"));
        assertTrue(result.contains(MASK));
    }

    @Test
    void maskSensitiveDataKeepType_chineseNationalId() {
        String input = "query: 110101199003074518";
        String result = SensitiveDataMaskingConverter.maskSensitiveDataKeepType(input);
        assertTrue(result.contains("[SENSITIVE:NATIONAL_ID]"));
        assertFalse(result.contains("110101199003074518"));
    }

    @Test
    void maskSensitiveDataKeepType_chinesePhone() {
        String input = "query: 13800138000";
        String result = SensitiveDataMaskingConverter.maskSensitiveDataKeepType(input);
        assertTrue(result.contains("[SENSITIVE:PHONE]"));
        assertFalse(result.contains("13800138000"));
    }
}

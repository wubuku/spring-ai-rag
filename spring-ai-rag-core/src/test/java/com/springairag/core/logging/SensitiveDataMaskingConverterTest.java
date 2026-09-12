package com.springairag.core.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SensitiveDataMaskingConverter 脱敏语义：各类敏感模式替换、空/null
 * 输入透传、convert 事件脱敏、getSensitiveType 分类。
 */
class SensitiveDataMaskingConverterTest {

    private final SensitiveDataMaskingConverter converter =
            new SensitiveDataMaskingConverter();

    private String mask(String message) {
        return SensitiveDataMaskingConverter.maskSensitiveData(message);
    }

    @Test
    void nullAndEmptyPassThrough() {
        assertNull(mask(null));
        assertEquals("", mask(""));
    }

    @Test
    void normalTextUnchanged() {
        assertEquals("Hello World", mask("Hello World"));
    }

    @Test
    void masksJsonPassword() {
        String result = mask("{\"password\":\"secret123\"}");
        assertFalse(result.contains("secret123"));
        assertTrue(result.contains("***REDACTED***"));
    }

    @Test
    void masksJsonApiKey() {
        String result = mask("{\"apiKey\":\"ak-123456\"}");
        assertFalse(result.contains("ak-123456"));
        assertTrue(result.contains("***REDACTED***"));
    }

    @Test
    void masksJsonToken() {
        String result = mask("{\"token\":\"tok-abc\"}");
        assertFalse(result.contains("tok-abc"));
    }

    @Test
    void masksJsonSecret() {
        String result = mask("{\"secret\":\"s3cr3t\"}");
        assertFalse(result.contains("s3cr3t"));
    }

    @Test
    void masksJsonAuthorization() {
        String result = mask("{\"authorization\":\"Bearer xyz\"}");
        assertFalse(result.contains("Bearer xyz"));
    }

    @Test
    void masksUrlQueryPassword() {
        String result = mask("/api/login?password=abc123&user=admin");
        assertFalse(result.contains("abc123"));
        assertTrue(result.contains("user=admin"));
    }

    @Test
    void masksUrlQueryApiKey() {
        String result = mask("/api/data?apiKey=key123&format=json");
        assertFalse(result.contains("key123"));
    }

    @Test
    void masksKeyValuePassword() {
        String result = mask("password = mysecret");
        assertFalse(result.contains("mysecret"));
    }

    @Test
    void masksSqlSingleQuoted() {
        String result = mask("SELECT * WHERE password='s3cret'");
        assertFalse(result.contains("s3cret"));
    }

    @Test
    void masksBearerToken() {
        String result = mask("Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.test.sig");
        assertFalse(result.contains("eyJhbGciOiJIUzI1NiJ9"));
    }

    @Test
    void masksBasicAuth() {
        String result = mask("Auth: Basic dXNlcjpwYXNz");
        assertFalse(result.contains("dXNlcjpwYXNz"));
    }

    @Test
    void masksChineseNationalId() {
        String result = mask("ID: 110101199003071234");
        assertFalse(result.contains("110101199003071234"));
        assertTrue(result.contains("***REDACTED***"));
    }

    @Test
    void masksChinesePhone() {
        String result = mask("Phone: 13800138000");
        assertFalse(result.contains("13800138000"));
    }

    @Test
    void convert_masksFormattedMessage() {
        ILoggingEvent event = mock(ILoggingEvent.class);
        when(event.getFormattedMessage())
                .thenReturn("{\"password\":\"test123\"}");

        String result = converter.convert(event);

        assertFalse(result.contains("test123"));
        assertTrue(result.contains("***REDACTED***"));
    }

    @Test
    void convert_nullMessageReturnsNull() {
        ILoggingEvent event = mock(ILoggingEvent.class);
        when(event.getFormattedMessage()).thenReturn(null);

        assertNull(converter.convert(event));
    }

    @Test
    void convert_emptyMessageReturnsEmpty() {
        ILoggingEvent event = mock(ILoggingEvent.class);
        when(event.getFormattedMessage()).thenReturn("");

        assertEquals("", converter.convert(event));
    }

    @Test
    void maskSensitiveDataKeepType_masksWithTypeInfo() {
        String result = SensitiveDataMaskingConverter
                .maskSensitiveDataKeepType("{\"password\":\"abc\"}");
        assertTrue(result.contains("PASSWORD") || result.contains("SENSITIVE"));
    }

    @Test
    void maskSensitiveDataKeepType_nullReturnsNull() {
        assertNull(SensitiveDataMaskingConverter.maskSensitiveDataKeepType(null));
    }

    @Test
    void maskSensitiveDataKeepType_emptyReturnsEmpty() {
        assertEquals("", SensitiveDataMaskingConverter
                .maskSensitiveDataKeepType(""));
    }
}

package com.springairag.core.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 日志脱敏转换器类型识别长尾（Batch 625，JaCoCo 驱动）：
 * convert 对 null/空消息透传；maskSensitiveDataKeepType 对各类
 * 敏感值模式（PASSWORD/API_KEY/TOKEN/SECRET/AUTH/BEARER/
 * BASIC_AUTH/AWS_KEY/SENSITIVE 兜底）与中文身份证/手机号专用
 * 模式的类型识别投影。
 */
class SensitiveDataMaskingKeepTypeTailTest {

    private final SensitiveDataMaskingConverter converter =
            new SensitiveDataMaskingConverter();

    private String keepType(String message) {
        return SensitiveDataMaskingConverter.maskSensitiveDataKeepType(message);
    }

    @Test
    void convertPassesThroughNullAndEmptyMessages() {
        ILoggingEvent nullEvent = mock(ILoggingEvent.class);
        when(nullEvent.getFormattedMessage()).thenReturn(null);
        assertNull(converter.convert(nullEvent));

        ILoggingEvent emptyEvent = mock(ILoggingEvent.class);
        when(emptyEvent.getFormattedMessage()).thenReturn("");
        assertEquals("", converter.convert(emptyEvent));
    }

    @Test
    void keepTypePassthroughWhenNothingMatches() {
        assertEquals("no secrets here", keepType("no secrets here"));
    }

    @Test
    void keepTypeProjectsPasswordType() {
        String result = keepType("password=abc123");
        assertTrue(result.contains("[SENSITIVE:PASSWORD]"), "result=" + result);
    }

    @Test
    void keepTypeProjectsApiKeyType() {
        String result = keepType("apiKey=abc123xyz");
        assertTrue(result.contains("[SENSITIVE:API_KEY]"));
    }

    @Test
    void keepTypeProjectsTokenType() {
        String result = keepType("token=abc123xyz");
        assertTrue(result.contains("[SENSITIVE:TOKEN]"));
    }

    @Test
    void keepTypeProjectsSecretType() {
        String result = keepType("secretKey=abc123xyz");
        assertTrue(result.contains("[SENSITIVE:SECRET]"));
    }

    @Test
    void keepTypeProjectsAuthTypeFromUrlQuery() {
        String result = keepType("x?auth=abc123");
        assertTrue(result.contains("[SENSITIVE:AUTH]"));
    }

    @Test
    void keepTypeProjectsBearerTokenType() {
        String result = keepType("Bearer abc123.def");
        assertTrue(result.contains("[SENSITIVE:BEARER_TOKEN]"));
    }

    @Test
    void keepTypeProjectsBasicAuthType() {
        String result = keepType("Basic abc123+/=");
        assertTrue(result.contains("[SENSITIVE:BASIC_AUTH]"));
    }

    @Test
    void keepTypeProjectsAwsKeyType() {
        String result = keepType("AKIA1234567890ABCDEF");
        assertTrue(result.contains("[SENSITIVE:AWS_KEY]"));
    }

    @Test
    void keepTypeFallsBackToGenericSensitive() {
        String result = keepType("key=sk-abcdefghijklmnop1234");
        assertTrue(result.contains("[SENSITIVE:"), "result=" + result);
    }

    @Test
    void keepTypeProjectsNationalIdAndPhone() {
        String nationalId = keepType("110101199003071234");
        assertTrue(nationalId.contains("[SENSITIVE:NATIONAL_ID]"));

        String phone = keepType("13800138000");
        assertTrue(phone.contains("[SENSITIVE:PHONE]"));
    }

    @Test
    void convertMasksSensitiveValuesInFormattedMessage() {
        ILoggingEvent event = mock(ILoggingEvent.class);
        when(event.getFormattedMessage())
                .thenReturn("user login token=abc123xyz789 ok");

        String masked = converter.convert(event);

        assertTrue(masked.contains("REDACTED") || !masked.contains("abc123xyz789"),
                "masked=" + masked);
        assertTrue(!masked.contains("abc123xyz"));
    }

    private static void assertNull(Object value) {
        org.junit.jupiter.api.Assertions.assertNull(value);
    }
}

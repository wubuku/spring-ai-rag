package com.springairag.core.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for MaskingLogstashEncoder.
 * Verifies that sensitive data is masked before JSON encoding.
 */
class MaskingLogstashEncoderTest {

    private MaskingLogstashEncoder encoder;

    @BeforeEach
    void setUp() {
        encoder = new MaskingLogstashEncoder();
        encoder.start();
    }

    @Test
    void encode_masksPasswordInMessage() throws Exception {
        ILoggingEvent event = mockLoggingEvent(
            "User login: {\"username\":\"admin\",\"password\":\"secret123\"}"
        );

        byte[] result = encoder.encode(event);

        assertNotNull(result);
        String json = new String(result);
        // Password value should be masked
        assertFalse(json.contains("secret123"), 
            "Sensitive password should be masked in JSON output");
    }

    @Test
    void encode_masksApiKeyInMessage() throws Exception {
        ILoggingEvent event = mockLoggingEvent(
            "API call with key: {\"apiKey\":\"sk-live-abcdefghijklmnop\"}"
        );

        byte[] result = encoder.encode(event);

        assertNotNull(result);
        String json = new String(result);
        assertFalse(json.contains("sk-live-abcdefghijklmnop"),
            "Sensitive apiKey should be masked in JSON output");
    }

    @Test
    void encode_masksTokenInMessage() throws Exception {
        ILoggingEvent event = mockLoggingEvent(
            "Request token: access_token=eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"
        );

        byte[] result = encoder.encode(event);

        assertNotNull(result);
        String json = new String(result);
        assertFalse(json.contains("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"),
            "Sensitive token should be masked in JSON output");
    }

    @Test
    void encode_preservesNonSensitiveData() throws Exception {
        String safeMessage = "GET /api/users/123 - 200 OK - Response time: 45ms";
        ILoggingEvent event = mockLoggingEvent(safeMessage);

        byte[] result = encoder.encode(event);

        assertNotNull(result);
        String json = new String(result);
        assertTrue(json.contains("200 OK") || json.contains("ms"),
            "Non-sensitive data should be preserved");
    }

    @Test
    void encode_noChangeWhenNoSensitiveData() throws Exception {
        String safeMessage = "Normal log: user admin logged in at 10:30";
        ILoggingEvent event = mockLoggingEvent(safeMessage);

        byte[] result2 = encoder.encode(event);

        // Should still produce valid JSON output
        assertNotNull(result2);
        String json = new String(result2);
        assertTrue(json.startsWith("{") || json.startsWith("["),
            "Should produce valid JSON output: " + json);
    }

    @Test
    void encode_masksBearerToken() throws Exception {
        ILoggingEvent event = mockLoggingEvent(
            "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0"
        );

        byte[] result = encoder.encode(event);

        assertNotNull(result);
        String json = new String(result);
        assertFalse(json.contains("eyJhbGciOiJIUzI1NiJ9"),
            "Bearer token should be masked");
    }

    @Test
    void encode_masksAwsAccessKey() throws Exception {
        ILoggingEvent event = mockLoggingEvent(
            "AWS credentials: AKIAIOSFODNN7EXAMPLE"
        );

        byte[] result = encoder.encode(event);

        assertNotNull(result);
        String json = new String(result);
        assertFalse(json.contains("AKIAIOSFODNN7EXAMPLE"),
            "AWS access key should be masked");
    }

    @Test
    void encode_emptyMessage() throws Exception {
        ILoggingEvent event = mockLoggingEvent("");

        byte[] result = encoder.encode(event);

        assertNotNull(result);
    }

    @Test
    void encode_nullMessage() throws Exception {
        ILoggingEvent event = mockLoggingEvent(null);

        byte[] result = encoder.encode(event);

        assertNotNull(result);
    }

    /**
     * Creates a mock ILoggingEvent that returns the given message
     * for getFormattedMessage() and getMessage().
     */
    @SuppressWarnings("unchecked")
    private ILoggingEvent mockLoggingEvent(String message) throws Exception {
        ILoggingEvent event = mock(ILoggingEvent.class);
        when(event.getFormattedMessage()).thenReturn(message);
        when(event.getMessage()).thenReturn(message);
        when(event.getLoggerName()).thenReturn("test.logger");
        when(event.getLevel()).thenReturn(Level.INFO);
        when(event.getThreadName()).thenReturn("main");
        when(event.getInstant()).thenReturn(Instant.now());
        return event;
    }
}

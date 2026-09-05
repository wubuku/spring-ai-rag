package com.springairag.core.alertdelivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.core.config.NotificationConfig;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AlertNotificationPayloadSanitizerTest {

    private AlertNotificationPayloadSanitizer createSanitizer() {
        var config = new NotificationConfig();
        return new AlertNotificationPayloadSanitizer(new ObjectMapper(), config);
    }

    @Test
    void createsPayloadWithValidInput() {
        var sanitizer = createSanitizer();
        var result = sanitizer.create(
                UUID.randomUUID(), "SLO_BREACH", "Latency SLO", "WARNING",
                "Average latency exceeded threshold",
                Map.of("principalId", "rag_k_test"));

        assertNotNull(result);
        assertNotNull(result.payload());
        assertFalse(result.json().isEmpty());
        assertEquals("SLO_BREACH", result.payload().alertType());
        assertEquals("Latency SLO", result.payload().alertName());
        assertEquals("WARNING", result.payload().severity());
        assertFalse(result.payload().payloadTruncated());
    }

    @Test
    void sanitizesSensitiveKeysInMetrics() {
        var sanitizer = createSanitizer();
        var result = sanitizer.create(
                UUID.randomUUID(), "ALERT", "Auth", "ERROR",
                "Authentication failed",
                Map.of("password", "super-secret-value", "api_key", "sk-abc123def456"));

        String json = result.json();
        assertFalse(json.contains("super-secret-value"), "sensitive value must be redacted");
        assertTrue(json.contains("[REDACTED]"), "must contain redaction marker");
    }

    @Test
    void sanitizesBearerTokensInMessage() {
        var sanitizer = createSanitizer();
        var result = sanitizer.create(
                UUID.randomUUID(), "ALERT", "Auth", "ERROR",
                "Request failed: Bearer eyJhbGciOiJIUzI1NiJ9.test.token",
                Map.of());

        assertFalse(result.json().contains("eyJhbGciOiJIUzI1NiJ9"),
                "bearer token must be redacted from message");
    }

    @Test
    void handlesEmptyMetrics() {
        var sanitizer = createSanitizer();
        var result = sanitizer.create(
                UUID.randomUUID(), "INFO", "Test", "INFO", "No metrics", Map.of());

        assertNotNull(result);
        assertFalse(result.payload().payloadTruncated());
    }

    @Test
    void handlesNullMetrics() {
        var sanitizer = createSanitizer();
        var result = sanitizer.create(
                UUID.randomUUID(), "ALERT", "Null Test", "WARNING", "test", null);

        assertNotNull(result);
        assertFalse(result.json().isEmpty());
    }
}

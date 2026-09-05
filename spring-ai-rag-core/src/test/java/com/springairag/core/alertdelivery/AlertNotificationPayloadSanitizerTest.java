package com.springairag.core.alertdelivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.core.config.NotificationConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AlertNotificationPayloadSanitizerTest {

    private AlertNotificationPayloadSanitizer sanitizer;

    @BeforeEach
    void setUp() {
        var config = new NotificationConfig();
        config.getDelivery().setMaxPayloadBytes(65536);
        sanitizer = new AlertNotificationPayloadSanitizer(
                new ObjectMapper(), config);
    }

    private UUID deliveryId() {
        return UUID.randomUUID();
    }

    @Test
    void createsPayloadWithMetricsAndType() {
        var result = sanitizer.create(
                deliveryId(), "SLO_BREACH", "Latency SLO", "WARNING",
                "Average latency exceeded threshold",
                Map.of("principalId", "rag_k_test", "threshold", 800));

        assertNotNull(result.payload());
        assertFalse(result.json().isEmpty());
    }

    @Test
    void redactsSensitiveKeysInMetrics() {
        var result = sanitizer.create(
                deliveryId(), "ALERT", "Test", "ERROR",
                "Auth failed",
                Map.of("password", "super-secret-123", "api_key", "sk-abc123"));

        String json = result.json();
        assertFalse(json.contains("super-secret-123"), "sensitive value must be redacted");
        assertTrue(json.contains("[REDACTED]"), "must contain redaction marker");
    }

    @Test
    void sanitizesBearerTokensInMessage() {
        var result = sanitizer.create(
                deliveryId(), "ALERT", "Auth Test", "ERROR",
                "Request failed: Bearer sk-abc123def456ghi789",
                Map.of());

        assertFalse(result.json().contains("sk-abc123def456ghi789"),
                "bearer token must be redacted");
    }
}

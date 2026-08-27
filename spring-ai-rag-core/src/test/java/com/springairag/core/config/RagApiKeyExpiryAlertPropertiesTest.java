package com.springairag.core.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RagApiKeyExpiryAlertPropertiesTest {

    @Test
    void defaultsAreLowFrequencyAndBounded() {
        RagApiKeyExpiryAlertProperties properties =
                new RagApiKeyExpiryAlertProperties();

        assertDoesNotThrow(properties::validate);
        assertEquals(Duration.ofDays(30), properties.getWarningWindow());
        assertEquals(Duration.ofDays(7), properties.getCriticalWindow());
        assertEquals(Duration.ofHours(1), properties.getFallbackScanInterval());
        assertEquals(10_000, properties.getFallbackScanLimit());
        assertEquals(3, properties.getEventRetryAttempts());
    }

    @Test
    void rejectsInvalidWindowOrderAndAggressivePolling() {
        RagApiKeyExpiryAlertProperties properties =
                new RagApiKeyExpiryAlertProperties();

        properties.setCriticalWindow(Duration.ofDays(30));
        assertThrows(IllegalArgumentException.class, properties::validate);

        properties = new RagApiKeyExpiryAlertProperties();
        properties.setFallbackScanInterval(Duration.ofMinutes(9));
        assertThrows(IllegalArgumentException.class, properties::validate);
    }

    @Test
    void rejectsUnboundedScanAndRetryValues() {
        RagApiKeyExpiryAlertProperties properties =
                new RagApiKeyExpiryAlertProperties();
        properties.setFallbackScanLimit(99);
        assertThrows(IllegalArgumentException.class, properties::validate);

        properties = new RagApiKeyExpiryAlertProperties();
        properties.setEventRetryAttempts(11);
        assertThrows(IllegalArgumentException.class, properties::validate);
    }
}

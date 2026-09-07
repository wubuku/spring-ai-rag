package com.springairag.core.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** API Key 供给账本配置：保留期范围校验、批次与重试钳制。 */
class RagApiKeyProvisioningPropertiesTest {

    @Test
    void defaultsMatchTheDocumentedBaseline() {
        RagApiKeyProvisioningProperties properties = new RagApiKeyProvisioningProperties();

        assertTrue(properties.isEnabled());
        assertEquals(Duration.ofDays(400), properties.getRetention());
        assertEquals(500, properties.getCleanupBatchSize());
        assertEquals(3, properties.getConcurrentRetryAttempts());
    }

    @Test
    void acceptsTheInclusiveRetentionBoundaries() {
        RagApiKeyProvisioningProperties properties = new RagApiKeyProvisioningProperties();

        properties.setRetention(Duration.ofDays(7));
        assertEquals(Duration.ofDays(7), properties.getRetention());

        properties.setRetention(Duration.ofDays(3650));
        assertEquals(Duration.ofDays(3650), properties.getRetention());
    }

    @Test
    void rejectsRetentionOutsideTheSupportedRange() {
        RagApiKeyProvisioningProperties properties = new RagApiKeyProvisioningProperties();

        IllegalArgumentException tooShort = assertThrows(IllegalArgumentException.class,
                () -> properties.setRetention(Duration.ofDays(6)));
        assertTrue(tooShort.getMessage().contains("between 7 and 3650 days"));

        IllegalArgumentException tooLong = assertThrows(IllegalArgumentException.class,
                () -> properties.setRetention(Duration.ofDays(3651)));
        assertTrue(tooLong.getMessage().contains("between 7 and 3650 days"));

        IllegalArgumentException nullRetention = assertThrows(IllegalArgumentException.class,
                () -> properties.setRetention(null));
        assertTrue(nullRetention.getMessage().contains("between 7 and 3650 days"));
    }

    @Test
    void clampsCleanupBatchSizeIntoTheSupportedRange() {
        RagApiKeyProvisioningProperties properties = new RagApiKeyProvisioningProperties();

        properties.setCleanupBatchSize(0);
        assertEquals(10, properties.getCleanupBatchSize());

        properties.setCleanupBatchSize(99_999);
        assertEquals(5_000, properties.getCleanupBatchSize());
    }

    @Test
    void clampsConcurrentRetryAttemptsIntoTheSupportedRange() {
        RagApiKeyProvisioningProperties properties = new RagApiKeyProvisioningProperties();

        properties.setConcurrentRetryAttempts(0);
        assertEquals(1, properties.getConcurrentRetryAttempts());

        properties.setConcurrentRetryAttempts(99);
        assertEquals(8, properties.getConcurrentRetryAttempts());
    }
}

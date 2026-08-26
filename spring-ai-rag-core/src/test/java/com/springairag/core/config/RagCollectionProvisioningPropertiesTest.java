package com.springairag.core.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagCollectionProvisioningPropertiesTest {

    @Test
    void defaultsAndClampsAreBounded() {
        RagCollectionProvisioningProperties properties =
                new RagCollectionProvisioningProperties();

        assertTrue(properties.isEnabled());
        assertEquals(Duration.ofDays(400), properties.getRetention());
        assertEquals(500, properties.getCleanupBatchSize());
        assertEquals(3_600_000L, properties.getCleanupIntervalMs());
        assertEquals(3, properties.getConcurrentRetryAttempts());

        properties.setCleanupBatchSize(1);
        properties.setCleanupIntervalMs(1);
        properties.setConcurrentRetryAttempts(0);
        assertEquals(10, properties.getCleanupBatchSize());
        assertEquals(10_000L, properties.getCleanupIntervalMs());
        assertEquals(1, properties.getConcurrentRetryAttempts());

        properties.setCleanupBatchSize(10_000);
        properties.setCleanupIntervalMs(100_000_000L);
        properties.setConcurrentRetryAttempts(20);
        assertEquals(5000, properties.getCleanupBatchSize());
        assertEquals(86_400_000L, properties.getCleanupIntervalMs());
        assertEquals(8, properties.getConcurrentRetryAttempts());
    }

    @Test
    void retentionRejectsOutOfRangeValues() {
        RagCollectionProvisioningProperties properties =
                new RagCollectionProvisioningProperties();

        assertThrows(IllegalArgumentException.class,
                () -> properties.setRetention(Duration.ofDays(6)));
        assertThrows(IllegalArgumentException.class,
                () -> properties.setRetention(Duration.ofDays(3651)));
        assertThrows(IllegalArgumentException.class,
                () -> properties.setRetention(null));
    }
}

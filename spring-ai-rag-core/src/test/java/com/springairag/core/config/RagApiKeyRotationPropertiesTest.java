package com.springairag.core.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RagApiKeyRotationPropertiesTest {

    @Test
    void defaultsExposeExactCapabilityUnits() {
        RagApiKeyRotationProperties properties =
                new RagApiKeyRotationProperties();

        assertEquals(900, properties.defaultOverlapSeconds());
        assertEquals(3600, properties.maxOverlapSeconds());
        assertEquals(400, properties.operationRetentionDays());
        assertEquals(500, properties.getCleanupBatchSize());
    }

    @Test
    void rejectsFractionalUnitsAndOutOfRangeLimits() {
        RagApiKeyRotationProperties properties =
                new RagApiKeyRotationProperties();

        assertThrows(IllegalArgumentException.class, () ->
                properties.setDefaultOverlap(Duration.ofMillis(1500)));
        assertThrows(IllegalArgumentException.class, () ->
                properties.setMaxOverlap(Duration.ofHours(25)));
        assertThrows(IllegalArgumentException.class, () ->
                properties.setOperationRetention(Duration.ofHours(25)));
        assertThrows(IllegalArgumentException.class, () ->
                properties.setCleanupBatchSize(9));
    }

    @Test
    void rejectsMaxBelowConfiguredDefault() {
        RagApiKeyRotationProperties properties =
                new RagApiKeyRotationProperties();

        assertThrows(IllegalArgumentException.class, () ->
                properties.setMaxOverlap(Duration.ofMinutes(10)));
    }
}

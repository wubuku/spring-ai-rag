package com.springairag.core.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * API key 轮换配置校验臂长尾（Batch 741，JaCoCo 驱动）：各 setter
 * 的独立校验臂——defaultOverlap 超过 maxOverlap、maxOverlap 越下
 * 界、operationRetention 非整天/越界、cleanupInterval 越界。
 */
class RagApiKeyRotationPropertiesArmsTailTest {

    @Test
    void defaultOverlapGreaterThanMaxOverlapIsRejected() {
        RagApiKeyRotationProperties properties =
                new RagApiKeyRotationProperties();

        // maxOverlap 保持默认 1 小时：2 小时的 defaultOverlap 越上界。
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> properties.setDefaultOverlap(Duration.ofHours(2)));
        assertEquals("Default overlap must be positive and no greater than max overlap",
                error.getMessage());
    }

    @Test
    void defaultOverlapWholeSecondsWithinMaxIsAccepted() {
        RagApiKeyRotationProperties properties =
                new RagApiKeyRotationProperties();

        properties.setDefaultOverlap(Duration.ofSeconds(30));

        assertEquals(Duration.ofSeconds(30), properties.getDefaultOverlap());
    }

    @Test
    void maxOverlapBelowOneSecondIsRejected() {
        RagApiKeyRotationProperties properties =
                new RagApiKeyRotationProperties();

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> properties.setMaxOverlap(Duration.ZERO));
        assertEquals("Max overlap must be between 1 second and 24 hours and cover the default",
                error.getMessage());
    }

    @Test
    void operationRetentionRejectsNullAndNonWholeDays() {
        RagApiKeyRotationProperties properties =
                new RagApiKeyRotationProperties();

        assertThrows(IllegalArgumentException.class,
                () -> properties.setOperationRetention(null));
        assertThrows(IllegalArgumentException.class,
                () -> properties.setOperationRetention(Duration.ofHours(25)));
        assertThrows(IllegalArgumentException.class,
                () -> properties.setOperationRetention(Duration.ofDays(3)));
        assertThrows(IllegalArgumentException.class,
                () -> properties.setOperationRetention(Duration.ofDays(4000)));
    }

    @Test
    void operationRetentionAcceptsWholeDayBoundaries() {
        RagApiKeyRotationProperties properties =
                new RagApiKeyRotationProperties();

        properties.setOperationRetention(Duration.ofDays(7));
        assertEquals(Duration.ofDays(7), properties.getOperationRetention());

        properties.setOperationRetention(Duration.ofDays(3650));
        assertEquals(Duration.ofDays(3650), properties.getOperationRetention());
    }

    @Test
    void cleanupIntervalRejectsOutOfBoundsValues() {
        RagApiKeyRotationProperties properties =
                new RagApiKeyRotationProperties();

        assertThrows(IllegalArgumentException.class,
                () -> properties.setCleanupIntervalMs(500));
        assertThrows(IllegalArgumentException.class,
                () -> properties.setCleanupIntervalMs(90_000_000L));
    }

    @Test
    void cleanupIntervalAcceptsBoundaries() {
        RagApiKeyRotationProperties properties =
                new RagApiKeyRotationProperties();

        properties.setCleanupIntervalMs(1_000L);
        assertEquals(1_000L, properties.getCleanupIntervalMs());

        properties.setCleanupIntervalMs(86_400_000L);
        assertEquals(86_400_000L, properties.getCleanupIntervalMs());
    }

    @Test
    void capabilityUnitAccessorsExposeConfiguredValues() {
        RagApiKeyRotationProperties properties =
                new RagApiKeyRotationProperties();
        properties.setDefaultOverlap(Duration.ofMinutes(20));
        properties.setMaxOverlap(Duration.ofHours(2));
        properties.setOperationRetention(Duration.ofDays(30));

        assertEquals(20 * 60, properties.defaultOverlapSeconds());
        assertEquals(2 * 3_600, properties.maxOverlapSeconds());
        assertEquals(30, properties.operationRetentionDays());
    }
}

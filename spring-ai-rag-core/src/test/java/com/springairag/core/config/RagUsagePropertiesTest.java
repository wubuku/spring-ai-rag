package com.springairag.core.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖 durable 用量台账属性校验：保留/清理/记录器/超时的边界约束。
 */
class RagUsagePropertiesTest {

    private RagUsageProperties props = new RagUsageProperties();

    @Test
    void acceptsSaneDefaults() {
        assertDoesNotThrow(props::validate);
    }

    @Test
    void rejectsRetentionOutsideThirtyDaysToTenYears() {
        props.setRetentionDays(29);
        assertThrows(IllegalArgumentException.class, props::validate);

        props.setRetentionDays(3_651);
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class, props::validate);
        assertTrue(error.getMessage().contains("retention-days"));
    }

    @Test
    void rejectsCleanupBatchSizeOutsideBounds() {
        props.setCleanupBatchSize(99);
        assertThrows(IllegalArgumentException.class, props::validate);

        props.setCleanupBatchSize(10_001);
        assertThrows(IllegalArgumentException.class, props::validate);
    }

    @Test
    void rejectsCleanupMaxBatchesOutsideOneToHundred() {
        props.setCleanupMaxBatches(0);
        assertThrows(IllegalArgumentException.class, props::validate);

        props.setCleanupMaxBatches(101);
        assertThrows(IllegalArgumentException.class, props::validate);
    }

    @Test
    void rejectsRecorderThreadsOutsideOneToSixteen() {
        props.setRecorderThreads(0);
        assertThrows(IllegalArgumentException.class, props::validate);

        props.setRecorderThreads(17);
        assertThrows(IllegalArgumentException.class, props::validate);
    }

    @Test
    void rejectsRecorderQueueCapacityOutsideBounds() {
        props.setRecorderQueueCapacity(99);
        assertThrows(IllegalArgumentException.class, props::validate);

        props.setRecorderQueueCapacity(10_001);
        assertThrows(IllegalArgumentException.class, props::validate);
    }

    @Test
    void rejectsRecordTimeoutOutsideHundredMsToTenSeconds() {
        props.setRecordTimeoutMs(99);
        assertThrows(IllegalArgumentException.class, props::validate);

        props.setRecordTimeoutMs(10_001);
        assertThrows(IllegalArgumentException.class, props::validate);
    }

    @Test
    void acceptsBoundaryValues() {
        props.setRetentionDays(30);
        props.setCleanupBatchSize(100);
        props.setCleanupMaxBatches(1);
        props.setRecorderThreads(1);
        props.setRecorderQueueCapacity(100);
        props.setRecordTimeoutMs(100);

        assertDoesNotThrow(props::validate);
    }
}

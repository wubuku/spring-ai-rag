package com.springairag.core.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * RagUsageProperties 属性存取长尾（Batch 679，JaCoCo 驱动）：
 * costUnit / cleanupCron 存取与非法 batch size / max batches 校
 * 验。
 */
class RagUsagePropertiesTailTest {

    @Test
    void costUnitRoundTrips() {
        var properties = new RagUsageProperties();
        properties.setCostUnit("USD");

        assertEquals("USD", properties.getCostUnit());
    }

    @Test
    void cleanupCronRoundTrips() {
        var properties = new RagUsageProperties();
        properties.setCleanupCron("0 0 * * * *");

        assertEquals("0 0 * * * *", properties.getCleanupCron());
    }

    @Test
    void cleanupBatchSizeSetterRoundTrips() {
        var properties = new RagUsageProperties();
        properties.setCleanupBatchSize(500);
        assertEquals(500, properties.getCleanupBatchSize());
    }

    @Test
    void recordTimeoutMsValidation() {
        var properties = new RagUsageProperties();
        properties.setRecordTimeoutMs(50);
        assertThrows(IllegalArgumentException.class, properties::validate);
    }

    @Test
    void recordTimeoutMsValidPasses() {
        var properties = new RagUsageProperties();
        properties.setRecordTimeoutMs(2000);
        properties.validate();
    }
}

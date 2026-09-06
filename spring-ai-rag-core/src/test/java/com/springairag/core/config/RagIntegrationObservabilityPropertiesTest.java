package com.springairag.core.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖集成观测属性校验：保留期与查询范围的范围/整日约束、
 * 查询范围不得超过保留期、以及各容量参数的正数下限。
 */
class RagIntegrationObservabilityPropertiesTest {

    private RagIntegrationObservabilityProperties props =
            new RagIntegrationObservabilityProperties();

    @Test
    void acceptsSaneDefaults() {
        assertDoesNotThrow(props::validate);
    }

    @Test
    void rejectsRetentionBelowSevenDaysOrAboveTwoYears() {
        props.setRetention(Duration.ofDays(6));
        assertThrows(IllegalArgumentException.class, props::validate);

        props = new RagIntegrationObservabilityProperties();
        props.setRetention(Duration.ofDays(731));
        assertThrows(IllegalArgumentException.class, props::validate);
    }

    @Test
    void rejectsNonWholeDayRetention() {
        props.setRetention(Duration.ofHours(36));
        assertThrows(IllegalArgumentException.class, props::validate);
    }

    @Test
    void rejectsMaxQueryRangeOutsideOneToNinetyDays() {
        props.setMaxQueryRange(Duration.ofHours(12));
        assertThrows(IllegalArgumentException.class, props::validate);

        props = new RagIntegrationObservabilityProperties();
        props.setMaxQueryRange(Duration.ofDays(91));
        assertThrows(IllegalArgumentException.class, props::validate);
    }

    @Test
    void rejectsQueryRangeExceedingRetention() {
        props.setRetention(Duration.ofDays(7));
        props.setMaxQueryRange(Duration.ofDays(8));
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class, props::validate);
        assertTrue(error.getMessage().contains("must not exceed retention"));
    }

    @Test
    void rejectsNonPositiveCapacityValues() {
        props.setQueueCapacity(0);
        assertThrows(IllegalArgumentException.class, props::validate);

        props = new RagIntegrationObservabilityProperties();
        props.setFlushBatchSize(-1);
        assertThrows(IllegalArgumentException.class, props::validate);
    }
}

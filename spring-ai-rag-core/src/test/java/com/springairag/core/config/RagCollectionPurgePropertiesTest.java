package com.springairag.core.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Collection 永久清理安全边界：默认基线、时间窗依赖链与行数上限。 */
class RagCollectionPurgePropertiesTest {

    private RagCollectionPurgeProperties properties;

    @BeforeEach
    void setUp() {
        properties = new RagCollectionPurgeProperties();
    }

    @Test
    void defaultsPassValidation() {
        properties.validate();
    }

    @Test
    void documentsTheDefaultSafetyBaseline() {
        assertEquals(Duration.ofMinutes(15), properties.getConfirmationWindow());
        assertEquals(Duration.ofHours(1), properties.getOperationWindow());
        assertEquals(Duration.ofHours(24), properties.getResultRetention());
        assertEquals(Duration.ofMinutes(2), properties.getApplyLease());
        assertEquals(20, properties.getMaxActivePreviewsPerOwner());
        assertEquals(500, properties.getCleanupBatchSize());
        assertEquals(10_000, properties.getMaxDocuments());
        assertEquals(100_000, properties.getMaxEmbeddings());
        assertEquals(100_000, properties.getMaxVersions());
        assertEquals(250_000, properties.getMaxDerivedRows());
        assertEquals(1_000, properties.getMaxAffectedChatSessions());
        assertEquals(50_000, properties.getMaxChatRows());
    }

    @Test
    void rejectsConfirmationWindowBelowOneMinute() {
        properties.setConfirmationWindow(Duration.ofSeconds(30));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                properties::validate);
        assertTrue(error.getMessage().contains("confirmation-window"));
    }

    @Test
    void rejectsOperationWindowShorterThanTheConfirmationWindow() {
        properties.setOperationWindow(Duration.ofMinutes(5));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                properties::validate);
        assertTrue(error.getMessage().contains("operation-window"));
    }

    @Test
    void rejectsResultRetentionShorterThanTheOperationWindow() {
        properties.setResultRetention(Duration.ofMinutes(30));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                properties::validate);
        assertTrue(error.getMessage().contains("result-retention"));
    }

    @Test
    void rejectsApplyLeaseOutsideItsRange() {
        properties.setApplyLease(Duration.ofSeconds(5));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                properties::validate);
        assertTrue(error.getMessage().contains("apply-lease"));
    }

    @Test
    void rejectsOutOfRangeRowLimits() {
        properties.setMaxDocuments(0);
        IllegalArgumentException documents = assertThrows(
                IllegalArgumentException.class, properties::validate);
        assertTrue(documents.getMessage().contains("max-documents"));

        properties.setMaxDocuments(10_000);
        properties.setMaxChatRows(600_000);
        IllegalArgumentException chatRows = assertThrows(
                IllegalArgumentException.class, properties::validate);
        assertTrue(chatRows.getMessage().contains("max-chat-rows"));
    }

    @Test
    void rejectsOutOfRangeCleanupSettings() {
        properties.setCleanupBatchSize(5);
        IllegalArgumentException batch = assertThrows(
                IllegalArgumentException.class, properties::validate);
        assertTrue(batch.getMessage().contains("cleanup-batch-size"));

        properties.setCleanupBatchSize(500);
        properties.setCleanupInterval(Duration.ofSeconds(30));
        IllegalArgumentException interval = assertThrows(
                IllegalArgumentException.class, properties::validate);
        assertTrue(interval.getMessage().contains("cleanup-interval"));
    }
}

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
        assertEquals(Duration.ofSeconds(60), properties.getAsyncPreviewDeadline());
        assertEquals(Duration.ofSeconds(10), properties.getAsyncPreviewStatementTimeout());
        assertEquals(Duration.ofSeconds(180), properties.getAsyncPreviewAdmissionLease());
        assertEquals(1_000_000, properties.getAsyncPreviewMaxDocuments());
        assertEquals(10_000_000, properties.getAsyncPreviewMaxDerivedRows());
        assertEquals(Duration.ofSeconds(120), properties.getAsyncOperationLease());
        assertEquals(Duration.ofSeconds(20), properties.getAsyncOperationHeartbeat());
        assertEquals(Duration.ofSeconds(60), properties.getAsyncBatchDeadline());
        assertEquals(Duration.ofSeconds(30), properties.getAsyncBatchStatementTimeout());
        assertEquals(500, properties.getAsyncBatchMaxDocuments());
        assertEquals(5_000, properties.getAsyncBatchMaxRows());
        assertEquals(2, properties.getAsyncWorkerConcurrency());
        assertEquals(5, properties.getAsyncMaxCoordinatorAttempts());
        assertEquals(5_000, properties.getAsyncManifestBatchSize());
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

    @Test
    void asyncPreviewLeaseMustCoverDeadlineAndStatementCleanup() {
        properties.setAsyncPreviewDeadline(Duration.ofSeconds(120));
        properties.setAsyncPreviewAdmissionLease(Duration.ofSeconds(150));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class, properties::validate);

        assertTrue(error.getMessage().contains("async-preview-admission-lease"));
    }

    @Test
    void asyncPreviewLimitsCannotExceedHardBounds() {
        properties.setAsyncPreviewMaxDocuments(1_000_001);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class, properties::validate);

        assertTrue(error.getMessage().contains("async preview scan limits"));
    }

    @Test
    void asyncWorkerLeaseAndBatchLimitsAreBounded() {
        properties.setAsyncOperationLease(Duration.ofSeconds(75));
        IllegalArgumentException lease = assertThrows(
                IllegalArgumentException.class, properties::validate);
        assertTrue(lease.getMessage().contains("async-operation-lease"));

        properties.setAsyncOperationLease(Duration.ofSeconds(120));
        properties.setAsyncOperationHeartbeat(Duration.ofSeconds(41));
        IllegalArgumentException heartbeat = assertThrows(
                IllegalArgumentException.class, properties::validate);
        assertTrue(heartbeat.getMessage().contains("async-operation-heartbeat"));

        properties.setAsyncOperationHeartbeat(Duration.ofSeconds(20));
        properties.setAsyncBatchMaxRows(10_001);
        IllegalArgumentException rows = assertThrows(
                IllegalArgumentException.class, properties::validate);
        assertTrue(rows.getMessage().contains("async-batch-max-rows"));

        properties.setAsyncBatchMaxRows(5_000);
        properties.setAsyncManifestBatchSize(5_001);
        IllegalArgumentException manifest = assertThrows(
                IllegalArgumentException.class, properties::validate);
        assertTrue(manifest.getMessage().contains("async-manifest-batch-size"));
    }
}

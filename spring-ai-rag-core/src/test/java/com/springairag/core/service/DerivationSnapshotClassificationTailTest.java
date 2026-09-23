package com.springairag.core.service;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 派生完整性快照分类矩阵长尾（Batch 600，JaCoCo 驱动）：Snapshot.from
 * 对本地/向量行完整性、新鲜度、收敛任务、禁用与墓碑的组合判定，
 * 条件与原因码投影，以及 toResponse 的建议动作与错误截断。
 */
class DerivationSnapshotClassificationTailTest {

    private static final String HASH =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    private Map<String, Object> baseRow() {
        Map<String, Object> row = new HashMap<>();
        row.put("id", 7L);
        row.put("title", "Doc");
        row.put("version", 4L);
        row.put("document_revision", 2L);
        row.put("content_hash", HASH);
        row.put("enabled", true);
        row.put("source_deleted", false);
        row.put("source_namespace", "default");
        row.put("external_id", "doc-1");
        row.put("expected_chunker", "c1");
        row.put("local_status", "READY");
        row.put("local_hash", HASH);
        row.put("local_chunker", "c1");
        row.put("local_generation", 1L);
        row.put("local_expected", 3);
        row.put("local_actual", 3);
        row.put("local_distinct", 3);
        row.put("local_min", 0L);
        row.put("local_max", 2L);
        row.put("local_invalid", 0);
        row.put("local_error", null);
        row.put("vector_status", "COMPLETED");
        row.put("vector_hash", HASH);
        row.put("vector_chunker", "c1");
        row.put("vector_generation", 1L);
        row.put("vector_expected", 3);
        row.put("vector_actual", 3);
        row.put("vector_distinct", 3);
        row.put("vector_min", 0L);
        row.put("vector_max", 2L);
        row.put("invalid_vectors", 0);
        row.put("local_mismatches", 0);
        row.put("vector_error", null);
        row.put("active_job_id", null);
        row.put("active_job_status", null);
        return row;
    }

    private DerivationIntegrityRepository.Snapshot snapshot(
            Map<String, Object> row) {
        return DerivationIntegrityRepository.Snapshot.from(row);
    }

    @Test
    void fullyConsistentRowClassifiesReady() {
        var snapshot = snapshot(baseRow());

        assertEquals("READY", snapshot.bucket());
        assertEquals("READY", snapshot.localCondition());
        assertEquals("READY", snapshot.vectorCondition());
        assertEquals("CURRENT", snapshot.reasonCode());
        assertTrue(snapshot.localFresh());
        assertTrue(snapshot.vectorFresh());
    }

    @Test
    void disabledAndTombstonedRowsBucketAsDisabled() {
        Map<String, Object> disabled = baseRow();
        disabled.put("enabled", false);
        assertEquals("DISABLED", snapshot(disabled).bucket());

        Map<String, Object> tombstoned = baseRow();
        tombstoned.put("source_deleted", true);
        assertEquals("DISABLED", snapshot(tombstoned).bucket());
        assertTrue(snapshot(tombstoned).tombstoned());
    }

    @Test
    void incompleteLocalRowsArePhysicalCorruption() {
        Map<String, Object> row = baseRow();
        row.put("local_actual", 2);

        var snapshot = snapshot(row);
        assertEquals("CORRUPT", snapshot.bucket());
        assertFalse(snapshot.localFresh());
        assertTrue(snapshot.localCorrupt());
        assertEquals("LOCAL_PHYSICAL_INTEGRITY_FAILED", snapshot.reasonCode());
    }

    @Test
    void staleVectorHashIsPhysicalCorruption() {
        Map<String, Object> row = baseRow();
        row.put("vector_hash", "stale-hash");

        var snapshot = snapshot(row);
        assertEquals("CORRUPT", snapshot.bucket());
        assertTrue(snapshot.vectorCorrupt());
        assertEquals("VECTOR_PHYSICAL_INTEGRITY_FAILED", snapshot.reasonCode());
    }

    @Test
    void freshLocalWithMissingVectorBucketsKeywordOnly() {
        Map<String, Object> row = baseRow();
        row.put("vector_status", null);

        var snapshot = snapshot(row);
        assertEquals("KEYWORD_ONLY", snapshot.bucket());
        assertEquals("NOT_REQUESTED", snapshot.vectorCondition());
        assertEquals("VECTOR_NOT_REQUESTED", snapshot.reasonCode());
    }

    @Test
    void convergingRunningJobBucketsIndexing() {
        Map<String, Object> row = baseRow();
        row.put("local_status", "PENDING");
        row.put("vector_status", "RUNNING");
        row.put("vector_actual", 1);
        row.put("active_job_status", "RUNNING");
        row.put("active_job_id", "6f9619ff-8b86-d011-b42d-00c04fc964ff");

        var snapshot = snapshot(row);
        assertEquals("INDEXING", snapshot.bucket());
        assertEquals("INDEXING", snapshot.vectorCondition());
        assertEquals(
                UUID.fromString("6f9619ff-8b86-d011-b42d-00c04fc964ff"),
                snapshot.activeJobId());
    }

    @Test
    void notRequestedBothSidesBucketsNotRequested() {
        Map<String, Object> row = baseRow();
        row.put("local_status", "NOT_REQUESTED");
        row.put("vector_status", "NOT_REQUESTED");
        row.put("local_hash", null);
        row.put("local_generation", 0L);

        var snapshot = snapshot(row);
        assertEquals("NOT_REQUESTED", snapshot.bucket());
        assertEquals("NOT_REQUESTED", snapshot.localCondition());
        assertEquals("NOT_REQUESTED", snapshot.vectorCondition());
    }

    @Test
    void failedLocalStatusBucketsLocalUnavailable() {
        Map<String, Object> row = baseRow();
        row.put("local_status", "FAILED");
        row.put("local_error", "boom");
        row.put("vector_status", "FAILED");

        var snapshot = snapshot(row);
        assertEquals("LOCAL_UNAVAILABLE", snapshot.bucket());
        assertEquals("FAILED", snapshot.localCondition());
        assertEquals("LOCAL_FAILED", snapshot.reasonCode());
    }

    @Test
    void staleVectorWithoutJobBucketsStaleWithVectorReason() {
        Map<String, Object> row = baseRow();
        row.put("local_status", "PENDING");
        row.put("vector_status", "RUNNING");
        row.put("vector_chunker", "c0");

        var snapshot = snapshot(row);
        assertEquals("LOCAL_UNAVAILABLE", snapshot.bucket());
        assertEquals("STALE", snapshot.localCondition());
        assertEquals("STALE", snapshot.vectorCondition());
        assertEquals("LOCAL_STALE", snapshot.reasonCode());
    }

    @Test
    void toResponseAddsActionsAndTruncatesLongErrors() {
        Map<String, Object> row = baseRow();
        row.put("local_actual", 2);
        row.put("local_error", "x".repeat(600));

        var response = snapshot(row).toResponse();

        assertTrue(response.repairable());
        assertTrue(response.recommendedActions().contains("REBUILD_LOCAL"));
        assertTrue(response.recommendedActions().contains("QUEUE_VECTOR"));
        assertEquals(500, response.error().length());
    }

    @Test
    void readySnapshotRequiresNoAction() {
        var response = snapshot(baseRow()).toResponse();

        assertEquals(false, response.repairable());
        assertTrue(response.recommendedActions().isEmpty());
    }

    @Test
    void missingFactoryProducesDocumentMissingSnapshot() {
        var snapshot = DerivationIntegrityRepository.Snapshot.missing(42L);

        assertEquals(42L, snapshot.documentId());
        assertEquals("DISABLED", snapshot.bucket());
        assertEquals("MISSING", snapshot.localCondition());
        assertEquals("DOCUMENT_MISSING", snapshot.reasonCode());
    }
}

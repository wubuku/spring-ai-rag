package com.springairag.core.service;

import com.springairag.core.service.DerivationIntegrityRepository.Snapshot;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖派生完整性快照的分类矩阵：从台账行派生 local/vector 新鲜度、
 * 损坏检测、桶归类与原因码。
 *
 * <p>from(Map) 为包级私有静态工厂，测试放同包直接驱动。</p>
 */
class DerivationIntegritySnapshotFromTest {

    private Map<String, Object> baseRow() {
        Map<String, Object> row = new HashMap<>();
        row.put("id", 1L);
        row.put("title", "Doc");
        row.put("version", 3L);
        row.put("document_revision", 2L);
        row.put("content_hash", "h-1");
        row.put("enabled", true);
        row.put("tombstoned", false);
        row.put("expected_chunker", "v1");
        row.put("local_status", "READY");
        row.put("local_hash", "h-1");
        row.put("local_chunker", "v1");
        row.put("local_generation", 2L);
        row.put("local_expected", 3);
        row.put("local_actual", 3);
        row.put("local_distinct", 3);
        row.put("local_min", 0);
        row.put("local_max", 2);
        row.put("local_invalid", 0);
        row.put("vector_status", "COMPLETED");
        row.put("vector_hash", "h-1");
        row.put("vector_chunker", "v1");
        row.put("vector_generation", 2L);
        row.put("vector_expected", 3);
        row.put("vector_actual", 3);
        row.put("vector_distinct", 3);
        row.put("vector_min", 0);
        row.put("vector_max", 2);
        row.put("invalid_vectors", 0);
        row.put("local_mismatches", 0);
        return row;
    }

    private Snapshot fromRow(Map<String, Object> overrides) {
        Map<String, Object> row = baseRow();
        row.putAll(overrides);
        return Snapshot.from(row);
    }

    @Test
    void fullyFreshLocalAndVectorClassifiesReady() {
        Snapshot snapshot = fromRow(Map.of());

        assertEquals("READY", snapshot.bucket());
        assertTrue(snapshot.localFresh());
        assertTrue(snapshot.vectorFresh());
        assertEquals("CURRENT", snapshot.reasonCode());
    }

    @Test
    void classifiesCorruptWhenLocalRowsInvalid() {
        Snapshot snapshot = fromRow(Map.of("local_invalid", 1));

        assertEquals("CORRUPT", snapshot.bucket());
        assertTrue(snapshot.localCorrupt());
        assertEquals("LOCAL_PHYSICAL_INTEGRITY_FAILED", snapshot.reasonCode());
    }

    @Test
    void classifiesKeywordOnlyWhenLocalFreshButVectorFailed() {
        // 本地新鲜、向量 FAILED（非 COMPLETED 不算 CORRUPT）→ KEYWORD_ONLY。
        Snapshot snapshot = fromRow(Map.of(
                "vector_status", "FAILED",
                "vector_hash", "old-hash"));

        assertEquals("KEYWORD_ONLY", snapshot.bucket());
        assertTrue(snapshot.localFresh());
        assertFalse(snapshot.vectorFresh());
        assertEquals("VECTOR_FAILED", snapshot.reasonCode());
    }

    @Test
    void classifiesDisabledWhenDocumentDisabled() {
        Snapshot snapshot = fromRow(Map.of("enabled", false));

        assertEquals("DISABLED", snapshot.bucket());
    }

    @Test
    void classifiesNotRequestedWhenNeitherDerived() {
        Map<String, Object> row = baseRow();
        row.put("local_status", "NOT_REQUESTED");
        row.put("vector_status", "NOT_REQUESTED");
        Snapshot snapshot = fromRow(row);

        assertEquals("NOT_REQUESTED", snapshot.bucket());
    }

    @Test
    void classifiesIndexingWhenJobConverging() {
        // 向量行数不完整（2/3）但作业正在收敛 → INDEXING。
        Map<String, Object> row = baseRow();
        row.put("active_job_status", "RUNNING");
        // 向量未完成且不满足新鲜条件；local 也未 READY，
        // 作业在收敛 → INDEXING（KEYWORD_ONLY/READY 优先级更高）。
        row.put("local_status", "FAILED");
        row.put("vector_actual", 2);
        row.put("vector_distinct", 2);
        row.put("vector_max", 1);
        row.put("vector_status", "RUNNING");
        Snapshot snapshot = fromRow(row);

        assertEquals("INDEXING", snapshot.bucket());
    }

    @Test
    void reasonCodesPreferLocalCorruptionOverVector() {
        Map<String, Object> row = baseRow();
        row.put("local_invalid", 1);
        row.put("vector_status", "FAILED");
        Snapshot snapshot = fromRow(row);

        assertEquals("LOCAL_PHYSICAL_INTEGRITY_FAILED", snapshot.reasonCode());
    }
}

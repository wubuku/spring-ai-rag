package com.springairag.core.service;

import com.springairag.core.config.EmbeddingProfile;
import com.springairag.core.config.RagProperties;
import com.springairag.core.retrieval.EmbeddingBatchService;
import com.springairag.documents.chunk.TextChunk;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 覆盖 profile 级 embedding 持久化：缓存判定（完整性快照短路与
 * metadata/行数双查）、内容哈希初始化 CAS、原子替换的提交门与
 * 版本围栏、失败记录的脱敏截断与静默跳过。
 */
class EmbeddingPersistenceServiceTest {

    private static final EmbeddingProfile PROFILE = new EmbeddingProfile(
            7L, "bge-m3", "openai", "bge-m3", "r1", 1024,
            "cosine", "normalized", true);

    /** What {@code DocumentDerivationDescriptorProvider} derives for text. */
    private static final String TEXT_CHUNKER_VERSION = "hierarchical-v2:1000:100:100";
    /** Any type other than {@code json-record} derives the text descriptor. */
    private static final String TEXT_DOCUMENT_TYPE = "text";
    /** ...and for a JSON record, which takes a different branch entirely. */
    private static final String JSON_CHUNKER_VERSION = "json-record-v1:single";

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final EmbeddingPersistenceService service =
            new EmbeddingPersistenceService(jdbc);

    @Test
    void cacheMissesWhenStateRowAbsent() {
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of());

        var state = service.findCacheState(1L, TEXT_DOCUMENT_TYPE, PROFILE, "h1");

        assertEquals(new EmbeddingPersistenceService.CacheState(false, 0), state);
    }

    @Test
    void cacheMissesOnMetadataMismatch() {
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(stateRow("FAILED", "h1", TEXT_CHUNKER_VERSION, 3)));

        assertEquals(false, service.findCacheState(1L, TEXT_DOCUMENT_TYPE, PROFILE, "h1").hit());

        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(stateRow("COMPLETED", "stale", TEXT_CHUNKER_VERSION, 3)));

        assertEquals(false, service.findCacheState(1L, TEXT_DOCUMENT_TYPE, PROFILE, "h1").hit());

        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(stateRow("COMPLETED", "h1", "other", 3)));

        assertEquals(false, service.findCacheState(1L, TEXT_DOCUMENT_TYPE, PROFILE, "h1").hit());

        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(stateRow("COMPLETED", "h1", TEXT_CHUNKER_VERSION, 0)));

        assertEquals(false, service.findCacheState(1L, TEXT_DOCUMENT_TYPE, PROFILE, "h1").hit());
        verify(jdbc, never()).queryForObject(anyString(), eq(Long.class), any(Object[].class));
    }

    @Test
    void cacheMissesWhenVectorRowsIncomplete() {
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(stateRow("COMPLETED", "h1", TEXT_CHUNKER_VERSION, 3)));
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
                .thenReturn(2L);

        var state = service.findCacheState(1L, TEXT_DOCUMENT_TYPE, PROFILE, "h1");

        assertEquals(false, state.hit());
    }

    @Test
    void cacheHitsWhenVectorRowsMatchChunkCount() {
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(stateRow("COMPLETED", "h1", TEXT_CHUNKER_VERSION, 3)));
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
                .thenReturn(3L);

        var state = service.findCacheState(1L, TEXT_DOCUMENT_TYPE, PROFILE, "h1");

        assertEquals(new EmbeddingPersistenceService.CacheState(true, 3), state);
    }

    @Test
    void integritySnapshotShortCircuitsJdbcLookups() {
        DerivationIntegrityRepository integrity =
                mock(DerivationIntegrityRepository.class);
        service.setIntegrityRepository(integrity);
        when(integrity.inspect(1L)).thenReturn(snapshot(true));

        var state = service.findCacheState(1L, TEXT_DOCUMENT_TYPE, PROFILE, "h1");

        assertEquals(new EmbeddingPersistenceService.CacheState(true, 5), state);
        verify(jdbc, never()).queryForList(anyString(), any(Object[].class));

        when(integrity.inspect(1L)).thenReturn(snapshot(false));

        assertEquals(false, service.findCacheState(1L, TEXT_DOCUMENT_TYPE, PROFILE, "h1").hit());
    }

    @Test
    void ensureContentHashRejectsStaleVersion() {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);

        assertThrows(IllegalStateException.class,
                () -> service.ensureContentHash(1L, 3L, "h1"));
    }

    @Test
    void replaceRejectsWhenCommitGuardFails() {
        EmbeddingCommitGuard guard = mock(EmbeddingCommitGuard.class);
        doThrow(new IllegalStateException("lease lost")).when(guard).verify();

        assertThrows(IllegalStateException.class,
                () -> service.replace(1L, 3L, "h3", PROFILE,
                        List.of(new TextChunk("alpha", 0, 5)),
                        List.of(result("alpha", new float[] {0.1f})),
                        guard));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void replaceRejectsOnDocumentMutation() {
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(documentRow(4L, "h3", true)));

        assertThrows(IllegalStateException.class,
                () -> service.replace(1L, 3L, "h3", PROFILE,
                        List.of(new TextChunk("alpha", 0, 5)),
                        List.of(result("alpha", new float[] {0.1f})),
                        EmbeddingCommitGuard.allowAll()));

        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(documentRow(3L, "h3", false)));

        assertThrows(IllegalStateException.class,
                () -> service.replace(1L, 3L, "h3", PROFILE,
                        List.of(new TextChunk("alpha", 0, 5)),
                        List.of(result("alpha", new float[] {0.1f})),
                        EmbeddingCommitGuard.allowAll()));

        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(documentRow(3L, "stale", true)));

        assertThrows(IllegalStateException.class,
                () -> service.replace(1L, 3L, "h3", PROFILE,
                        List.of(new TextChunk("alpha", 0, 5)),
                        List.of(result("alpha", new float[] {0.1f})),
                        EmbeddingCommitGuard.allowAll()));
    }

    @Test
    void replaceWritesRowsAndCommitsAtomically() {
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(documentRow(3L, "h3", true)));
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        service.replace(
                1L, 3L, "h3", PROFILE,
                List.of(new TextChunk("alpha", 0, 5), new TextChunk("beta", 6, 10)),
                List.of(result("alpha", new float[] {0.1f, 0.2f}),
                        result("beta", new float[] {0.3f})),
                EmbeddingCommitGuard.allowAll());

        verify(jdbc).update(contains("DELETE FROM rag_embeddings"),
                eq(1L), eq(7L));
        verify(jdbc).update(contains("INSERT INTO rag_embeddings"),
                eq(1L), eq("alpha"), eq(0),
                eq("[0.1,0.2]"), eq("[0.1,0.2]"), eq(7L), eq(0), eq(5));
        verify(jdbc).update(contains("INSERT INTO rag_embeddings"),
                eq(1L), eq("beta"), eq(1),
                eq("[0.3]"), eq("[0.3]"), eq(7L), eq(6), eq(10));
        // The chunker version is no longer passed in, so the assertion can no
        // longer name whatever the caller happened to type: it has to equal the
        // value the retrieval scope will filter on, derived from the document.
        verify(jdbc).update(contains("rag_document_embedding_state"),
                eq(1L), eq(7L), eq("h3"), eq(TEXT_CHUNKER_VERSION), eq(2));
        verify(jdbc).update(contains("processing_status = 'COMPLETED'"),
                eq("h3"), eq(1L), eq(3L));
    }

    @Test
    void replaceRecordsTheChunkerVersionTheDocumentTypeImplies() {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(documentRow(3L, "h3", true, "document")));
        service.replace(1L, 3L, "h3", PROFILE,
                List.of(new TextChunk("alpha", 0, 5)),
                List.of(result("alpha", new float[] {0.1f})),
                EmbeddingCommitGuard.allowAll());
        verify(jdbc).update(contains("rag_document_embedding_state"),
                eq(1L), eq(7L), eq("h3"), eq(TEXT_CHUNKER_VERSION), eq(1));

        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(documentRow(3L, "h3", true, "json-record")));
        service.replace(1L, 3L, "h3", PROFILE,
                List.of(new TextChunk("alpha", 0, 5)),
                List.of(result("alpha", new float[] {0.1f})),
                EmbeddingCommitGuard.allowAll());
        verify(jdbc).update(contains("rag_document_embedding_state"),
                eq(1L), eq(7L), eq("h3"), eq(JSON_CHUNKER_VERSION), eq(1));
    }

    @Test
    void replaceTakesItsChunkerVersionFromTheSameProviderTheRetrievalScopeUses() {
        RagProperties tuned = new RagProperties();
        tuned.getChunk().setDefaultChunkSize(512);
        tuned.getChunk().setMinChunkSize(64);
        tuned.getChunk().setDefaultChunkOverlap(32);
        EmbeddingPersistenceService tunedService = new EmbeddingPersistenceService(
                jdbc, new DocumentDerivationDescriptorProvider(tuned));

        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(documentRow(3L, "h3", true)));
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        tunedService.replace(1L, 3L, "h3", PROFILE,
                List.of(new TextChunk("alpha", 0, 5)),
                List.of(result("alpha", new float[] {0.1f})),
                EmbeddingCommitGuard.allowAll());

        // Same provider, same answer. Before this change the caller supplied
        // this string and two tests in this repository each supplied a wrong
        // one; now the tuned configuration reaches the write path with no
        // threading at all.
        String expected = new DocumentDerivationDescriptorProvider(tuned)
                .textDescriptor()
                .chunkerVersion();
        assertEquals("hierarchical-v2:512:64:32", expected);
        verify(jdbc).update(contains("rag_document_embedding_state"),
                eq(1L), eq(7L), eq("h3"), eq(expected), eq(1));
    }

    @Test
    void replaceRejectsWhenFinalCasFails() {
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(documentRow(3L, "h3", true)));
        when(jdbc.update(anyString(), any(Object[].class)))
                // DELETE、INSERT、state upsert 各成功一次，最终文档 CAS 失败
                .thenReturn(1, 1, 1, 0);

        assertThrows(IllegalStateException.class,
                () -> service.replace(
                        1L, 3L, "h3", PROFILE,
                        List.of(new TextChunk("alpha", 0, 5)),
                        List.of(result("alpha", new float[] {0.1f})),
                        EmbeddingCommitGuard.allowAll()));
    }

    @Test
    void recordFailureSkipsWhenDocumentChanged() {
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(documentRow(9L, "h3", true)));

        service.recordFailureIfNoCompleted(1L, 3L, "h3", PROFILE, TEXT_DOCUMENT_TYPE, "boom");

        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void recordFailureWritesDefaultErrorForBlankInput() {
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(documentRow(3L, "h3", true)));
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        service.recordFailureIfNoCompleted(1L, 3L, "h3", PROFILE, TEXT_DOCUMENT_TYPE, "  ");

        verify(jdbc).update(contains("rag_document_embedding_state"),
                eq(1L), eq(7L), eq("h3"), eq(TEXT_CHUNKER_VERSION), eq("Embedding failed"));
        verify(jdbc).update(contains("processing_status = 'FAILED'"),
                eq("Embedding failed"), eq(1L), eq(3L));
    }

    @Test
    void recordFailureMasksAndTruncatesSensitiveError() {
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(documentRow(3L, "h3", true)));
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        String sensitive = "failed with password=hunter2 "
                + "and token=eyJhbGciOiJIUzI1NiJ9.secret.part "
                + "detail ".repeat(80);

        service.recordFailureIfNoCompleted(1L, 3L, "h3", PROFILE, TEXT_DOCUMENT_TYPE, sensitive);

        verify(jdbc).update(contains("rag_document_embedding_state"),
                eq(1L), eq(7L), eq("h3"), eq(TEXT_CHUNKER_VERSION),
                ArgumentMatchers.<String>argThat(error -> error != null
                        && error.length() <= 500
                        && !error.contains("hunter2")));
    }

    @Test
    void recordFailureRejectsWhenFinalCasFails() {
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(documentRow(3L, "h3", true)));
        when(jdbc.update(anyString(), any(Object[].class)))
                .thenReturn(1, 0);

        assertThrows(IllegalStateException.class,
                () -> service.recordFailureIfNoCompleted(
                        1L, 3L, "h3", PROFILE, TEXT_DOCUMENT_TYPE, "boom"));
    }

    private EmbeddingBatchService.EmbeddingResult result(
            String text, float[] embedding) {
        return new EmbeddingBatchService.EmbeddingResult(text, embedding, null);
    }

    private Map<String, Object> stateRow(
            String status, String hash, String chunker, int chunkCount) {
        return Map.of(
                "status", status,
                "content_hash", hash,
                "chunker_version", chunker,
                "chunk_count", chunkCount);
    }

    /**
     * {@code document_type} is now part of the snapshot the service reads,
     * because that is what it derives the recorded chunker version from.
     */
    private Map<String, Object> documentRow(
            long version, String hash, boolean enabled) {
        return documentRow(version, hash, enabled, "document");
    }

    private Map<String, Object> documentRow(
            long version, String hash, boolean enabled, String documentType) {
        return Map.of(
                "version", version,
                "content_hash", hash,
                "enabled", enabled,
                "document_type", documentType);
    }

    private DerivationIntegrityRepository.Snapshot snapshot(boolean vectorFresh) {
        return new DerivationIntegrityRepository.Snapshot(
                1L, "title", 1L, 1L, "h1", true, false,
                null, null, null,
                null, null, null, 0L, 0, 0, null, false, false,
                null, null, null, 0L, 5, 5, null, null, null,
                vectorFresh, false,
                null, null, null, null);
    }
}

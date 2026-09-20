package com.springairag.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.core.config.EmbeddingProfile;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.api.dto.DocumentLifecycleResponse;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.RagDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DocumentLifecycleService 派生长尾（Batch 543，JaCoCo 驱动）：
 * 本地 NOT_REQUESTED 且嵌入在场 → FAILED、双缺席 → NOT_REQUESTED、
 * 未知嵌入态 → NOT_REQUESTED、job RUNNING → INDEXING、本地就绪但
 * 嵌入失败 → KEYWORD_ONLY、integrity 快照的桶位/条件映射与错误回
 * 退、profileProvider 异常返回 null、uuid 静态工具四分支。
 */
class DocumentLifecycleServiceDeriveTailTest {

    private static final long DOC_ID = 42L;
    private static final String HASH = "hash-1";
    private static final String CHUNKER = "hierarchical-v2:1000:100:100";

    private JdbcTemplate jdbcTemplate;
    private EmbeddingProfileProvider profileProvider;
    private DocumentLifecycleService service;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        profileProvider = mock(EmbeddingProfileProvider.class);
        when(profileProvider.getActiveProfile()).thenReturn(new EmbeddingProfile(
                PROFILE_ID, "bge-m3", "siliconflow", "BAAI/bge-m3", null,
                1024, "cosine", "none", true));
        service = new DocumentLifecycleService(
                jdbcTemplate,
                profileProvider,
                new DocumentDerivationDescriptorProvider(new RagProperties()));
    }

    private static final long PROFILE_ID = 7L;

    private RagDocument document(Boolean enabled) {
        RagDocument document = new RagDocument();
        document.setId(DOC_ID);
        document.setContentHash(HASH);
        document.setEnabled(enabled);
        document.setDocumentType("TEXT");
        return document;
    }

    private void stubRow(Map<String, Object> row) {
        when(jdbcTemplate.queryForList(any(String.class), any(Object[].class)))
                .thenReturn(List.of(row));
    }

    private Map<String, Object> readyRow() {
        Map<String, Object> row = new HashMap<>();
        row.put("local_index_status", "READY");
        row.put("local_content_hash", HASH);
        row.put("local_chunker_version", CHUNKER);
        row.put("local_index_generation", 1);
        row.put("local_chunk_count", 2);
        row.put("local_actual_chunk_count", 2);
        row.put("local_error", null);
        row.put("embedding_status", "COMPLETED");
        row.put("embedding_content_hash", HASH);
        row.put("embedding_chunker_version", CHUNKER);
        row.put("embedding_chunk_count", 2);
        row.put("active_job_id", null);
        row.put("embedding_error", null);
        row.put("job_status", null);
        row.put("job_error", null);
        return row;
    }

    @Test
    void localNotRequestedWithEmbeddingPresentYieldsFailedLocal() {
        Map<String, Object> row = readyRow();
        row.put("local_index_status", "NOT_REQUESTED");
        stubRow(row);

        DocumentLifecycleResponse response = service.read(document(true));

        assertEquals("FAILED", response.localIndexStatus());
        assertEquals("FAILED", response.searchability());
    }

    @Test
    void absentStatesYieldNotRequestedEverywhere() {
        stubRow(new HashMap<>());

        DocumentLifecycleResponse response = service.read(document(true));

        assertEquals("NOT_REQUESTED", response.localIndexStatus());
        assertEquals("NOT_REQUESTED", response.embeddingStatus());
        assertEquals("NOT_REQUESTED", response.searchability());
    }

    @Test
    void runningJobKeepsEmbeddingIndexing() {
        Map<String, Object> row = readyRow();
        row.put("embedding_status", "QUEUED");
        row.put("job_status", "RUNNING");
        stubRow(row);

        DocumentLifecycleResponse response = service.read(document(true));

        assertEquals("INDEXING", response.embeddingStatus());
        // 本地已就绪时检索性优先取 KEYWORD_ONLY，而非 INDEXING。
        assertEquals("KEYWORD_ONLY", response.searchability());
    }

    @Test
    void unknownEmbeddingStateYieldsNotRequested() {
        Map<String, Object> row = readyRow();
        row.put("embedding_status", "WEIRD_STATE");
        stubRow(row);

        DocumentLifecycleResponse response = service.read(document(true));

        assertEquals("NOT_REQUESTED", response.embeddingStatus());
    }

    @Test
    void localReadyWithFailedEmbeddingIsKeywordOnly() {
        Map<String, Object> row = readyRow();
        row.put("embedding_status", "FAILED");
        row.put("embedding_error", "provider down");
        stubRow(row);

        DocumentLifecycleResponse response = service.read(document(true));

        assertEquals("KEYWORD_ONLY", response.searchability());
        assertEquals("EMBEDDING_FAILED", response.lastErrorCode());
        assertEquals("provider down", response.lastError());
    }

    private DerivationIntegrityRepository.Snapshot snapshot(
            String bucket, String localCondition, String vectorCondition,
            boolean localFresh, boolean vectorFresh, String vectorError) {
        return new DerivationIntegrityRepository.Snapshot(
                DOC_ID, "doc", 1L, 1L, HASH, true, false,
                "default", null, CHUNKER,
                localFresh ? "READY" : "STALE", HASH, CHUNKER, 1L, 2, 2,
                null, localFresh, false,
                "COMPLETED", HASH, CHUNKER, 1L, 2, 2,
                vectorError, null, "COMPLETED", vectorFresh, false,
                bucket, localCondition, vectorCondition, "check-doc");
    }

    @Test
    void integrityNotRequestedBucketMapsStraightThrough() {
        var integrity = mock(DerivationIntegrityRepository.class);
        service.setIntegrityRepository(integrity);
        when(integrity.inspect(any(RagDocument.class))).thenReturn(snapshot(
                "NOT_REQUESTED", "NOT_REQUESTED", "NOT_REQUESTED",
                false, false, null));

        DocumentLifecycleResponse response = service.read(document(true));

        assertEquals("NOT_REQUESTED", response.searchability());
        assertEquals("NOT_REQUESTED", response.localIndexStatus());
        assertEquals("NOT_REQUESTED", response.embeddingStatus());
    }

    @Test
    void integrityIndexingVectorConditionMapsToIndexing() {
        var integrity = mock(DerivationIntegrityRepository.class);
        service.setIntegrityRepository(integrity);
        when(integrity.inspect(any(RagDocument.class))).thenReturn(snapshot(
                "INDEXING", "READY", "INDEXING",
                true, false, null));

        DocumentLifecycleResponse response = service.read(document(true));

        assertEquals("INDEXING", response.searchability());
        assertEquals("READY", response.localIndexStatus());
        assertEquals("INDEXING", response.embeddingStatus());
    }

    @Test
    void integrityVectorErrorSurfacesWhenLocalErrorAbsent() {
        var integrity = mock(DerivationIntegrityRepository.class);
        service.setIntegrityRepository(integrity);
        when(integrity.inspect(any(RagDocument.class))).thenReturn(snapshot(
                "KEYWORD_ONLY", "READY", "FAILED",
                true, false, "vector exploded"));

        DocumentLifecycleResponse response = service.read(document(true));

        assertEquals("KEYWORD_ONLY", response.searchability());
        assertEquals("vector exploded", response.lastError());
    }

    @Test
    void activeProfileKeyReturnsNullWhenProviderThrows() {
        when(profileProvider.getActiveProfile())
                .thenThrow(new IllegalStateException("profile gone"));
        var integrity = mock(DerivationIntegrityRepository.class);
        service.setIntegrityRepository(integrity);
        when(integrity.inspect(any(RagDocument.class))).thenReturn(snapshot(
                "READY", "READY", "READY", true, true, null));

        DocumentLifecycleResponse response = service.read(document(true));

        assertNull(response.activeEmbeddingProfileKey());
        assertEquals("READY", response.searchability());
    }

    @Test
    void uuidHelperHandlesInstanceStringNullAndGarbage() throws Exception {
        var method = DocumentLifecycleService.class
                .getDeclaredMethod("uuid", Object.class);
        method.setAccessible(true);

        UUID id = UUID.randomUUID();
        assertEquals(id, method.invoke(null, id));
        assertNull(method.invoke(null, new Object[]{null}));
        assertEquals(id, method.invoke(null, id.toString()));
        assertNull(method.invoke(null, "not-a-uuid"));
    }

}

package com.springairag.core.service;

import com.springairag.api.dto.DocumentLifecycleResponse;
import com.springairag.core.config.EmbeddingProfile;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.RagDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link DocumentLifecycleService#read} 的护栏单测：锁定 SQL 回退路径的
 * 状态推导矩阵（local/embedding current 判定、searchability 归一、错误码）
 * 与 integrity 路径的分派，为后续行为保持拆分提供回归护栏。
 */
class DocumentLifecycleServiceTest {

    private static final long DOC_ID = 42L;
    private static final long PROFILE_ID = 9L;
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
        Map<String, Object> row = new java.util.HashMap<>();
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
    void disabledDocumentIsReportedDisabledOrTombstoned() {
        DocumentLifecycleResponse disabled =
                service.read(document(false));
        assertEquals("DISABLED", disabled.documentState());
        assertEquals("DISABLED", disabled.searchability());
        assertFalse(disabled.retryable());

        RagDocument tombstoned = document(false);
        tombstoned.setSourceDeletedAt(LocalDateTime.now());
        assertEquals("TOMBSTONED", service.read(tombstoned).documentState());
    }

    @Test
    void missingStateRowsYieldNotRequested() {
        when(jdbcTemplate.queryForList(any(String.class), any(Object[].class)))
                .thenReturn(List.of());

        DocumentLifecycleResponse response =
                service.read(document(true));

        assertEquals("ACTIVE", response.documentState());
        assertEquals("NOT_REQUESTED", response.searchability());
        assertEquals("NOT_REQUESTED", response.localIndexStatus());
        assertEquals("NOT_REQUESTED", response.embeddingStatus());
        assertTrue(response.retryable());
        assertEquals("bge-m3", response.activeEmbeddingProfileKey());
    }

    @Test
    void fullyCurrentDerivationsYieldReadyWithoutErrors() {
        stubRow(readyRow());

        DocumentLifecycleResponse response = service.read(document(true));

        assertEquals("READY", response.searchability());
        assertEquals("READY", response.localIndexStatus());
        assertEquals("READY", response.embeddingStatus());
        assertNull(response.lastErrorCode());
        assertFalse(response.retryable());
    }

    @Test
    void readyLocalWithQueuedEmbeddingYieldsKeywordOnly() {
        Map<String, Object> row = new java.util.HashMap<>(readyRow());
        row.put("embedding_status", "QUEUED");
        row.put("embedding_content_hash", HASH);
        stubRow(row);

        DocumentLifecycleResponse response = service.read(document(true));

        assertEquals("KEYWORD_ONLY", response.searchability());
        assertEquals("READY", response.localIndexStatus());
        assertEquals("INDEXING", response.embeddingStatus());
    }

    @Test
    void failedEmbeddingSurfacesEmbeddingFailedErrorCode() {
        Map<String, Object> row = new java.util.HashMap<>(readyRow());
        row.put("embedding_status", "FAILED");
        row.put("embedding_error", "provider exploded");
        stubRow(row);

        DocumentLifecycleResponse response = service.read(document(true));

        assertEquals("FAILED", response.embeddingStatus());
        assertEquals("KEYWORD_ONLY", response.searchability());
        assertEquals("EMBEDDING_FAILED", response.lastErrorCode());
        assertEquals("provider exploded", response.lastError());
        assertTrue(response.retryable());
    }

    @Test
    void localErrorWithNotReadyLocalIndexReportsLocalIndexFailed() {
        Map<String, Object> row = new java.util.HashMap<>(readyRow());
        row.put("local_index_status", "FAILED");
        row.put("local_error", "chunker blew up");
        row.put("embedding_status", "NOT_REQUESTED");
        stubRow(row);

        DocumentLifecycleResponse response = service.read(document(true));

        assertEquals("FAILED", response.localIndexStatus());
        assertEquals("LOCAL_INDEX_FAILED", response.lastErrorCode());
        assertEquals("chunker blew up", response.lastError());
    }

    @Test
    void integrityRepositoryPresenceRoutesThroughFromIntegrity() {
        DerivationIntegrityRepository integrity =
                mock(DerivationIntegrityRepository.class);
        service.setIntegrityRepository(integrity);
        DerivationIntegrityRepository.Snapshot snapshot =
                new DerivationIntegrityRepository.Snapshot(
                        DOC_ID, "doc", 1L, 1L, HASH, true, false,
                        "default", null, CHUNKER,
                        "READY", HASH, CHUNKER, 1L, 2, 2,
                        null, true, false,
                        "COMPLETED", HASH, CHUNKER, 1L, 2, 2,
                        null, null, "COMPLETED", true, false,
                        "READY", "READY", "READY", null);
        when(integrity.inspect(any(RagDocument.class))).thenReturn(snapshot);

        DocumentLifecycleResponse response = service.read(document(true));

        assertEquals("READY", response.searchability());
        assertEquals("READY", response.localIndexStatus());
        assertEquals("READY", response.embeddingStatus());
        assertNull(response.lastErrorCode());
    }
}

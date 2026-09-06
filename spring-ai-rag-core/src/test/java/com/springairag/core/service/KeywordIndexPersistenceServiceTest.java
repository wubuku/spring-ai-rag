package com.springairag.core.service;

import com.springairag.core.entity.RagDocument;
import com.springairag.documents.chunk.TextChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 覆盖本地关键词索引持久化协调器：新鲜度判定（完整性快照短路与
 * JDBC 双查）、内容哈希 CAS 初始化、REBUILD 全流程、SKIP 落地与
 * 错误脱敏。
 */
class KeywordIndexPersistenceServiceTest {

    private JdbcTemplate jdbcTemplate;
    private DocumentChunkingService chunkingService;
    private DocumentDerivationDescriptorProvider descriptorProvider;
    private KeywordIndexPersistenceService service;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        chunkingService = mock(DocumentChunkingService.class);
        descriptorProvider = mock(DocumentDerivationDescriptorProvider.class);
        service = new KeywordIndexPersistenceService(
                jdbcTemplate, chunkingService, descriptorProvider);
        when(descriptorProvider.describe(any()))
                .thenReturn(new DocumentDerivationDescriptorProvider.Descriptor(
                        "TEXT", "v1"));
    }

    private RagDocument document() {
        RagDocument doc = new RagDocument();
        doc.setId(1L);
        doc.setEnabled(true);
        doc.setContentHash("hash-1");
        doc.setContent("alpha body");
        doc.setVersion(3L);
        return doc;
    }

    private void stubPreparedChunks() {
        when(chunkingService.prepare(any())).thenReturn(
                new DocumentChunkingService.PreparedChunks(
                        new DocumentDerivationDescriptorProvider.Descriptor(
                                "TEXT", "v1"),
                        List.of(new TextChunk("alpha", 0, 5))));
    }

    @Test
    void ensureCurrentRejectsMissingIdentityAndDisabledDocument() {
        assertThrows(IllegalArgumentException.class, () -> service.ensureCurrent(null));

        RagDocument noId = new RagDocument();
        assertThrows(IllegalArgumentException.class, () -> service.ensureCurrent(noId));

        RagDocument disabled = document();
        disabled.setEnabled(false);
        assertThrows(IllegalStateException.class, () -> service.ensureCurrent(disabled));
    }

    @Test
    void hasFreshLocalIndexReturnsFalseWithoutUsableIdentity() {
        RagDocument noHash = document();
        noHash.setContentHash(" ");

        assertFalse(service.hasFreshLocalIndex(new RagDocument()));
        assertFalse(service.hasFreshLocalIndex(noHash));
        verify(jdbcTemplate, org.mockito.Mockito.never())
                .queryForList(anyString(), any(Object[].class));
    }

    @Test
    void hasFreshLocalIndexShortCircuitsThroughIntegritySnapshot() {
        DerivationIntegrityRepository integrity =
                mock(DerivationIntegrityRepository.class);
        service.setIntegrityRepository(integrity);
        RagDocument doc = document();
        when(integrity.inspect(doc)).thenReturn(
                mock(DerivationIntegrityRepository.Snapshot.class));
        when(integrity.inspect(doc).localFresh()).thenReturn(true);

        assertTrue(service.hasFreshLocalIndex(doc));
        verify(jdbcTemplate, org.mockito.Mockito.never())
                .queryForList(anyString(), any(Object[].class));
    }

    @Test
    void hasFreshLocalIndexComparesChunkCountWithState() {
        RagDocument doc = document();
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(Map.of(
                        "local_index_generation", 7L,
                        "chunk_count", 3)));

        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class),
                any(Object[].class))).thenReturn(2L);
        assertFalse(service.hasFreshLocalIndex(doc));

        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class),
                any(Object[].class))).thenReturn(3L);
        assertTrue(service.hasFreshLocalIndex(doc));
    }

    @Test
    void hasFreshLocalIndexReturnsFalseWhenStateRowMissing() {
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of());

        assertFalse(service.hasFreshLocalIndex(document()));
    }

    @Test
    void ensureContentHashReusesExistingHashWithoutDatabaseWrite() {
        RagDocument doc = document();

        assertEquals("hash-1", service.ensureContentHash(doc));
        verify(jdbcTemplate, org.mockito.Mockito.never())
                .update(anyString(), any(Object[].class));
    }

    @Test
    void ensureContentHashComputesAndCAsStringValues() {
        RagDocument doc = document();
        doc.setContentHash(null);
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

        String hash = service.ensureContentHash(doc);

        assertEquals(64, hash.length());
        assertEquals(hash, doc.getContentHash());
        assertEquals(4L, doc.getVersion());
    }

    @Test
    void ensureContentHashRejectsWhenDocumentChanged() {
        RagDocument doc = document();
        doc.setContentHash(null);
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(0);

        assertThrows(IllegalStateException.class, () -> service.ensureContentHash(doc));
    }

    @Test
    void ensureCurrentRunsAllocateDeleteInsertAndStateCas() {
        stubPreparedChunks();
        // allocateGeneration RETURNING 命中一次；hasFreshLocalIndex 行集为空。
        when(jdbcTemplate.query(anyString(), any(PreparedStatementSetter.class),
                any(RowMapper.class))).thenReturn(List.of(42L));
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of());
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

        service.ensureCurrent(document());

        verify(jdbcTemplate).update(contains("DELETE FROM rag_document_chunks"),
                eq(1L));
        verify(jdbcTemplate).batchUpdate(contains("INSERT INTO rag_document_chunks"),
                anyList(), eq(100), any());
        verify(jdbcTemplate).update(contains("local_index_status = 'READY'"),
                eq("hash-1"), eq("v1"), eq(42L), eq(1), eq(1L), eq(42L),
                eq(1L), eq("hash-1"));
    }

    @Test
    void ensureCurrentRejectsWhenStateCasMisses() {
        stubPreparedChunks();
        when(jdbcTemplate.query(anyString(), any(PreparedStatementSetter.class),
                any(RowMapper.class))).thenReturn(List.of(42L));
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of());
        when(jdbcTemplate.update(anyString(), any(Object[].class)))
                .thenReturn(1, 0);

        assertThrows(IllegalStateException.class,
                () -> service.ensureCurrent(document()));
    }

    @Test
    void markNotRequestedAllocatesGenerationAndRetiresChunks() {
        when(jdbcTemplate.query(anyString(), any(PreparedStatementSetter.class),
                any(RowMapper.class))).thenReturn(List.of(9L));
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

        service.markNotRequested(document());

        verify(jdbcTemplate).update(contains("DELETE FROM rag_document_chunks"),
                eq(1L));
    }

    @Test
    void markNotRequestedRejectsWhenStateCasMisses() {
        when(jdbcTemplate.query(anyString(), any(PreparedStatementSetter.class),
                any(RowMapper.class))).thenReturn(List.of(9L));
        when(jdbcTemplate.update(anyString(), any(Object[].class)))
                .thenReturn(1, 0);

        assertThrows(IllegalStateException.class,
                () -> service.markNotRequested(document()));
    }

    @Test
    void sanitizeErrorMasksTruncatesAndDefaults() {
        assertEquals("Local keyword index failed", service.sanitizeError(null));
        assertEquals("Local keyword index failed", service.sanitizeError("  "));
        assertEquals("clean failure", service.sanitizeError("clean failure"));
        String longError = "password=hunter2 " + "detail ".repeat(200);
        String sanitized = service.sanitizeError(longError);
        assertTrue(sanitized.length() <= 500);
        assertTrue(!sanitized.contains("hunter2"));
    }
}

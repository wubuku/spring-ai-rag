package com.springairag.core.service;

import com.springairag.core.entity.RagDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * isCurrent 新鲜度判定（经 public hasFreshLocalIndex 驱动，
 * Batch 339）：状态行缺失、chunk 计数一致/不一致、计数为 null、
 * 数据访问异常均判定；文档基础字段缺失短路。
 */
class KeywordIndexFreshnessTest {

    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
    private DocumentDerivationDescriptorProvider descriptorProvider;
    private KeywordIndexPersistenceService service;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(org.springframework.jdbc.core.JdbcTemplate.class);
        var chunkingService = mock(DocumentChunkingService.class);
        descriptorProvider = mock(DocumentDerivationDescriptorProvider.class);
        when(descriptorProvider.describe(any()))
                .thenReturn(new DocumentDerivationDescriptorProvider.Descriptor(
                        "TEXT", "v1"));
        service = new KeywordIndexPersistenceService(
                jdbcTemplate, chunkingService, descriptorProvider);
    }

    private RagDocument document(String contentHash) {
        RagDocument document = new RagDocument();
        document.setId(1L);
        document.setContentHash(contentHash);
        return document;
    }

    private void stubStateRow(int chunkCount, long generation) {
        when(jdbcTemplate.queryForList(
                contains("rag_document_local_index_state"),
                any(Object[].class)))
                .thenReturn(List.of(Map.of(
                        "local_index_generation", generation,
                        "chunk_count", chunkCount)));
    }

    private void stubChunkCount(Long count) {
        when(jdbcTemplate.queryForObject(
                contains("COUNT(*)"), eq(Long.class), any(Object[].class)))
                .thenReturn(count);
    }

    @Test
    void stateRowWithMatchingChunkCountIsFresh() {
        stubStateRow(3, 5L);
        stubChunkCount(3L);

        assertEquals(true, service.hasFreshLocalIndex(document("hash-1")));
    }

    @Test
    void missingStateRowIsNotFresh() {
        when(jdbcTemplate.queryForList(
                contains("rag_document_local_index_state"),
                any(Object[].class))).thenReturn(List.of());

        assertFalse(service.hasFreshLocalIndex(document("hash-1")));
    }

    @Test
    void chunkCountMismatchIsNotFresh() {
        stubStateRow(3, 5L);
        stubChunkCount(2L);

        assertFalse(service.hasFreshLocalIndex(document("hash-1")));
    }

    @Test
    void nullChunkCountIsNotFresh() {
        stubStateRow(3, 5L);
        stubChunkCount(null);

        assertFalse(service.hasFreshLocalIndex(document("hash-1")));
    }

    @Test
    void dataAccessFailureIsNotFresh() {
        when(jdbcTemplate.queryForList(
                contains("rag_document_local_index_state"),
                any(Object[].class)))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        assertFalse(service.hasFreshLocalIndex(document("hash-1")));
    }

    @Test
    void incompleteDocumentIdentityIsNotFresh() {
        assertFalse(service.hasFreshLocalIndex(null));
        assertFalse(service.hasFreshLocalIndex(new RagDocument()));
        assertFalse(service.hasFreshLocalIndex(document("  ")));
    }
}

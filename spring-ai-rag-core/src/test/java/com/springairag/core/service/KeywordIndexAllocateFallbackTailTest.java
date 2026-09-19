package com.springairag.core.service;

import com.springairag.core.entity.RagDocument;
import com.springairag.documents.chunk.TextChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * KeywordIndexPersistenceService allocateGeneration 回退长尾（Batch
 * 519，JaCoCo 驱动）：首次 UPDATE...RETURNING 未命中时走 INSERT 兜
 * 底 + 二次 UPDATE，两次都未命中抛文档变更冲突；fresh 索引命中时
 * ensureCurrent 提前返回。
 */
class KeywordIndexAllocateFallbackTailTest {

    private JdbcTemplate jdbcTemplate;
    private DocumentChunkingService chunkingService;
    private DocumentDerivationDescriptorProvider descriptorProvider;
    private KeywordIndexPersistenceService service;

    private static final DocumentDerivationDescriptorProvider.Descriptor
            DESCRIPTOR = new DocumentDerivationDescriptorProvider.Descriptor(
            "TEXT", "v1");

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        chunkingService = mock(DocumentChunkingService.class);
        descriptorProvider = mock(DocumentDerivationDescriptorProvider.class);
        when(descriptorProvider.describe(any())).thenReturn(DESCRIPTOR);
        service = new KeywordIndexPersistenceService(
                jdbcTemplate, chunkingService, descriptorProvider);
        Mockito.lenient().when(jdbcTemplate.update(
                        contains("DELETE FROM rag_document_chunks"),
                        eq(1L)))
                .thenReturn(1);
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

    private void stubPrepare() {
        when(chunkingService.prepare(any())).thenReturn(
                new DocumentChunkingService.PreparedChunks(
                        DESCRIPTOR,
                        List.of(new TextChunk("alpha", 0, 5))));
    }

    @SuppressWarnings("unchecked")
    private void stubReturningAnswers(List<Long> first, List<Long> second) {
        when(jdbcTemplate.query(
                contains("RETURNING state.local_index_generation"),
                any(PreparedStatementSetter.class), any(RowMapper.class)))
                .thenAnswer(invocation -> first)
                .thenAnswer(invocation -> second);
    }

    @Test
    void allocateGenerationFallsBackToInsertThenSecondUpdate() {
        stubPrepare();
        stubReturningAnswers(List.of(), List.of(7L));
        when(jdbcTemplate.update(
                contains("INSERT INTO rag_document_local_index_state"),
                any(Object[].class)))
                .thenReturn(1);
        // chunk 写入后的 READY CAS 必须命中，否则报 preparing 冲突。
        when(jdbcTemplate.update(
                contains("SET local_index_status"),
                any(Object[].class)))
                .thenReturn(1);

        service.ensureCurrent(document());

        // 兜底 INSERT 与二次 UPDATE 都已执行。
        verify(jdbcTemplate).update(
                contains("INSERT INTO rag_document_local_index_state"),
                any(Object[].class));
        verify(jdbcTemplate).update(
                contains("SET local_index_status"),
                any(Object[].class));
    }

    @Test
    void allocateGenerationThrowsWhenDocumentChangedMidAllocation() {
        stubPrepare();
        stubReturningAnswers(List.of(), List.of());

        var error = assertThrows(IllegalStateException.class,
                () -> service.ensureCurrent(document()));

        assertTrue(error.getMessage().startsWith(
                "Document changed while allocating local index generation"));
        // 未进入 chunk 写入阶段。
        verify(jdbcTemplate, never()).update(
                contains("DELETE FROM rag_document_chunks"), eq(1L));
    }

    @Test
    void ensureCurrentReturnsEarlyWhenFreshIndexExists() {
        stubPrepare();
        // hasFreshLocalIndex：状态行 READY + chunk 计数一致即 fresh。
        when(jdbcTemplate.queryForList(
                contains("FROM rag_document_local_index_state"),
                any(Object[].class)))
                .thenReturn(List.of(Map.<String, Object>of(
                        "local_index_generation", 5L,
                        "chunk_count", 2)));
        when(jdbcTemplate.queryForObject(
                contains("SELECT COUNT(*)"),
                eq(Long.class), any(Object[].class)))
                .thenReturn(2L);

        service.ensureCurrent(document());

        // fresh 命中：既不分配代次也不重建 chunk。
        verify(jdbcTemplate, never()).query(
                contains("RETURNING state.local_index_generation"),
                any(PreparedStatementSetter.class), any(RowMapper.class));
        verify(jdbcTemplate, never()).update(
                contains("DELETE FROM rag_document_chunks"), eq(1L));
    }
}

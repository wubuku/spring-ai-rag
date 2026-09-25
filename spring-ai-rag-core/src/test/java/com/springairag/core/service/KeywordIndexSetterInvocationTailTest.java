package com.springairag.core.service;

import com.springairag.core.entity.RagDocument;
import com.springairag.documents.chunk.TextChunk;
import com.springairag.core.rag.KnowledgeSearchTool;
import com.springairag.core.rag.RetrievalDocumentMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.RowMapper;

import java.sql.PreparedStatement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * KeywordIndexPersistenceService 语句参数装配长尾（Batch 651，
 * JaCoCo 驱动）：本地索引代际分配的两条条件 UPDATE 的
 * PreparedStatementSetter 真实执行（首查命中 / 兜底二次更新），
 * 参数按占位符顺序装配。
 */
class KeywordIndexSetterInvocationTailTest {

    private static final DocumentDerivationDescriptorProvider.Descriptor
            DESCRIPTOR = new DocumentDerivationDescriptorProvider.Descriptor(
            "TEXT", "v1");

    private JdbcTemplate jdbcTemplate;
    private DocumentChunkingService chunkingService;
    private DocumentDerivationDescriptorProvider descriptorProvider;
    private KeywordIndexPersistenceService service;
    private PreparedStatement statement;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        chunkingService = mock(DocumentChunkingService.class);
        descriptorProvider = mock(DocumentDerivationDescriptorProvider.class);
        when(descriptorProvider.describe(any())).thenReturn(DESCRIPTOR);
        statement = mock(PreparedStatement.class);
        service = new KeywordIndexPersistenceService(
                jdbcTemplate, chunkingService, descriptorProvider);
        Mockito.lenient().when(jdbcTemplate.update(
                        contains("DELETE FROM rag_document_chunks"),
                        eq(1L)))
                .thenReturn(1);
        Mockito.lenient().when(chunkingService.prepare(any())).thenReturn(
                new DocumentChunkingService.PreparedChunks(
                        DESCRIPTOR,
                        List.of(new TextChunk("alpha", 0, 5))));
        Mockito.lenient().when(jdbcTemplate.update(
                contains("SET local_index_status"),
                any(Object[].class)))
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

    /** 让被桩化的 query 真实执行 PreparedStatementSetter 后返回答案。 */
    @SuppressWarnings("unchecked")
    private void stubReturningWithSetterInvocation(List<Long> first,
                                                   List<Long> second) {
        when(jdbcTemplate.query(
                contains("RETURNING state.local_index_generation"),
                any(PreparedStatementSetter.class), any(RowMapper.class)))
                .thenAnswer(invocation -> {
                    ((PreparedStatementSetter) invocation.getArgument(1))
                            .setValues(statement);
                    return first;
                })
                .thenAnswer(invocation -> {
                    ((PreparedStatementSetter) invocation.getArgument(1))
                            .setValues(statement);
                    return second;
                });
    }

    @Test
    @SuppressWarnings("unchecked")
    void firstUpdateHitAssemblesSetterParameters() throws Exception {
        when(jdbcTemplate.update(
                contains("INSERT INTO rag_document_local_index_state"),
                any(Object[].class)))
                .thenReturn(1);
        stubReturningWithSetterInvocation(List.of(7L), List.of());

        service.ensureCurrent(document());

        verify(statement).setString(1, "hash-1");
        verify(statement).setString(2, "v1");
        verify(statement).setLong(3, 1L);
        verify(statement).setString(4, "hash-1");
    }

    @Test
    @SuppressWarnings("unchecked")
    void fallbackSecondUpdateAssemblesSetterParameters() throws Exception {
        when(jdbcTemplate.update(
                contains("INSERT INTO rag_document_local_index_state"),
                any(Object[].class)))
                .thenReturn(1);
        stubReturningWithSetterInvocation(List.of(), List.of(9L));

        service.ensureCurrent(document());

        verify(jdbcTemplate).update(
                contains("INSERT INTO rag_document_local_index_state"),
                any(Object[].class));
        verify(statement, Mockito.times(2)).setString(1, "hash-1");
        verify(statement, Mockito.times(2)).setLong(3, 1L);
        assertEquals(1, document().getId());
    }
}

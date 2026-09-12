package com.springairag.core.service;

import com.springairag.core.entity.RagDocument;
import com.springairag.documents.chunk.TextChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.RowMapper;

import java.sql.PreparedStatement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ensureCurrent 的批量插入 setter（Batch 343，lambda$ensureCurrent$0）：
 * 实际执行 ParameterizedPreparedStatementSetter，逐列断言写入值
 * （文档 id、代次、哈希、分块器版本、文本、序号、位置、空元数据）。
 */
class KeywordIndexBatchSetterTest {

    private JdbcTemplate jdbcTemplate;
    private DocumentChunkingService chunkingService;
    private KeywordIndexPersistenceService service;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        chunkingService = mock(DocumentChunkingService.class);
        var descriptorProvider = mock(DocumentDerivationDescriptorProvider.class);
        when(descriptorProvider.describe(any()))
                .thenReturn(new DocumentDerivationDescriptorProvider.Descriptor(
                        "TEXT", "v1"));
        service = new KeywordIndexPersistenceService(
                jdbcTemplate, chunkingService, descriptorProvider);
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

    @Test
    @SuppressWarnings("unchecked")
    void batchInsertSetterWritesEveryChunkColumn() {
        when(chunkingService.prepare(any()))
                .thenReturn(new DocumentChunkingService.PreparedChunks(
                        new DocumentDerivationDescriptorProvider.Descriptor(
                                "TEXT", "v1"),
                        List.of(new TextChunk("alpha body", 0, 10))));
        // 新鲜度双查均无结果 → 触发 REBUILD。
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of());
        when(jdbcTemplate.query(anyString(),
                any(PreparedStatementSetter.class), any(RowMapper.class)))
                .thenReturn(List.of(42L));
        when(jdbcTemplate.update(anyString(), any(Object[].class)))
                .thenReturn(1);

        // 捕获批插入 setter 并真实执行，覆盖 lambda 全部列写入。
        ArgumentCaptor<org.springframework.jdbc.core.ParameterizedPreparedStatementSetter<TextChunk>>
                setter = ArgumentCaptor.forClass(
                (Class<org.springframework.jdbc.core.ParameterizedPreparedStatementSetter<TextChunk>>)
                        (Class) org.springframework.jdbc.core
                                .ParameterizedPreparedStatementSetter.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        org.mockito.Mockito.doAnswer(invocation -> {
            var batchSetter =
                    (org.springframework.jdbc.core
                            .ParameterizedPreparedStatementSetter<TextChunk>)
                            invocation.getArgument(3);
            List<TextChunk> chunks =
                    (List<TextChunk>) invocation.getArgument(1);
            for (TextChunk chunk : chunks) {
                batchSetter.setValues(ps, chunk);
            }
            return new int[][] {{1}};
        }).when(jdbcTemplate).batchUpdate(
                contains("INSERT INTO rag_document_chunks"),
                anyList(), eq(100), setter.capture());

        service.ensureCurrent(document());

        assertEquals(1, setter.getAllValues().size());
        // 逐列断言：id/代次/哈希/分块器/文本/序号/起止位置/空元数据。
        try {
            verify(ps).setLong(1, 1L);
            verify(ps).setLong(2, 42L);
            verify(ps).setString(3, "hash-1");
            verify(ps).setString(4, "v1");
            verify(ps).setString(5, "alpha body");
            verify(ps).setInt(6, 0);
            verify(ps).setInt(7, 0);
            verify(ps).setInt(8, 10);
            verify(ps).setObject(9, null);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

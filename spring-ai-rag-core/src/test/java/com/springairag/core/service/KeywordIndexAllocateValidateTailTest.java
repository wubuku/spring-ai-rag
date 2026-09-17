package com.springairag.core.service;

import com.springairag.core.entity.RagDocument;
import com.springairag.documents.chunk.TextChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.ParameterizedPreparedStatementSetter;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * KeywordIndexPersistenceService 分配与校验长尾（Batch 487，JaCoCo
 * 驱动）：allocateGeneration 的 UPDATE-RETURNING 命中路径、markNot
 * Requested 的状态行竞争冲突、ensureCurrent 的禁用/身份守卫、
 * validateChunks 的空块/空白/越界拒绝、ensureContentHash 的缺失
 * 回填与并发冲突。
 */
class KeywordIndexAllocateValidateTailTest {

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
        // allocateGeneration 的 UPDATE...RETURNING 命中：返回代次 5。
        when(jdbcTemplate.query(
                contains("RETURNING state.local_index_generation"),
                any(PreparedStatementSetter.class), any(RowMapper.class)))
                .thenAnswer(invocation -> {
                    RowMapper<Object> mapper =
                            (RowMapper<Object>) invocation.getArgument(2);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getLong(1)).thenReturn(5L);
                    return List.of(mapper.mapRow(rs, 0));
                });
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

    private void stubFreshIndexMiss() {
        when(jdbcTemplate.queryForList(
                contains("FROM rag_document_local_index_state"),
                any(), any(), any()))
                .thenReturn(List.of());
    }

    private void stubStateUpdate(int updated) {
        // any(Object[].class) 整体匹配 varargs（5 参或 8 参均可）。
        when(jdbcTemplate.update(
                contains("SET local_index_status"),
                any(Object[].class)))
                .thenReturn(updated);
    }

    @Test
    void markNotRequestedDeletesChunksAndUpdatesState() {
        stubStateUpdate(1);

        service.markNotRequested(document());

        verify(jdbcTemplate).update(
                contains("DELETE FROM rag_document_chunks"), eq(1L));
    }

    @Test
    void markNotRequestedGenerationConflictThrows() {
        stubStateUpdate(0);

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> service.markNotRequested(document()));
        assertTrue(error.getMessage().contains("state changed while skipping"),
                "应报状态行竞争: " + error.getMessage());
    }

    @Test
    void markNotRequestedRejectsMissingIdentity() {
        assertThrows(IllegalArgumentException.class,
                () -> service.markNotRequested(null));
        assertThrows(IllegalArgumentException.class,
                () -> service.markNotRequested(new RagDocument()));
    }

    @Test
    void ensureCurrentRejectsDisabledAndMissingIdentity() {
        assertThrows(IllegalArgumentException.class,
                () -> service.ensureCurrent(null));
        assertThrows(IllegalArgumentException.class,
                () -> service.ensureCurrent(new RagDocument()));

        RagDocument disabled = document();
        disabled.setEnabled(false);
        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> service.ensureCurrent(disabled));
        assertTrue(error.getMessage().contains("Disabled document"),
                "应报禁用文档: " + error.getMessage());
    }

    @Test
    void ensureCurrentRejectsEmptyChunkList() {
        stubFreshIndexMiss();
        when(chunkingService.prepare(any())).thenReturn(
                new DocumentChunkingService.PreparedChunks(
                        DESCRIPTOR, List.of()));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.ensureCurrent(document()));
        assertEquals("Document produced no chunks", error.getMessage());
    }

    @Test
    void ensureCurrentRejectsBlankChunkText() {
        stubFreshIndexMiss();
        when(chunkingService.prepare(any())).thenReturn(
                new DocumentChunkingService.PreparedChunks(
                        DESCRIPTOR,
                        List.of(new TextChunk("   ", 0, 4))));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.ensureCurrent(document()));
        assertEquals("Chunk text must not be blank", error.getMessage());
    }

    @Test
    void ensureCurrentRejectsOutOfBoundsChunkPosition() {
        stubFreshIndexMiss();
        when(chunkingService.prepare(any())).thenReturn(
                new DocumentChunkingService.PreparedChunks(
                        DESCRIPTOR,
                        List.of(new TextChunk("alpha body tail", 0, 20))));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.ensureCurrent(document()));
        assertTrue(error.getMessage().contains("outside document content"),
                "应报越界: " + error.getMessage());
    }

    @Test
    void ensureCurrentHappyPathWritesReadyState() {
        stubFreshIndexMiss();
        when(chunkingService.prepare(any())).thenReturn(
                new DocumentChunkingService.PreparedChunks(
                        DESCRIPTOR,
                        List.of(new TextChunk("alpha", 0, 5),
                                new TextChunk("body", 6, 10))));
        stubStateUpdate(1);

        service.ensureCurrent(document());

        verify(jdbcTemplate).update(
                contains("DELETE FROM rag_document_chunks"), eq(1L));
        verify(jdbcTemplate).batchUpdate(
                contains("INSERT INTO rag_document_chunks"),
                anyList(), eq(100),
                any(ParameterizedPreparedStatementSetter.class));
        verify(jdbcTemplate).update(
                contains("SET local_index_status"),
                any(Object[].class));
    }

    @Test
    void ensureContentHashBackfillsWhenMissing() {
        RagDocument doc = document();
        doc.setContentHash(null);
        when(jdbcTemplate.update(
                contains("SET content_hash = ?"),
                any(), any(), any()))
                .thenReturn(1);

        String hash = service.ensureContentHash(doc);

        assertEquals(
                com.springairag.core.util.DigestUtils.sha256("alpha body"),
                hash);
        assertEquals(hash, doc.getContentHash());
        assertEquals(4L, doc.getVersion());
    }

    @Test
    void ensureContentHashConflictThrows() {
        RagDocument doc = document();
        doc.setContentHash(null);
        when(jdbcTemplate.update(
                contains("SET content_hash = ?"),
                any(), any(), any()))
                .thenReturn(0);

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> service.ensureContentHash(doc));
        assertTrue(error.getMessage().contains("changed while initializing"),
                "应报初始化冲突: " + error.getMessage());
    }
}

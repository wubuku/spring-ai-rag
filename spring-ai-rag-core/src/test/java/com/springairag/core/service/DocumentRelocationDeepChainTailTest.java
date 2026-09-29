package com.springairag.core.service;

import com.springairag.api.dto.ExternalDocumentRelocateRequest;
import com.springairag.core.repository.RagDocumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DocumentRelocationService 深链长尾（Batch 723，JaCoCo 驱动）：
 * 序列分配 null 的不可用异常（408-410）、完成幂等记录 CAS 失败的
 * 非法状态（331-333）。
 */
class DocumentRelocationDeepChainTailTest {

    private JdbcTemplate jdbcTemplate;
    private RagDocumentRepository documentRepository;
    private DocumentVersionService versionService;
    private DocumentRelocationService service;
    private com.springairag.core.entity.RagDocument document;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        documentRepository = mock(RagDocumentRepository.class);
        versionService = mock(DocumentVersionService.class);
        com.springairag.core.config.RagProperties ragProperties =
                new com.springairag.core.config.RagProperties();
        ragProperties.getDocumentLifecycle().setRelocationEnabled(true);
        com.springairag.core.service.CollectionIdentityResolver resolver =
                mock(com.springairag.core.service.CollectionIdentityResolver.class);
        when(resolver.requireActive(org.mockito.ArgumentMatchers.isNull(),
                eq("source-col")))
                .thenReturn(collection(10L, "source-col"));
        when(resolver.requireActive(org.mockito.ArgumentMatchers.isNull(),
                eq("target-col")))
                .thenReturn(collection(20L, "target-col"));
        when(resolver.beginActiveWrites(List.of(10L, 20L)))
                .thenReturn(List.of(
                        new CollectionIdentityResolver.ActiveCollectionToken(10L, 1),
                        new CollectionIdentityResolver.ActiveCollectionToken(20L, 1)));
        when(jdbcTemplate.update(anyString(), any(Object[].class)))
                .thenReturn(1);
        when(jdbcTemplate.update(anyString(), anyInt()))
                .thenReturn(1);
        when(jdbcTemplate.query(
                contains("INSERT INTO rag_document_idempotency_operations"),
                any(PreparedStatementSetter.class),
                any(RowMapper.class)))
                .thenReturn(List.of(99L));
        when(jdbcTemplate.queryForObject(
                contains("COUNT(*) FROM rag_document_sync_runs"),
                eq(Long.class), any(Object[].class)))
                .thenReturn(0L);
        when(versionService.forceRecordVersion(
                any(com.springairag.core.entity.RagDocument.class),
                anyString(), anyString()))
                .thenAnswer(invocation -> {
                    var version = new com.springairag.core.entity.RagDocumentVersion();
                    version.setVersionNumber(6);
                    return version;
                });
        document = new com.springairag.core.entity.RagDocument();
        document.setId(5L);
        document.setCollectionId(10L);
        service = new DocumentRelocationService(
                jdbcTemplate,
                new com.fasterxml.jackson.databind.ObjectMapper(),
                documentRepository,
                resolver,
                versionService,
                mock(DocumentLifecycleService.class),
                ragProperties,
                mock(jakarta.persistence.EntityManager.class));
    }

    private com.springairag.core.entity.RagCollection collection(
            long id, String key) {
        var value = new com.springairag.core.entity.RagCollection();
        value.setId(id);
        value.setCollectionKey(key);
        return value;
    }

    private void stubMatchingSourceDocument() {
        Map<String, Object> row = new java.util.HashMap<>();
        row.put("id", 5L);
        row.put("version", 3L);
        row.put("document_revision", 2L);
        row.put("external_id", "cms:article:1");
        row.put("source_revision", "etag:2");
        when(jdbcTemplate.queryForList(
                contains("FROM rag_documents"),
                eq(10L), eq("crm"), eq("cms:article:1")))
                .thenReturn(List.of(row));
    }

    private void stubSequenceAllocation(Long value) {
        when(jdbcTemplate.queryForObject(
                contains("mutation_sequence"),
                eq(Long.class), anyLong(), eq("crm")))
                .thenReturn(value);
    }

    private void stubMoveUpdateReturningId(Long id) {
        when(jdbcTemplate.query(
                contains("SET collection_id = ?"),
                any(PreparedStatementSetter.class),
                any(RowMapper.class)))
                .thenReturn(List.of(id));
    }

    private void stubCompletedCascade(int updated) {
        when(jdbcTemplate.update(
                contains("SET status = 'SUCCEEDED', result_document_id"),
                any(Object[].class)))
                .thenReturn(updated);
    }

    private ExternalDocumentRelocateRequest request() {
        return new ExternalDocumentRelocateRequest(
                "source-col", "target-col", "crm", "cms:article:1", "etag:2");
    }

    @Test
    void sequenceAllocationNullThrowsIllegalState() {
        stubMatchingSourceDocument();
        stubSequenceAllocation(null);

        var error = assertThrows(IllegalStateException.class,
                () -> service.relocate(request(), "key-1"));

        assertEquals("Cannot allocate source namespace sequence",
                error.getMessage());
    }

    @Test
    void completeIdempotencyCasFailureThrowsIllegalState() {
        stubMatchingSourceDocument();
        stubSequenceAllocation(1L);
        stubMoveUpdateReturningId(5L);
        stubCompletedCascade(0);
        when(documentRepository.findById(5L))
                .thenReturn(Optional.of(document));

        var error = assertThrows(IllegalStateException.class,
                () -> service.relocate(request(), "key-1"));

        assertEquals("Cannot complete relocation idempotency record",
                error.getMessage());
    }
}

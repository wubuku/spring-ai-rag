package com.springairag.core.service;

import com.springairag.api.dto.ExternalDocumentRelocateRequest;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.exception.RagException;
import com.springairag.core.repository.RagDocumentRepository;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DocumentRelocationService 完成阶段长尾（Batch 748，JaCoCo 驱
 * 动）：全新迁移链成功推进至文档重载，但 findById 落空 →
 * "Relocated document disappeared"（225）。
 *
 * <p>夹具沿用 Batch 722/723 打通的新址链：幂等 INSERT 命中、同步
 * 计数为零、源文档版本匹配、双集合序列分配、目标地址空闲、移动
 * CAS 命中。</p>
 */
class DocumentRelocationVanishTailTest {

    private JdbcTemplate jdbcTemplate;
    private RagDocumentRepository documentRepository;

    private DocumentRelocationService service() {
        jdbcTemplate = mock(JdbcTemplate.class);
        documentRepository = mock(RagDocumentRepository.class);
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
                        new com.springairag.core.service.CollectionIdentityResolver.ActiveCollectionToken(10L, 1),
                        new com.springairag.core.service.CollectionIdentityResolver.ActiveCollectionToken(20L, 1)));
        when(jdbcTemplate.update(anyString(), any(Object[].class)))
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
        when(jdbcTemplate.queryForObject(
                contains("mutation_sequence"),
                eq(Long.class), any(), eq("crm")))
                .thenReturn(1L);
        var entityManager = mock(jakarta.persistence.EntityManager.class);
        return new DocumentRelocationService(
                jdbcTemplate,
                new com.fasterxml.jackson.databind.ObjectMapper(),
                documentRepository,
                resolver,
                mock(DocumentVersionService.class),
                mock(DocumentLifecycleService.class),
                properties(),
                entityManager);
    }

    private com.springairag.core.config.RagProperties properties() {
        var properties = new com.springairag.core.config.RagProperties();
        properties.getDocumentLifecycle().setRelocationEnabled(true);
        return properties;
    }

    private com.springairag.core.entity.RagCollection collection(
            long id, String key) {
        var value = new com.springairag.core.entity.RagCollection();
        value.setId(id);
        value.setCollectionKey(key);
        return value;
    }

    private void stubSourceDocument() {
        when(jdbcTemplate.queryForList(
                contains("FROM rag_documents"),
                eq(10L), eq("crm"), eq("cms:article:1")))
                .thenReturn(List.of(Map.of(
                        "id", 5L,
                        "version", 3L,
                        "document_revision", 2L,
                        "external_id", "cms:article:1",
                        "source_revision", "etag:2")));
        when(jdbcTemplate.query(
                contains("SET collection_id = ?"),
                any(PreparedStatementSetter.class),
                any(RowMapper.class)))
                .thenReturn(List.of(5L));
    }

    private ExternalDocumentRelocateRequest request() {
        return new ExternalDocumentRelocateRequest(
                "source-col", "target-col", "crm", "cms:article:1", "etag:2");
    }

    @Test
    void relocatedDocumentVanishingThrowsNotFound() {
        DocumentRelocationService relocation = service();
        stubSourceDocument();
        // 目标地址空闲。
        when(jdbcTemplate.queryForList(
                contains("FROM rag_documents"),
                eq(20L), eq("crm"), eq("cms:article:1")))
                .thenReturn(List.of());
        // 移动成功后文档消失。
        when(documentRepository.findById(5L)).thenReturn(Optional.empty());

        RagException error = assertThrows(RagException.class,
                () -> relocation.relocate(request(), "key-1"));

        assertEquals(ErrorCode.DOCUMENT_NOT_FOUND,
                error.getErrorCodeEnum());
        assertEquals("Relocated document disappeared", error.getMessage());
    }
}

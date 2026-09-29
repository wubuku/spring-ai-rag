package com.springairag.core.service;

import com.springairag.api.dto.ExternalDocumentRelocateRequest;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.exception.RagException;
import com.springairag.core.repository.RagDocumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DocumentRelocationService 新址校验长尾（Batch 722，JaCoCo 驱
 * 动）：源地址文档缺失 NOT_FOUND（112-115）、非外部管理拒绝
 * （117-119）、遗留身份未认领拒绝 LEGACY_EXTERNAL_IDENTITY_
 * REQUIRES_CLAIM（120-122）、源版本不匹配冲突（123-125）。
 *
 * 夹具沿用 DocumentRelocationResidualBranchesTest 的幂等预留空
 * 结果 + 无活跃同步链（未认领/缺失等校验在序列分配之前触发，无
 * 需更深的移动链桩）。
 */
class DocumentRelocationFreshAddressTailTest {

    private JdbcTemplate jdbcTemplate;
    private DocumentRelocationService service;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
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
        // 幂等 INSERT 命中 → 全新预留（非重放）。
        when(jdbcTemplate.query(
                contains("INSERT INTO rag_document_idempotency_operations"),
                any(org.springframework.jdbc.core.PreparedStatementSetter.class),
                any(org.springframework.jdbc.core.RowMapper.class)))
                .thenReturn(List.of(99L));
        when(jdbcTemplate.queryForObject(
                contains("COUNT(*) FROM rag_document_sync_runs"),
                eq(Long.class), any(Object[].class)))
                .thenReturn(0L);
        service = new DocumentRelocationService(
                jdbcTemplate,
                new com.fasterxml.jackson.databind.ObjectMapper(),
                mock(RagDocumentRepository.class),
                resolver,
                mock(DocumentVersionService.class),
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

    private void stubFoundDocument(Map<String, Object> row) {
        List<Map<String, Object>> rows = row == null
                ? List.of() : List.of(row);
        when(jdbcTemplate.queryForList(
                contains("FROM rag_documents"),
                eq(10L), eq("crm"), eq("cms:article:1")))
                .thenReturn(rows);
    }

    private Map<String, Object> managedRow(String sourceRevision) {
        Map<String, Object> row = new java.util.HashMap<>();
        row.put("id", 5L);
        row.put("version", 3L);
        row.put("document_revision", 2L);
        row.put("external_id", "cms:article:1");
        row.put("source_revision", sourceRevision);
        return row;
    }

    @Test
    void missingSourceDocumentThrowsNotFound() {
        stubFoundDocument(null);

        RagException error = assertThrows(RagException.class,
                () -> service.relocate(request(), "key-1"));

        assertEquals(ErrorCode.DOCUMENT_NOT_FOUND,
                error.getErrorCodeEnum());
    }

    @Test
    void nonManagedDocumentThrowsNotExternallyManaged() {
        Map<String, Object> row = new java.util.HashMap<>();
        row.put("id", 5L);
        row.put("version", 3L);
        row.put("document_revision", 2L);
        row.put("external_id", null);
        row.put("source_revision", "etag:2");
        stubFoundDocument(row);

        RagException error = assertThrows(RagException.class,
                () -> service.relocate(request(), "key-1"));

        assertEquals(ErrorCode.DOCUMENT_NOT_EXTERNAL_MANAGED,
                error.getErrorCodeEnum());
    }

    @Test
    void unclaimedLegacyIdentityRequiresClaim() {
        stubFoundDocument(managedRow(null));

        RagException error = assertThrows(RagException.class,
                () -> service.relocate(request(), "key-1"));

        assertEquals(ErrorCode.LEGACY_EXTERNAL_IDENTITY_REQUIRES_CLAIM,
                error.getErrorCodeEnum());
    }

    @Test
    void revisionMismatchThrowsConflict() {
        stubFoundDocument(managedRow("etag:1"));

        RagException error = assertThrows(RagException.class,
                () -> service.relocate(request(), "key-1"));

        assertEquals(ErrorCode.DOCUMENT_REVISION_CONFLICT,
                error.getErrorCodeEnum());
    }

    private ExternalDocumentRelocateRequest request() {
        return new ExternalDocumentRelocateRequest(
                "source-col", "target-col", "crm", "cms:article:1", "etag:2");
    }
}

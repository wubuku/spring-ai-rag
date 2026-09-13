package com.springairag.core.service;

import com.springairag.api.dto.ExternalDocumentRelocateRequest;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.config.RagProperties;
import com.springairag.core.repository.RagDocumentRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.RowMapper;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * relocate 残余分支（Batch 354）：重放信封读取与 ACL 复核、
 * IN_PROGRESS/指纹不匹配拒绝、活跃同步 run 冲突、外部托管与命
 * 名空间守卫。
 */
class DocumentRelocationResidualBranchesTest {

    private JdbcTemplate jdbcTemplate;
    private com.springairag.core.service.CollectionIdentityResolver collectionResolver;
    private DocumentVersionService versionService;
    private DocumentLifecycleService lifecycleService;
    private RagDocumentRepository documentRepository;
    private RagProperties ragProperties;
    private DocumentRelocationService service;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        collectionResolver = mock(CollectionIdentityResolver.class);
        versionService = mock(DocumentVersionService.class);
        lifecycleService = mock(DocumentLifecycleService.class);
        documentRepository = mock(RagDocumentRepository.class);
        ragProperties = new RagProperties();
        service = new DocumentRelocationService(
                jdbcTemplate,
                new com.fasterxml.jackson.databind.ObjectMapper(),
                documentRepository,
                collectionResolver,
                versionService,
                lifecycleService,
                ragProperties,
                mock(EntityManager.class));
        ragProperties.getDocumentLifecycle().setRelocationEnabled(true);
        when(collectionResolver.requireActive(null, "source-col"))
                .thenReturn(collection(10L, "source-col"));
        when(collectionResolver.requireActive(null, "target-col"))
                .thenReturn(collection(20L, "target-col"));
        when(collectionResolver.beginActiveWrites(List.of(10L, 20L)))
                .thenReturn(List.of(
                        new com.springairag.core.service.CollectionIdentityResolver.ActiveCollectionToken(10L, 1),
                        new com.springairag.core.service.CollectionIdentityResolver.ActiveCollectionToken(20L, 1)));
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
    }

    private com.springairag.core.entity.RagCollection collection(long id, String key) {
        var collection = new com.springairag.core.entity.RagCollection();
        collection.setId(id);
        collection.setCollectionKey(key);
        return collection;
    }

    private ExternalDocumentRelocateRequest request() {
        return new ExternalDocumentRelocateRequest(
                "source-col", "target-col", "crm", "cms:article:1", "etag:2");
    }

    private String fingerprintOfRequest() {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("sourceCollectionKey", "source-col");
        canonical.put("targetCollectionKey", "target-col");
        canonical.put("sourceNamespace", "crm");
        canonical.put("externalId", "cms:article:1");
        canonical.put("expectedSourceRevision", "etag:2");
        try {
            return com.springairag.core.util.DigestUtils.sha256(
                    new com.fasterxml.jackson.databind.ObjectMapper()
                            .writeValueAsString(canonical));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void stubNoActiveSyncRuns() {
        when(jdbcTemplate.queryForObject(
                contains("COUNT(*) FROM rag_document_sync_runs"),
                eq(Long.class), any(Object[].class))).thenReturn(0L);
    }

    /** 幂等 INSERT 未命中（空列表）→ 走台账 SELECT 读取既有行。 */
    private void stubReserveSelectRow(Map<String, Object> row) {
        when(jdbcTemplate.query(contains("rag_document_idempotency_operations"),
                any(PreparedStatementSetter.class), any(RowMapper.class)))
                .thenReturn(List.of());
        when(jdbcTemplate.queryForList(
                contains("rag_document_idempotency_operations"), any(Object[].class)))
                .thenReturn(List.of(row));
    }

    @Test
    void replayEnvelopeReturnedWhenReservationReplays() {
        stubNoActiveSyncRuns();
        stubReserveSelectRow(Map.of(
                "id", 99L,
                "request_fingerprint", fingerprintOfRequest(),
                "status", "SUCCEEDED",
                "result_payload", "{\"schemaVersion\":1,\"response\":{"
                        + "\"documentId\":5,\"sourceCollectionKey\":\"source-col\","
                        + "\"targetCollectionKey\":\"target-col\","
                        + "\"sourceNamespace\":\"crm\","
                        + "\"externalId\":\"cms:article:1\","
                        + "\"sourceRevision\":\"etag:2\",\"action\":\"RELOCATED\","
                        + "\"documentRevision\":3,\"versionNumber\":9,"
                        + "\"contentChanged\":true,\"derivationAction\":\"PRESERVED\"}}",
                "source_acl_id", 10L,
                "target_acl_id", 20L,
                "expired", false));

        var response = service.relocate(request(), "key-1");

        assertEquals("RELOCATED", response.action());
        assertEquals(5L, response.documentId());
        assertTrue(response.contentChanged());
    }

    @Test
    void replayWithMissingAuthorizationScopeThrows() {
        stubNoActiveSyncRuns();
        Map<String, Object> missingAclRow = new java.util.HashMap<>();
        missingAclRow.put("id", 99L);
        missingAclRow.put("request_fingerprint", fingerprintOfRequest());
        missingAclRow.put("status", "SUCCEEDED");
        missingAclRow.put("result_payload", "{}");
        missingAclRow.put("source_acl_id", null);
        missingAclRow.put("target_acl_id", 20L);
        missingAclRow.put("expired", false);
        stubReserveSelectRow(missingAclRow);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.relocate(request(), "key-1"));
        assertTrue(error.getMessage().contains("missing authorization scope"));
    }

    @Test
    void replayUnsupportedSchemaRejected() {
        stubNoActiveSyncRuns();
        stubReserveSelectRow(Map.of(
                "id", 99L,
                "request_fingerprint", fingerprintOfRequest(),
                "status", "SUCCEEDED",
                "result_payload", "{\"schemaVersion\":99,\"response\":{}}",
                "source_acl_id", 10L,
                "target_acl_id", 20L,
                "expired", false));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.relocate(request(), "key-1"));
        assertTrue(error.getMessage().contains("Unsupported relocation response schema")
                        || error.getMessage().contains("Cannot read relocation replay"),
                "应拒绝不支持的重放 schema: " + error.getMessage());
    }

    @Test
    void inProgressReservationRejected() {
        stubNoActiveSyncRuns();
        Map<String, Object> inProgressRow = new java.util.HashMap<>();
        inProgressRow.put("id", 99L);
        inProgressRow.put("request_fingerprint", fingerprintOfRequest());
        inProgressRow.put("status", "IN_PROGRESS");
        inProgressRow.put("result_payload", null);
        inProgressRow.put("source_acl_id", 10L);
        inProgressRow.put("target_acl_id", 20L);
        inProgressRow.put("expired", false);
        stubReserveSelectRow(inProgressRow);

        RuntimeException error = assertThrows(RuntimeException.class,
                () -> service.relocate(request(), "key-1"));
        assertTrue(error.getMessage().contains("still in progress"));
    }

    @Test
    void fingerprintMismatchRejected() {
        stubNoActiveSyncRuns();
        stubReserveSelectRow(Map.of(
                "id", 99L,
                "request_fingerprint", "different",
                "status", "SUCCEEDED",
                "result_payload", "{}",
                "source_acl_id", 10L,
                "target_acl_id", 20L,
                "expired", false));

        RuntimeException error = assertThrows(RuntimeException.class,
                () -> service.relocate(request(), "key-1"));
        assertTrue(error.getMessage().contains("Idempotency-Key was already used"));
    }

    @Test
    void activeSyncRunConflictRejected() {
        when(jdbcTemplate.queryForObject(
                contains("COUNT(*) FROM rag_document_sync_runs"),
                eq(Long.class), any(Object[].class))).thenReturn(2L);

        RuntimeException error = assertThrows(RuntimeException.class,
                () -> service.relocate(request(), "key-1"));
        assertTrue(error.getMessage().contains("active source synchronization run"));
    }

    @Test
    void nonAsciiNamespaceRejected() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.relocate(new ExternalDocumentRelocateRequest(
                        "source-col", "target-col", "cr\u00e9m",
                        "cms:article:1", "etag:2"), "key-1"));
        assertTrue(error.getMessage().contains("visible ASCII only"));
    }

    @Test
    void nonAsciiExternalIdRejected() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.relocate(new ExternalDocumentRelocateRequest(
                        "source-col", "target-col", "crm",
                        "cms:art\u0001icle", "etag:2"), "key-1"));
        assertTrue(error.getMessage().contains("visible ASCII only"));
    }

    @Test
    void sourceDocumentWithoutExternalIdRejected() {
        stubNoActiveSyncRuns();
        // 幂等插入命中（fresh 预约）。
        when(jdbcTemplate.query(contains("rag_document_idempotency_operations"),
                any(PreparedStatementSetter.class), any(RowMapper.class)))
                .thenReturn(List.of(99L));
        Map<String, Object> row = new java.util.HashMap<>();
        row.put("id", 5L);
        row.put("version", 3L);
        row.put("document_revision", 2L);
        row.put("external_id", null);
        row.put("source_revision", "etag:2");
        when(jdbcTemplate.queryForList(contains("FROM rag_documents"),
                any(Object[].class)))
                .thenReturn(List.of(row))
                .thenReturn(List.of());
        when(jdbcTemplate.queryForList(
                contains("rag_document_relocated_addresses"), any(Object[].class)))
                .thenReturn(List.of());

        RuntimeException error = assertThrows(RuntimeException.class,
                () -> service.relocate(request(), "key-1"));
        assertTrue(error.getMessage().contains("externally managed"));
    }
}

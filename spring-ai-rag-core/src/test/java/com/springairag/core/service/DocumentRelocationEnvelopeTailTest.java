package com.springairag.core.service;

import com.springairag.api.dto.ExternalDocumentRelocateRequest;
import com.springairag.core.config.RagProperties;
import com.springairag.core.repository.RagDocumentRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.RowMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DocumentRelocationService 幂等台账长尾（Batch 532，JaCoCo 驱
 * 动）：源/目标同集合拒绝、Idempotency-Key 超长、过期预约删除后
 * 重新预约并重放、损坏回放信封拒绝、非可见 ASCII 字段拒绝。
 */
class DocumentRelocationEnvelopeTailTest {

    private JdbcTemplate jdbcTemplate;
    private com.springairag.core.service.CollectionIdentityResolver collectionResolver;
    private DocumentRelocationService service;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        collectionResolver = mock(CollectionIdentityResolver.class);
        RagProperties ragProperties = new RagProperties();
        service = new DocumentRelocationService(
                jdbcTemplate,
                new com.fasterxml.jackson.databind.ObjectMapper(),
                mock(RagDocumentRepository.class),
                collectionResolver,
                mock(DocumentVersionService.class),
                mock(DocumentLifecycleService.class),
                ragProperties,
                mock(EntityManager.class));
        ragProperties.getDocumentLifecycle().setRelocationEnabled(true);
        when(collectionResolver.requireActive(null, "source-col"))
                .thenReturn(collection(10L, "source-col"));
        when(collectionResolver.requireActive(null, "target-col"))
                .thenReturn(collection(20L, "target-col"));
        when(jdbcTemplate.update(anyString(), any(Object[].class)))
                .thenReturn(1);
    }

    private com.springairag.core.entity.RagCollection collection(
            long id, String key) {
        var value = new com.springairag.core.entity.RagCollection();
        value.setId(id);
        value.setCollectionKey(key);
        value.setEnabled(true);
        return value;
    }

    private ExternalDocumentRelocateRequest request(String targetKey) {
        return new ExternalDocumentRelocateRequest(
                "source-col", targetKey, "crm", "cms:article:1", "etag:2");
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

    /** 幂等 INSERT 均未命中，SELECT 依序返回 provided 行列表。 */
    private void stubReserveSelectRows(List<Map<String, Object>> rows) {
        when(jdbcTemplate.query(
                contains("rag_document_idempotency_operations"),
                any(PreparedStatementSetter.class), any(RowMapper.class)))
                .thenReturn(List.of());
        when(jdbcTemplate.queryForList(
                contains("rag_document_idempotency_operations"),
                any(Object[].class)))
                .thenReturn(rows, rows.subList(rows.size() - 1, rows.size()));
    }

    @Test
    void sameSourceAndTargetCollectionsAreRejected() {
        var error = assertThrows(IllegalArgumentException.class,
                () -> service.relocate(request("source-col"), "key-1"));

        assertEquals(
                "sourceCollectionKey and targetCollectionKey must be different",
                error.getMessage());
    }

    @Test
    void oversizeIdempotencyKeyIsRejected() {
        var error = assertThrows(IllegalArgumentException.class,
                () -> service.relocate(request("target-col"), "k".repeat(256)));

        assertEquals("Idempotency-Key must not exceed 255 characters",
                error.getMessage());
    }

    @Test
    void nonVisibleAsciiExternalIdIsRejected() {
        var error = assertThrows(IllegalArgumentException.class,
                () -> service.relocate(
                        new ExternalDocumentRelocateRequest(
                                "source-col", "target-col", "crm",
                                "cms:article:\u00071", "etag:2"),
                        "key-1"));

        assertEquals("externalId must contain visible ASCII only",
                error.getMessage());
    }

    @Test
    void expiredReservationIsDeletedThenReReservedAndReplayed() {
        when(jdbcTemplate.queryForObject(
                contains("COUNT(*) FROM rag_document_sync_runs"),
                eq(Long.class), any(Object[].class))).thenReturn(0L);
        Map<String, Object> expired = new LinkedHashMap<>();
        expired.put("id", 77L);
        expired.put("request_fingerprint", fingerprintOfRequest());
        expired.put("status", "IN_PROGRESS");
        expired.put("result_payload", null);
        expired.put("source_acl_id", 10L);
        expired.put("target_acl_id", 20L);
        expired.put("expired", true);

        Map<String, Object> succeeded = new LinkedHashMap<>();
        succeeded.put("id", 78L);
        succeeded.put("request_fingerprint", fingerprintOfRequest());
        succeeded.put("status", "SUCCEEDED");
        succeeded.put("result_payload", "{\"schemaVersion\":1,\"response\":{"
                + "\"documentId\":5,\"sourceCollectionKey\":\"source-col\","
                + "\"targetCollectionKey\":\"target-col\","
                + "\"sourceNamespace\":\"crm\","
                + "\"externalId\":\"cms:article:1\","
                + "\"sourceRevision\":\"etag:2\",\"action\":\"RELOCATED\","
                + "\"documentRevision\":3,\"versionNumber\":9,"
                + "\"contentChanged\":true,\"derivationAction\":\"PRESERVED\"}}");
        succeeded.put("source_acl_id", 10L);
        succeeded.put("target_acl_id", 20L);
        succeeded.put("expired", false);

        when(jdbcTemplate.query(
                contains("rag_document_idempotency_operations"),
                any(PreparedStatementSetter.class), any(RowMapper.class)))
                .thenReturn(List.of());
        when(jdbcTemplate.queryForList(
                contains("rag_document_idempotency_operations"),
                any(Object[].class)))
                .thenReturn(List.of(expired), List.of(succeeded));

        var response = service.relocate(request("target-col"), "key-1");

        assertEquals("RELOCATED", response.action());
        verify(jdbcTemplate).update(
                contains("DELETE FROM rag_document_idempotency_operations"),
                any(Object[].class));
    }

    @Test
    void corruptReplayEnvelopeSurfacesIllegalState() {
        when(jdbcTemplate.queryForObject(
                contains("COUNT(*) FROM rag_document_sync_runs"),
                eq(Long.class), any(Object[].class))).thenReturn(0L);
        Map<String, Object> corrupted = new LinkedHashMap<>();
        corrupted.put("id", 79L);
        corrupted.put("request_fingerprint", fingerprintOfRequest());
        corrupted.put("status", "SUCCEEDED");
        corrupted.put("result_payload", "{not-json");
        corrupted.put("source_acl_id", 10L);
        corrupted.put("target_acl_id", 20L);
        corrupted.put("expired", false);

        when(jdbcTemplate.query(
                contains("rag_document_idempotency_operations"),
                any(PreparedStatementSetter.class), any(RowMapper.class)))
                .thenReturn(List.of());
        when(jdbcTemplate.queryForList(
                contains("rag_document_idempotency_operations"),
                any(Object[].class)))
                .thenReturn(List.of(corrupted));

        var error = assertThrows(IllegalStateException.class,
                () -> service.relocate(request("target-col"), "key-1"));

        assertEquals("Cannot read relocation replay response",
                error.getMessage());
    }
}

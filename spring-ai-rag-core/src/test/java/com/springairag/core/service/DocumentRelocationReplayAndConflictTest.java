package com.springairag.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.ExternalDocumentRelocateRequest;
import com.springairag.api.dto.ExternalDocumentRelocateResponse;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.entity.RagDocumentVersion;
import com.springairag.core.exception.RagException;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.service.CollectionIdentityResolver;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * relocate 的回放与冲突分支：幂等信封重放（不触达文档）、reverse
 * 搬迁（解析退役地址）、CAS 未命中、退役地址并发变化、源地址插
 * 入冲突、目标地址被永久退役阻断。
 */
class DocumentRelocationReplayAndConflictTest {

    private JdbcTemplate jdbcTemplate;
    private CollectionIdentityResolver collectionResolver;
    private DocumentVersionService versionService;
    private DocumentLifecycleService lifecycleService;
    private RagDocumentRepository documentRepository;
    private EntityManager entityManager;
    private RagProperties ragProperties;
    private DocumentRelocationService service;

    private final List<String> capturedFingerprints = new java.util.ArrayList<>();

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        collectionResolver = mock(CollectionIdentityResolver.class);
        versionService = mock(DocumentVersionService.class);
        lifecycleService = mock(DocumentLifecycleService.class);
        documentRepository = mock(RagDocumentRepository.class);
        entityManager = mock(EntityManager.class);
        ragProperties = new RagProperties();
        service = new DocumentRelocationService(
                jdbcTemplate,
                new ObjectMapper(),
                documentRepository,
                collectionResolver,
                versionService,
                lifecycleService,
                ragProperties,
                entityManager);
        ragProperties.getDocumentLifecycle().setRelocationEnabled(true);

        // reserve 的 INSERT RETURNING：恒空 → 走已有记录的 SELECT 回放路径。
        Mockito.lenient().when(jdbcTemplate.query(
                contains("rag_document_idempotency_operations"),
                any(PreparedStatementSetter.class), any(RowMapper.class)))
                .thenAnswer(invocation -> {
                    PreparedStatementSetter setter = invocation.getArgument(1);
                    java.sql.PreparedStatement ps = mock(java.sql.PreparedStatement.class);
                    Mockito.doAnswer(a -> {
                        capturedFingerprints.add(a.getArgument(1));
                        return null;
                    }).when(ps).setString(eq(4), any());
                    setter.setValues(ps);
                    return List.of(99L);
                });
        Mockito.lenient().when(jdbcTemplate.update(
                anyString(), any(Object[].class))).thenReturn(1);
        Mockito.lenient().when(jdbcTemplate.update(anyString(),
                any(Object.class), any(Object.class), any(Object.class),
                any(Object.class), any(Object.class), any(Object.class)))
                .thenReturn(1);
    }

    @AfterEach
    void tearDown() {
        capturedFingerprints.clear();
        org.springframework.web.context.request.RequestContextHolder
                .resetRequestAttributes();
    }

    private ExternalDocumentRelocateRequest request() {
        return new ExternalDocumentRelocateRequest(
                "source-col", "target-col", "crm", "cms:article:1", "etag:2");
    }

    private com.springairag.core.entity.RagCollection collection(
            long id, String key) {
        var collection = new com.springairag.core.entity.RagCollection();
        collection.setId(id);
        collection.setCollectionKey(key);
        return collection;
    }

    private void stubCollections() {
        when(collectionResolver.requireActive(null, "source-col"))
                .thenReturn(collection(10L, "source-col"));
        when(collectionResolver.requireActive(null, "target-col"))
                .thenReturn(collection(20L, "target-col"));
        when(collectionResolver.beginActiveWrites(List.of(10L, 20L)))
                .thenReturn(List.of(
                        new CollectionIdentityResolver.ActiveCollectionToken(10L, 1),
                        new CollectionIdentityResolver.ActiveCollectionToken(20L, 1)));
        when(jdbcTemplate.queryForObject(
                contains("COUNT(*) FROM rag_document_sync_runs"),
                eq(Long.class), any(Object[].class))).thenReturn(0L);
    }

    private Map<String, Object> sourceRow() {
        return Map.of(
                "id", 5L, "version", 3L, "document_revision", 2L,
                "external_id", "cms:article:1", "source_revision", "etag:2");
    }

    private void stubDocumentsSourceHitTargetMiss() {
        when(jdbcTemplate.queryForList(contains("FROM rag_documents"),
                any(Object[].class)))
                .thenReturn(List.of(sourceRow()))
                .thenReturn(List.of());
    }

    private void stubSequenceAllocation() {
        when(jdbcTemplate.queryForObject(contains("RETURNING mutation_sequence"),
                eq(Long.class), any(Object[].class)))
                .thenReturn(10L)
                .thenReturn(20L);
    }

    private void stubCasHit() {
        when(jdbcTemplate.query(contains("SET collection_id = ?"),
                any(PreparedStatementSetter.class), any(RowMapper.class)))
                .thenReturn(List.of(5L));
    }

    private void stubRelocatedDocument() {
        RagDocument relocated = new RagDocument();
        relocated.setId(5L);
        relocated.setSourceNamespace("crm");
        relocated.setExternalId("cms:article:1");
        relocated.setSourceRevision("etag:2");
        relocated.setDocumentRevision(3L);
        when(documentRepository.findById(5L)).thenReturn(Optional.of(relocated));
        RagDocumentVersion version = mock(RagDocumentVersion.class);
        when(version.getVersionNumber()).thenReturn(9);
        when(versionService.forceRecordVersion(
                eq(relocated), eq("RELOCATE"), anyString())).thenReturn(version);
        when(lifecycleService.read(relocated)).thenReturn(null);
    }

    private String replayEnvelope() throws Exception {
        ObjectMapper om = new ObjectMapper();
        var response = om.createObjectNode()
                .put("documentId", 5L)
                .put("sourceCollectionKey", "source-col")
                .put("targetCollectionKey", "target-col")
                .put("sourceNamespace", "crm")
                .put("externalId", "cms:article:1")
                .put("sourceRevision", "etag:2")
                .put("action", "RELOCATED")
                .put("documentRevision", 3)
                .put("versionNumber", 9)
                .put("contentChanged", false)
                .put("derivationAction", "PRESERVED");
        var envelope = om.createObjectNode();
        envelope.put("schemaVersion", 1);
        envelope.set("response", response);
        return om.writeValueAsString(envelope);
    }

    private void stubReplayReservation(String payload) {
        // SELECT 回放行：指纹取自 INSERT 捕获值，保证与重算一致。
        when(jdbcTemplate.queryForList(
                contains("rag_document_idempotency_operations"), any(Object[].class)))
                .thenAnswer(invocation -> {
                    Object[] args = invocation.getArguments();
                    Map<String, Object> row = new HashMap<>();
                    row.put("id", 99L);
                    row.put("request_fingerprint", capturedFingerprints.isEmpty()
                            ? args[2] : capturedFingerprints.get(0));
                    row.put("status", "SUCCEEDED");
                    row.put("result_payload", payload);
                    row.put("source_acl_id", 10L);
                    row.put("target_acl_id", 20L);
                    row.put("expired", false);
                    return List.of(row);
                });
    }

    @Test
    void replayReturnsStoredEnvelopeWithoutTouchingDocuments() throws Exception {
        stubCollections();
        // INSERT RETURNING 为空 → 读取已有记录回放；同时捕获
        // 写入的指纹（回放行的 request_fingerprint 必须一致）。
        Mockito.lenient().when(jdbcTemplate.query(
                contains("rag_document_idempotency_operations"),
                any(PreparedStatementSetter.class), any(RowMapper.class)))
                .thenAnswer(invocation -> {
                    PreparedStatementSetter setter = invocation.getArgument(1);
                    java.sql.PreparedStatement ps = mock(java.sql.PreparedStatement.class);
                    Mockito.doAnswer(a -> {
                        capturedFingerprints.add(a.getArgument(1));
                        return null;
                    }).when(ps).setString(eq(4), any());
                    setter.setValues(ps);
                    return List.of();
                });
        stubReplayReservation(replayEnvelope());

        ExternalDocumentRelocateResponse response =
                service.relocate(request(), "key-1");

        assertEquals(5L, response.documentId());
        assertEquals("RELOCATED", response.action());
        assertEquals(9, response.versionNumber());
        assertFalse(response.contentChanged());
        assertEquals("PRESERVED", response.derivationAction());
        // 重放短路：不触达文档仓储，也不清空实体管理器。
        verify(documentRepository, never()).findById(any());
        verify(entityManager, never()).clear();
    }

    @Test
    void reverseRelocationResolvesRetiredAddress() {
        stubCollections();
        stubDocumentsSourceHitTargetMiss();
        stubSequenceAllocation();
        stubCasHit();
        stubRelocatedDocument();
        // 目标地址标记指向同一文档且目标为源集合 → reverse 搬迁。
        when(jdbcTemplate.queryForList(
                contains("rag_document_relocated_addresses"), any(Object[].class)))
                .thenReturn(List.of(Map.of(
                        "id", 77L, "document_id", 5L, "target_collection_id", 10L)));

        ExternalDocumentRelocateResponse response =
                service.relocate(request(), "key-1");

        assertEquals("RELOCATED", response.action());
        verify(jdbcTemplate).update(
                contains("SET active = FALSE, resolved_at"), eq(77L));
    }

    @Test
    void casMissThrowsConcurrentModification() {
        stubCollections();
        stubDocumentsSourceHitTargetMiss();
        stubSequenceAllocation();
        when(jdbcTemplate.query(contains("SET collection_id = ?"),
                any(PreparedStatementSetter.class), any(RowMapper.class)))
                .thenReturn(List.of());

        RagException error = assertThrows(RagException.class,
                () -> service.relocate(request(), "key-1"));

        assertEquals(ErrorCode.CONCURRENT_MODIFICATION, error.getErrorCodeEnum());
        assertTrue(error.getMessage().contains("changed while relocation"));
    }

    @Test
    void reverseResolveMissThrowsConcurrentModification() {
        stubCollections();
        stubDocumentsSourceHitTargetMiss();
        stubSequenceAllocation();
        stubCasHit();
        when(jdbcTemplate.queryForList(
                contains("rag_document_relocated_addresses"), any(Object[].class)))
                .thenReturn(List.of(Map.of(
                        "id", 77L, "document_id", 5L, "target_collection_id", 10L)));
        when(jdbcTemplate.update(
                contains("SET active = FALSE, resolved_at"), any(Object[].class)))
                .thenReturn(0);

        RagException error = assertThrows(RagException.class,
                () -> service.relocate(request(), "key-1"));

        assertEquals(ErrorCode.CONCURRENT_MODIFICATION, error.getErrorCodeEnum());
        assertTrue(error.getMessage().contains("retired target address"));
    }

    @Test
    void insertConflictThrowsConcurrentModification() {
        stubCollections();
        stubDocumentsSourceHitTargetMiss();
        stubSequenceAllocation();
        stubCasHit();
        stubRelocatedDocument();
        when(jdbcTemplate.update(
                contains("INSERT INTO rag_document_relocated_addresses"),
                any(Object[].class)))
                .thenThrow(new DataIntegrityViolationException("duplicate"));

        RagException error = assertThrows(RagException.class,
                () -> service.relocate(request(), "key-1"));

        assertEquals(ErrorCode.CONCURRENT_MODIFICATION, error.getErrorCodeEnum());
        assertTrue(error.getMessage().contains("source address was retired"));
    }

    @Test
    void retiredMarkerBlocksRelocation() {
        stubCollections();
        stubDocumentsSourceHitTargetMiss();
        stubSequenceAllocation();
        // 目标标记指向另一文档 → 非 reverse → 永久退役阻断。
        when(jdbcTemplate.queryForList(
                contains("rag_document_relocated_addresses"), any(Object[].class)))
                .thenReturn(List.of(Map.of(
                        "id", 88L, "document_id", 6L, "target_collection_id", 30L)));

        RagException error = assertThrows(RagException.class,
                () -> service.relocate(request(), "key-1"));

        assertEquals(ErrorCode.TARGET_EXTERNAL_IDENTITY_RETIRED,
                error.getErrorCodeEnum());
    }
}

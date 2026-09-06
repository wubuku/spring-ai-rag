package com.springairag.core.service;

import com.springairag.api.dto.ExternalDocumentRelocateRequest;
import com.springairag.api.dto.ExternalDocumentRelocateResponse;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.RagCollection;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.exception.RagException;
import com.springairag.core.repository.RagDocumentRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 覆盖外部文档迁移协调器：功能开关、请求归一化守卫、幂等预约、
 * 源/目标身份检查与端到端迁移确认。
 */
class DocumentRelocationServiceTest {

    private JdbcTemplate jdbcTemplate;
    private CollectionIdentityResolver collectionResolver;
    private DocumentVersionService versionService;
    private DocumentLifecycleService lifecycleService;
    private RagDocumentRepository documentRepository;
    private EntityManager entityManager;
    private RagProperties ragProperties;
    private DocumentRelocationService service;

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
                new com.fasterxml.jackson.databind.ObjectMapper(),
                documentRepository,
                collectionResolver,
                versionService,
                lifecycleService,
                ragProperties,
                entityManager);
        ragProperties.getDocumentLifecycle().setRelocationEnabled(true);
    }

    @AfterEach
    void tearDown() {
        org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
    }

    private ExternalDocumentRelocateRequest request() {
        return new ExternalDocumentRelocateRequest(
                "source-col", "target-col", "crm", "cms:article:1", "etag:2");
    }

    private RagCollection collection(long id, String key) {
        RagCollection collection = new RagCollection();
        collection.setId(id);
        collection.setCollectionKey(key);
        return collection;
    }

    private void stubHappyPathRepositories() {
        when(collectionResolver.requireActive(null, "source-col"))
                .thenReturn(collection(10L, "source-col"));
        when(collectionResolver.requireActive(null, "target-col"))
                .thenReturn(collection(20L, "target-col"));
        when(collectionResolver.beginActiveWrites(List.of(10L, 20L)))
                .thenReturn(List.of(
                        new CollectionIdentityResolver.ActiveCollectionToken(10L, 1),
                        new CollectionIdentityResolver.ActiveCollectionToken(20L, 1)));
        // expireAndRejectActiveRuns：无活跃同步 run。
        when(jdbcTemplate.queryForObject(contains("COUNT(*) FROM rag_document_sync_runs"),
                eq(Long.class), any(Object[].class))).thenReturn(0L);
        // reserve：幂等操作插入命中。
        when(jdbcTemplate.query(contains("rag_document_idempotency_operations"),
                any(PreparedStatementSetter.class), any(RowMapper.class)))
                .thenReturn(List.of(99L));
        // findDocument：源命中，目标未占用。
        Map<String, Object> sourceRow = Map.of(
                "id", 5L, "version", 3L, "document_revision", 2L,
                "external_id", "cms:article:1", "source_revision", "etag:2");
        when(jdbcTemplate.queryForList(contains("FROM rag_documents"),
                any(Object[].class)))
                .thenReturn(List.of(sourceRow))
                .thenReturn(List.of());
        // 迁移地址标记：目标无 retired 记录。
        when(jdbcTemplate.queryForList(
                contains("rag_document_relocated_addresses"), any(Object[].class)))
                .thenReturn(List.of());
        // 序列分配：source → 10，target → 20。
        when(jdbcTemplate.queryForObject(contains("RETURNING mutation_sequence"),
                eq(Long.class), any(Object[].class)))
                .thenReturn(10L)
                .thenReturn(20L);
        // CAS 迁移文档行：命中。
        when(jdbcTemplate.query(contains("SET collection_id = ?"),
                any(PreparedStatementSetter.class), any(RowMapper.class)))
                .thenReturn(List.of(5L));
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
        RagDocument relocated = new RagDocument();
        relocated.setId(5L);
        relocated.setSourceNamespace("crm");
        relocated.setExternalId("cms:article:1");
        relocated.setSourceRevision("etag:2");
        relocated.setDocumentRevision(3L);
        when(documentRepository.findById(5L)).thenReturn(Optional.of(relocated));
        var version = mock(com.springairag.core.entity.RagDocumentVersion.class);
        when(version.getVersionNumber()).thenReturn(9);
        when(versionService.forceRecordVersion(eq(relocated), eq("RELOCATE"), anyString()))
                .thenReturn(version);
        when(lifecycleService.read(relocated)).thenReturn(null);
    }

    @Test
    void rejectsWhenRelocationIsDisabled() {
        ragProperties.getDocumentLifecycle().setRelocationEnabled(false);

        RagException error = assertThrows(RagException.class,
                () -> service.relocate(request(), "key-1"));
        assertEquals(ErrorCode.DOCUMENT_RELOCATION_DISABLED, error.getErrorCodeEnum());
    }

    @Test
    void rejectsIdenticalSourceAndTargetKeys() {
        assertThrows(IllegalArgumentException.class,
                () -> service.relocate(new ExternalDocumentRelocateRequest(
                        "same-col", "same-col", "crm", "ext-1", "etag:1"), "key-1"));
    }

    @Test
    void rejectsNonAsciiNamespace() {
        assertThrows(IllegalArgumentException.class,
                () -> service.relocate(new ExternalDocumentRelocateRequest(
                        "source-col", "target-col", "命名空间", "ext-1", "etag:1"),
                        "key-1"));
    }

    @Test
    void rejectsBlankIdempotencyKey() {
        stubHappyPathRepositories();

        assertThrows(IllegalArgumentException.class,
                () -> service.relocate(request(), "  "));
    }

    @Test
    void rejectsOversizedIdempotencyKey() {
        stubHappyPathRepositories();

        assertThrows(IllegalArgumentException.class,
                () -> service.relocate(request(), "k".repeat(256)));
    }

    @Test
    void rejectsWhenSourceDocumentIsMissing() {
        when(collectionResolver.requireActive(null, "source-col"))
                .thenReturn(collection(10L, "source-col"));
        when(collectionResolver.requireActive(null, "target-col"))
                .thenReturn(collection(20L, "target-col"));
        when(jdbcTemplate.queryForObject(contains("COUNT(*) FROM rag_document_sync_runs"),
                eq(Long.class), any(Object[].class))).thenReturn(0L);
        when(jdbcTemplate.query(contains("rag_document_idempotency_operations"),
                any(PreparedStatementSetter.class), any(RowMapper.class)))
                .thenReturn(List.of(99L));
        when(jdbcTemplate.queryForList(contains("FROM rag_documents"),
                any(Object[].class))).thenReturn(List.of());

        RagException error = assertThrows(RagException.class,
                () -> service.relocate(request(), "key-1"));
        assertEquals(ErrorCode.DOCUMENT_NOT_FOUND, error.getErrorCodeEnum());
    }

    @Test
    void rejectsWhenExpectedRevisionDoesNotMatchSource() {
        // 源行 revision 为 etag:2，请求期望 etag:stale → 修订冲突。
        stubHappyPathRepositories();

        RagException mismatch = assertThrows(RagException.class,
                () -> service.relocate(new ExternalDocumentRelocateRequest(
                        "source-col", "target-col", "crm", "cms:article:1",
                        "etag:stale"), "key-2"));
        assertEquals(ErrorCode.DOCUMENT_REVISION_CONFLICT,
                mismatch.getErrorCodeEnum());
    }

    @Test
    void rejectsWhenTargetIdentityAlreadyExists() {
        when(collectionResolver.requireActive(null, "source-col"))
                .thenReturn(collection(10L, "source-col"));
        when(collectionResolver.requireActive(null, "target-col"))
                .thenReturn(collection(20L, "target-col"));
        when(collectionResolver.beginActiveWrites(any()))
                .thenReturn(List.of());
        when(jdbcTemplate.queryForObject(contains("COUNT(*) FROM rag_document_sync_runs"),
                eq(Long.class), any(Object[].class))).thenReturn(0L);
        when(jdbcTemplate.query(contains("rag_document_idempotency_operations"),
                any(PreparedStatementSetter.class), any(RowMapper.class)))
                .thenReturn(List.of(99L));
        Map<String, Object> sourceRow = Map.of(
                "id", 5L, "version", 3L, "document_revision", 2L,
                "external_id", "cms:article:1", "source_revision", "etag:2");
        when(jdbcTemplate.queryForList(contains("FROM rag_documents"),
                any(Object[].class)))
                .thenReturn(List.of(sourceRow))
                .thenReturn(List.of(sourceRow));
        when(jdbcTemplate.queryForObject(contains("RETURNING mutation_sequence"),
                eq(Long.class), any(Object[].class)))
                .thenReturn(10L)
                .thenReturn(20L);

        RagException error = assertThrows(RagException.class,
                () -> service.relocate(request(), "key-1"));
        assertEquals(ErrorCode.TARGET_EXTERNAL_IDENTITY_EXISTS,
                error.getErrorCodeEnum());
    }

    @Test
    void completesRelocationAndConfirmsCollectionWrites() {
        stubHappyPathRepositories();

        ExternalDocumentRelocateResponse response =
                service.relocate(request(), "key-1");

        assertEquals(5L, response.documentId());
        assertEquals("source-col", response.sourceCollectionKey());
        assertEquals("target-col", response.targetCollectionKey());
        assertEquals("RELOCATED", response.action());
        assertEquals(9, response.versionNumber());
        verify(collectionResolver, times(2)).confirmActiveWrite(any());
        verify(entityManager).clear();
        // 幂等记录以 SUCCEEDED 落盘，授权数组按 id 排序写入。
        verify(jdbcTemplate).update(contains("SET status = 'SUCCEEDED', "
                + "result_document_id"), eq(5L), anyString(),
                eq(10L), eq(20L), eq(99L));
    }

    @Test
    void rejectsIdempotencyKeyReuseWithDifferentFingerprint() {
        // 幂等键已存在但存储指纹与新请求不同 → 拒绝复用。
        when(collectionResolver.requireActive(null, "source-col"))
                .thenReturn(collection(10L, "source-col"));
        when(collectionResolver.requireActive(null, "target-col"))
                .thenReturn(collection(20L, "target-col"));
        when(jdbcTemplate.queryForObject(contains("COUNT(*) FROM rag_document_sync_runs"),
                eq(Long.class), any(Object[].class))).thenReturn(0L);
        when(jdbcTemplate.query(contains("rag_document_idempotency_operations"),
                any(PreparedStatementSetter.class), any(RowMapper.class)))
                .thenReturn(List.of());
        when(jdbcTemplate.queryForList(
                contains("rag_document_idempotency_operations"), any(Object[].class)))
                .thenReturn(List.of(Map.of(
                        "id", 99L,
                        "request_fingerprint", "different-fingerprint",
                        "status", "SUCCEEDED",
                        "result_payload", "{}",
                        "source_acl_id", 10L,
                        "target_acl_id", 20L,
                        "expired", false)));

        RagException error = assertThrows(RagException.class,
                () -> service.relocate(request(), "key-1"));
        assertEquals(ErrorCode.IDEMPOTENCY_KEY_REUSED, error.getErrorCodeEnum());
    }
}

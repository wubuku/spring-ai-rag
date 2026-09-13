package com.springairag.core.service;

import com.springairag.core.entity.RagDocument;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.config.RagProperties;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.RowMapper;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * relocate 条件 UPDATE 的 setter/rowMapper 真实执行（Batch 353）：
 * 此前 CAS 桩仅返回罐装列表，lambda\$relocate\$0 的 9 列绑定从未
 * 执行。本用例捕获 setter 并真实调用，逐参数断言绑定值，同时执
 * 行 RETURNING 行映射。
 */
class DocumentRelocationApplyUpdateTest {

    private JdbcTemplate jdbcTemplate;
    private com.springairag.core.service.CollectionIdentityResolver collectionResolver;
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

    private com.springairag.core.entity.RagCollection collection(long id, String key) {
        var collection = new com.springairag.core.entity.RagCollection();
        collection.setId(id);
        collection.setCollectionKey(key);
        return collection;
    }

    @Test
    @SuppressWarnings("unchecked")
    void conditionalUpdateBindsAllColumnsAndReadsReturningId() throws Exception {
        when(collectionResolver.requireActive(null, "source-col"))
                .thenReturn(collection(10L, "source-col"));
        when(collectionResolver.requireActive(null, "target-col"))
                .thenReturn(collection(20L, "target-col"));
        when(collectionResolver.beginActiveWrites(List.of(10L, 20L)))
                .thenReturn(List.of(
                        new com.springairag.core.service.CollectionIdentityResolver.ActiveCollectionToken(10L, 1),
                        new com.springairag.core.service.CollectionIdentityResolver.ActiveCollectionToken(20L, 1)));
        when(jdbcTemplate.queryForObject(contains("COUNT(*) FROM rag_document_sync_runs"),
                eq(Long.class), any(Object[].class))).thenReturn(0L);
        // reserve：幂等操作插入命中。
        when(jdbcTemplate.query(contains("rag_document_idempotency_operations"),
                any(PreparedStatementSetter.class), any(RowMapper.class)))
                .thenReturn(List.of(99L));
        // findDocument：源命中；目标地址无 retired 记录。
        Map<String, Object> sourceRow = Map.of(
                "id", 5L, "version", 3L, "document_revision", 2L,
                "external_id", "cms:article:1", "source_revision", "etag:2");
        when(jdbcTemplate.queryForList(contains("FROM rag_documents"),
                any(Object[].class)))
                .thenReturn(List.of(sourceRow))
                .thenReturn(List.of());
        when(jdbcTemplate.queryForList(
                contains("rag_document_relocated_addresses"), any(Object[].class)))
                .thenReturn(List.of());
        // 序列分配：source → 10，target → 20。
        when(jdbcTemplate.queryForObject(contains("RETURNING mutation_sequence"),
                eq(Long.class), any(Object[].class)))
                .thenReturn(10L)
                .thenReturn(20L);

        // CAS 条件 UPDATE：真实执行 setter + RETURNING 行映射。
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        when(rs.getLong(1)).thenReturn(5L);
        ArgumentCaptor<PreparedStatementSetter> setters =
                ArgumentCaptor.forClass(PreparedStatementSetter.class);
        when(jdbcTemplate.query(contains("SET collection_id = ?"),
                setters.capture(), any(RowMapper.class)))
                .thenAnswer(invocation -> {
                    PreparedStatementSetter setter = invocation.getArgument(1);
                    setter.setValues(ps);
                    RowMapper<Long> mapper = invocation.getArgument(2);
                    return List.of(mapper.mapRow(rs, 0));
                });
        when(jdbcTemplate.update(anyString(), any(Object[].class)))
                .thenReturn(1);
        RagDocument relocated = new RagDocument();
        relocated.setId(5L);
        relocated.setSourceNamespace("crm");
        relocated.setExternalId("cms:article:1");
        relocated.setSourceRevision("etag:2");
        relocated.setDocumentRevision(3L);
        when(documentRepository.findById(5L)).thenReturn(java.util.Optional.of(relocated));
        var version = mock(com.springairag.core.entity.RagDocumentVersion.class);
        when(version.getVersionNumber()).thenReturn(9);
        when(versionService.forceRecordVersion(eq(relocated), eq("RELOCATE"), anyString()))
                .thenReturn(version);
        when(lifecycleService.read(relocated)).thenReturn(null);

        var response = service.relocate(new com.springairag.api.dto.ExternalDocumentRelocateRequest(
                "source-col", "target-col", "crm", "cms:article:1", "etag:2"), "key-1");

        assertEquals("RELOCATED", response.action());
        assertEquals(5L, response.documentId());
        // setter 真实执行：9 列绑定逐一断言。
        verify(ps).setLong(1, 20L);
        verify(ps).setLong(2, 20L);
        verify(ps).setLong(3, 5L);
        verify(ps).setLong(4, 10L);
        verify(ps).setString(5, "crm");
        verify(ps).setString(6, "cms:article:1");
        verify(ps).setString(7, "etag:2");
        verify(ps).setLong(8, 3L);
        verify(ps).setLong(9, 2L);
    }
}

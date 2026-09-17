package com.springairag.core.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.enums.EmbeddingPolicy;
import com.springairag.core.config.RagProperties;
import com.springairag.core.embeddingjob.EmbeddingDispatchService;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.entity.RagDocumentVersion;
import com.springairag.core.repository.RagEmbeddingRepository;
import com.springairag.core.exception.DocumentRevisionConflictException;
import com.springairag.core.service.DocumentEmbedService;
import com.springairag.core.service.DocumentLifecycleService;
import com.springairag.core.repository.RagDocumentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.web.context.request.RequestContextHolder;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DocumentMutationService 外部 upsert 事务方法守卫长尾（Batch 498，
 * JaCoCo 驱动，反射调用私有 20 参 upsertExternalInTransaction）：
 * 新身份携带 expectedSourceRevision 的冲突、同版本不同受管字段的
 * 冲突，以及新身份正常创建（saveAndFlush 落库）。
 */
class DocumentMutationUpsertExternalConflictTailTest {

    private RagDocumentRepository documentRepository;
    private JdbcTemplate jdbcTemplate;
    private CollectionIdentityResolver collectionResolver;
    private DocumentMutationService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        documentRepository = mock(RagDocumentRepository.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        collectionResolver = mock(CollectionIdentityResolver.class);
        PlatformTransactionManager transactionManager =
                mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any()))
                .thenReturn(mock(TransactionStatus.class));
        // 命名空间序列分配返回自增值。
        lenient().when(jdbcTemplate.queryForObject(
                anyString(),
                org.mockito.ArgumentMatchers.eq(Long.class),
                any(), any())).thenReturn(1L);
        lenient().when(collectionResolver.beginActiveWrite(10L))
                .thenReturn(new CollectionIdentityResolver.ActiveCollectionToken(10L, 1L));
        DocumentVersionService versionService =
                mock(DocumentVersionService.class);
        lenient().when(versionService.forceRecordVersion(
                any(RagDocument.class), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    RagDocumentVersion version = new RagDocumentVersion();
                    version.setVersionNumber(6);
                    return version;
                });
        lenient().when(documentRepository.saveAndFlush(any(RagDocument.class)))
                .thenAnswer(invocation -> {
                    RagDocument doc = invocation.getArgument(0);
                    if (doc.getId() == null) {
                        doc.setId(41L);
                    }
                    return doc;
                });
        service = new DocumentMutationService(
                documentRepository,
                mock(RagEmbeddingRepository.class),
                collectionResolver,
                versionService,
                mock(EmbeddingDispatchService.class),
                mock(DocumentEmbedService.class),
                mock(DocumentLifecycleService.class),
                jdbcTemplate,
                new ObjectMapper(),
                new RagProperties(),
                transactionManager);
        lenient().when(documentRepository.saveAndFlush(any(RagDocument.class)))
                .thenAnswer(invocation -> {
                    RagDocument doc = invocation.getArgument(0);
                    if (doc.getId() == null) {
                        doc.setId(41L);
                    }
                    return doc;
                });
        lenient().when(documentRepository.saveAndFlush(any(RagDocument.class)))
                .thenAnswer(invocation -> {
                    RagDocument doc = invocation.getArgument(0);
                    if (doc.getId() == null) {
                        doc.setId(41L);
                    }
                    return doc;
                });
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    private RagDocument existingDocument(String revision, String title) {
        RagDocument document = new RagDocument();
        document.setId(41L);
        document.setCollectionId(10L);
        document.setSourceNamespace("default");
        document.setExternalId("doc-1");
        document.setSourceRevision(revision);
        document.setTitle(title);
        document.setContent("First content");
        document.setContentHash(
                com.springairag.core.util.DigestUtils.sha256("First content"));
        document.setDocumentType("text");
        document.setEnabled(Boolean.TRUE);
        document.setSource("connector://x");
        document.setMetadata(Map.of());
        return document;
    }

    /** 反射调用私有 20 参 upsertExternalInTransaction（解包受检异常）。 */
    private Object upsertExternalInTransaction(
            boolean jsonRecord,
            String sourceRevision,
            String expectedSourceRevision,
            String title,
            RagDocument existing) throws Throwable {
        when(documentRepository
                .findByCollectionIdAndSourceNamespaceAndExternalId(
                        10L, "default", "doc-1"))
                .thenReturn(Optional.ofNullable(existing));
        Method method = DocumentMutationService.class.getDeclaredMethod(
                "upsertExternalInTransaction",
                Long.class, String.class, String.class, String.class,
                String.class, String.class, String.class, String.class,
                String.class, String.class, Map.class, JsonNode.class,
                boolean.class, EmbeddingPolicy.class, String.class,
                String.class, Boolean.class,
                java.time.LocalDateTime.class, Long.class, boolean.class);
        method.setAccessible(true);
        try {
            return method.invoke(service,
                    10L, "kb", "default", "doc-1",
                    sourceRevision, expectedSourceRevision, title,
                    "First content", "connector://x", "text",
                    Map.of(), null, jsonRecord, EmbeddingPolicy.SKIP,
                    "EXTERNAL_UPSERT", null, null, null, null, false);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw e.getCause();
        }
    }

    @Test
    void newIdentityWithExpectedRevisionConflictsAsDocument() {
        DocumentRevisionConflictException error =
                assertThrows(DocumentRevisionConflictException.class,
                        () -> upsertExternalInTransaction(false, "rev-1",
                                "rev-0", "First title", null));
        assertTrue(error.getMessage().contains("must be omitted"),
                "应报期望版本多余: " + error.getMessage());
    }

    @Test
    void sameRevisionWithDifferentManagedFieldsConflicts() {
        RagDocument existing = existingDocument("rev-1", "First title");

        DocumentRevisionConflictException error =
                assertThrows(DocumentRevisionConflictException.class,
                        () -> upsertExternalInTransaction(false, "rev-1",
                                null, "Different title", existing));
        assertTrue(error.getMessage().contains("different managed fields"),
                "应报受管字段漂移: " + error.getMessage());
    }

    @Test
    void sameRevisionSameFieldsIsUnchanged() throws Throwable {
        RagDocument existing = existingDocument("rev-1", "First title");

        Object prepared = upsertExternalInTransaction(false, "rev-1",
                null, "First title", existing);

        String text = String.valueOf(prepared);
        assertTrue(text.contains("UNCHANGED"),
                "同版本同字段应 UNCHANGED: " + text);
        verify(documentRepository, org.mockito.Mockito.never())
                .saveAndFlush(any());
    }

    @Test
    void newExternalDocumentIsCreated() throws Throwable {
        Object prepared = upsertExternalInTransaction(false, "rev-1",
                null, "First title", null);

        String text = String.valueOf(prepared);
        assertTrue(text.contains("CREATED"),
                "新身份应 CREATED: " + text);
        verify(documentRepository).saveAndFlush(any(RagDocument.class));
    }

}

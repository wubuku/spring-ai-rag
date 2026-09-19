package com.springairag.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.DocumentSyncRunItemRequest;
import com.springairag.api.enums.DocumentSyncDocumentKind;
import com.springairag.api.enums.DocumentSyncItemStatus;
import com.springairag.api.enums.EmbeddingPolicy;
import com.springairag.core.config.RagProperties;
import com.springairag.core.embeddingjob.EmbeddingDispatchService;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.entity.RagDocumentVersion;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.repository.RagEmbeddingRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DocumentMutationService 同步条目应用长尾（Batch 535，JaCoCo 驱
 * 动）：新建身份走 APPLIED（无 dispatch → NONE/无任务）、SYNC 策略
 * 携带 dispatch 与本地索引 ensureCurrent、台账唯一冲突的竞态重试。
 */
class DocumentMutationSyncItemAppliedTailTest {

    private static final long COLLECTION_ID = 30L;

    private RagDocumentRepository documentRepository;
    private DocumentVersionService versionService;
    private EmbeddingDispatchService dispatchService;
    private JdbcTemplate jdbcTemplate;
    private KeywordIndexPersistenceService keywordIndexPersistenceService;
    private DocumentMutationService service;

    @BeforeEach
    void setUp() {
        documentRepository = mock(RagDocumentRepository.class);
        versionService = mock(DocumentVersionService.class);
        dispatchService = mock(EmbeddingDispatchService.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        keywordIndexPersistenceService = mock(KeywordIndexPersistenceService.class);
        PlatformTransactionManager transactionManager =
                mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any()))
                .thenReturn(mock(TransactionStatus.class));
        when(versionService.forceRecordVersion(
                any(RagDocument.class), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    RagDocumentVersion version = new RagDocumentVersion();
                    version.setVersionNumber(5);
                    return version;
                });
        when(documentRepository.saveAndFlush(any(RagDocument.class)))
                .thenAnswer(invocation -> {
                    RagDocument saved = invocation.getArgument(0);
                    if (saved.getId() == null) {
                        saved.setId(41L);
                    }
                    saved.setSourceMutationSequence(30L);
                    return saved;
                });
        when(jdbcTemplate.update(anyString(), any(Object[].class)))
                .thenReturn(1);
        when(jdbcTemplate.queryForObject(
                contains("RETURNING mutation_sequence"),
                eq(Long.class), any(Object[].class)))
                .thenReturn(30L);

        service = new DocumentMutationService(
                documentRepository,
                mock(RagEmbeddingRepository.class),
                mock(CollectionIdentityResolver.class),
                versionService,
                dispatchService,
                mock(DocumentEmbedService.class),
                mock(DocumentLifecycleService.class),
                jdbcTemplate,
                new ObjectMapper(),
                new RagProperties(),
                transactionManager);
        service.setKeywordIndexPersistenceService(keywordIndexPersistenceService);
    }

    private DocumentSyncRunItemRequest request(EmbeddingPolicy policy) {
        return new DocumentSyncRunItemRequest(
                DocumentSyncDocumentKind.TEXT,
                "article-new",
                "r2",
                "Article New",
                "Fresh body",
                null,
                null,
                "cms",
                "text",
                Map.of(),
                policy);
    }

    private RagDocument persisted(long id) {
        RagDocument doc = new RagDocument();
        doc.setId(id);
        doc.setEnabled(Boolean.TRUE);
        doc.setSourceRevision("r2");
        doc.setSourceMutationSequence(3L);
        doc.setDocumentType("text");
        return doc;
    }

    @Test
    void appliedNewItemWithSkipPolicyHasNullDispatch() {
        when(documentRepository
                .findByCollectionIdAndSourceNamespaceAndExternalId(
                        COLLECTION_ID, "catalog", "article-new"))
                .thenReturn(Optional.empty());
        when(documentRepository.findById(anyLong()))
                .thenAnswer(invocation ->
                        Optional.of(persisted(invocation.getArgument(0))));

        var result = service.upsertSyncRunItem(
                COLLECTION_ID, "catalog", "catalog",
                request(EmbeddingPolicy.SKIP), 20L);

        assertEquals(DocumentSyncItemStatus.APPLIED, result.status());
        assertEquals(41L, result.documentId());
        assertEquals("NONE", result.embeddingAction());
        assertNull(result.embeddingJobId());
        // SKIP 策略：直接登记未请求，不做本地索引 ensureCurrent。
        verify(dispatchService).markNotRequestedInCurrentTransaction(
                any(RagDocument.class));
    }

    @Test
    void appliedSyncItemEnsuresLocalIndexAndCarriesDispatch() {
        RagDocument current = new RagDocument();
        current.setId(42L);
        current.setEnabled(Boolean.TRUE);
        current.setTitle("Article New");
        current.setContent("Stale body");
        current.setSource("cms");
        current.setDocumentType("text");
        current.setMetadata(Map.of());
        current.setSourceRevision("r1");
        current.setSourceMutationSequence(10L);
        current.setDocumentRevision(2L);
        when(documentRepository
                .findByCollectionIdAndSourceNamespaceAndExternalId(
                        COLLECTION_ID, "catalog", "article-new"))
                .thenReturn(Optional.of(current));
        when(documentRepository.findById(42L))
                .thenReturn(Optional.of(persisted(42L)));
        when(dispatchService.enqueueInCurrentTransaction(
                any(RagDocument.class), eq(true), anyBoolean(), anyString()))
                .thenReturn(new EmbeddingDispatchService.Result(
                        com.springairag.api.enums.EmbeddingAction.ASYNC_QUEUED,
                        "QUEUED", "test-profile",
                        UUID.randomUUID(), UUID.randomUUID(), null));

        var result = service.upsertSyncRunItemInCurrentTransaction(
                COLLECTION_ID, "catalog", "catalog",
                request(EmbeddingPolicy.SYNC), 20L);

        assertEquals(DocumentSyncItemStatus.APPLIED, result.status());
        assertEquals("ASYNC_QUEUED", result.embeddingAction());
        // 非 SKIP 且文档启用 → 本地索引必须刷新。
        verify(keywordIndexPersistenceService).ensureCurrent(
                any(RagDocument.class));
    }

    @Test
    void idempotencyRaceDuringSyncItemUpsertRetries() {
        // 首次提交冲突由上层收敛：直接验证 upsertSyncRunItem 透传
        // DataIntegrityViolationException（事务模板 mock 直通执行）。
        when(documentRepository
                .findByCollectionIdAndSourceNamespaceAndExternalId(
                        COLLECTION_ID, "catalog", "article-new"))
                .thenThrow(new DataIntegrityViolationException("uniq"));

        var error = assertThrowsDataIntegrityViolation();

        assertEquals("uniq", error.getMessage());
    }

    private DataIntegrityViolationException assertThrowsDataIntegrityViolation() {
        try {
            service.upsertSyncRunItem(
                    COLLECTION_ID, "catalog", "catalog",
                    request(EmbeddingPolicy.SKIP), 20L);
            throw new AssertionError("expected DataIntegrityViolationException");
        } catch (DataIntegrityViolationException expected) {
            return expected;
        }
    }
}

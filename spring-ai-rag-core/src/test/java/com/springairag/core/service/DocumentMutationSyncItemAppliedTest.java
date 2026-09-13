package com.springairag.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.DocumentSyncRunItemRequest;
import com.springairag.api.enums.DocumentSyncDocumentKind;
import com.springairag.api.enums.EmbeddingPolicy;
import com.springairag.api.enums.DocumentSyncItemStatus;
import com.springairag.core.embeddingjob.EmbeddingDispatchService;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.entity.RagDocumentVersion;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.repository.RagEmbeddingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 快照同步条目 APPLIED 分支（Batch 383）：内容变化驱动 UPDATED
 * 墓碑外条目，派发为 NONE（SKIP 策略无派发结果）。
 */
class DocumentMutationSyncItemAppliedTest {

    private static final long COLLECTION_ID = 10L;

    private RagDocumentRepository documentRepository;
    private DocumentMutationService service;

    @BeforeEach
    void setUp() {
        documentRepository = mock(RagDocumentRepository.class);
        CollectionIdentityResolver resolver =
                mock(CollectionIdentityResolver.class);
        DocumentVersionService versionService =
                mock(DocumentVersionService.class);
        EmbeddingDispatchService dispatchService =
                mock(EmbeddingDispatchService.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.update(anyString(), any(Object[].class)))
                .thenReturn(1);
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class),
                any(Object[].class))).thenReturn(6L);
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
                .thenAnswer(invocation -> invocation.getArgument(0));

        service = new DocumentMutationService(
                documentRepository,
                mock(RagEmbeddingRepository.class),
                resolver,
                versionService,
                dispatchService,
                mock(DocumentEmbedService.class),
                mock(DocumentLifecycleService.class),
                jdbcTemplate,
                new ObjectMapper(),
                new RagProperties(),
                transactionManager);
    }

    private RagDocument current() {
        RagDocument value = new RagDocument();
        value.setId(41L);
        value.setEnabled(Boolean.TRUE);
        value.setTitle("Article");
        value.setContent("Old body");
        value.setContentHash(
                com.springairag.core.util.DigestUtils.sha256("Old body"));
        value.setSource("cms");
        value.setDocumentType("text");
        value.setMetadata(Map.of());
        value.setSourceRevision("r1");
        value.setSourceMutationSequence(10L);
        value.setDocumentRevision(2L);
        return value;
    }

    private DocumentSyncRunItemRequest changedRequest() {
        return new DocumentSyncRunItemRequest(
                DocumentSyncDocumentKind.TEXT,
                "article-1",
                "r2",
                "Article",
                "New searchable body",
                null,
                null,
                "cms",
                "text",
                Map.of(),
                EmbeddingPolicy.SKIP);
    }

    @Test
    void changedSnapshotItemIsAppliedWithNoDispatch() {
        when(documentRepository
                .findByCollectionIdAndSourceNamespaceAndExternalId(
                        COLLECTION_ID, "cms-main", "article-1"))
                .thenReturn(Optional.of(current()));
        when(documentRepository.findById(41L))
                .thenReturn(Optional.of(current()));
        java.util.concurrent.atomic.AtomicReference<RagDocument> saved =
                new java.util.concurrent.atomic.AtomicReference<>();
        when(documentRepository.saveAndFlush(any(RagDocument.class)))
                .thenAnswer(invocation -> {
                    RagDocument value = invocation.getArgument(0);
                    value.setDocumentRevision(3L);
                    saved.set(value);
                    return value;
                });
        when(documentRepository.findById(41L))
                .thenAnswer(invocation ->
                        java.util.Optional.ofNullable(saved.get()));

        DocumentMutationService.SyncItemMutation result =
                service.upsertSyncRunItemInCurrentTransaction(
                        COLLECTION_ID, "catalog", "cms-main",
                        changedRequest(), 20L);

        assertEquals(DocumentSyncItemStatus.APPLIED, result.status());
        assertEquals(41L, result.documentId());
        assertEquals("r2", result.sourceRevision());
        assertEquals("NONE", result.embeddingAction());
        assertNull(result.embeddingJobId());
        assertNull(result.error());
    }
}

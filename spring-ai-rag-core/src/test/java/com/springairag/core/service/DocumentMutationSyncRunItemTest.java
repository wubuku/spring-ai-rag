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
import com.springairag.core.util.DigestUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 快照条目应用（upsertSyncRunItemInCurrentTransaction）分支语义：
 * 快照后新变更跳过（SKIPPED_NEWER_MUTATION）、同 revision 同状态
 * UNCHANGED 重放、json-record 缺失/null payload 拒绝。
 */
class DocumentMutationSyncRunItemTest {

    private static final long COLLECTION_ID = 10L;

    private DocumentMutationService service;
    private RagDocumentRepository documentRepository;
    private DocumentVersionService versionService;
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        documentRepository = mock(RagDocumentRepository.class);
        RagEmbeddingRepository embeddingRepository =
                mock(RagEmbeddingRepository.class);
        CollectionIdentityResolver resolver =
                mock(CollectionIdentityResolver.class);
        versionService = mock(DocumentVersionService.class);
        EmbeddingDispatchService dispatchService =
                mock(EmbeddingDispatchService.class);
        DocumentEmbedService documentEmbedService =
                mock(DocumentEmbedService.class);
        DocumentLifecycleService lifecycleService =
                mock(DocumentLifecycleService.class);
        jdbcTemplate = mock(JdbcTemplate.class);
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
                embeddingRepository,
                resolver,
                versionService,
                dispatchService,
                documentEmbedService,
                lifecycleService,
                jdbcTemplate,
                new ObjectMapper(),
                new RagProperties(),
                transactionManager);
    }

    private DocumentSyncRunItemRequest textRequest() {
        return new DocumentSyncRunItemRequest(
                DocumentSyncDocumentKind.TEXT,
                "article-1",
                "r1",
                "Article",
                "Searchable body",
                null,
                null,
                "cms",
                "text",
                Map.of(),
                EmbeddingPolicy.SKIP);
    }

    @Test
    void skipsItemsWhoseDocumentMutatedAfterTheSnapshotStarted() {
        RagDocument current = new RagDocument();
        current.setId(41L);
        current.setSourceRevision("r9");
        current.setSourceMutationSequence(25L);
        when(documentRepository
                .findByCollectionIdAndSourceNamespaceAndExternalId(
                        COLLECTION_ID, "cms-main", "article-1"))
                .thenReturn(Optional.of(current));

        DocumentMutationService.SyncItemMutation result =
                service.upsertSyncRunItemInCurrentTransaction(
                        COLLECTION_ID, "catalog", "cms-main",
                        textRequest(), 20L);

        assertEquals(DocumentSyncItemStatus.SKIPPED_NEWER_MUTATION,
                result.status());
        assertEquals(41L, result.documentId());
        assertEquals("r9", result.sourceRevision());
        assertEquals("NONE", result.embeddingAction());
        // 跳过路径不得触碰任何持久化状态。
        verify(documentRepository, never()).saveAndFlush(any());
        verify(versionService, never()).forceRecordVersion(
                any(), anyString(), anyString());
    }

    @Test
    void replaysIdenticalSnapshotItemAsUnchanged() {
        RagDocument current = new RagDocument();
        current.setId(41L);
        current.setEnabled(Boolean.TRUE);
        current.setTitle("Article");
        current.setContent("Searchable body");
        current.setContentHash(DigestUtils.sha256("Searchable body"));
        current.setSource("cms");
        current.setDocumentType("text");
        current.setMetadata(Map.of());
        current.setSourceRevision("r1");
        current.setSourceMutationSequence(10L);
        current.setDocumentRevision(2L);
        when(documentRepository
                .findByCollectionIdAndSourceNamespaceAndExternalId(
                        COLLECTION_ID, "cms-main", "article-1"))
                .thenReturn(Optional.of(current));
        when(documentRepository.findById(41L))
                .thenReturn(Optional.of(current));

        DocumentMutationService.SyncItemMutation result =
                service.upsertSyncRunItemInCurrentTransaction(
                        COLLECTION_ID, "catalog", "cms-main",
                        textRequest(), 20L);

        assertEquals(DocumentSyncItemStatus.UNCHANGED, result.status());
        verify(documentRepository, never()).saveAndFlush(any());
        verify(versionService, never()).forceRecordVersion(
                any(), anyString(), anyString());
        verify(jdbcTemplate, never()).update(anyString(), any(), any());
    }

    @Test
    void rejectsJsonRecordSnapshotItemWithoutPayload() {
        DocumentSyncRunItemRequest request = new DocumentSyncRunItemRequest(
                DocumentSyncDocumentKind.JSON_RECORD,
                "record-1",
                "r1",
                "Record",
                null,
                "Searchable body",
                null,
                "cms",
                null,
                Map.of(),
                EmbeddingPolicy.SKIP);

        assertThrows(NullPointerException.class,
                () -> service.upsertSyncRunItemInCurrentTransaction(
                        COLLECTION_ID, "catalog", "cms-main",
                        request, 20L));
    }

    @Test
    void rejectsJsonRecordSnapshotItemWithNullNodePayload() {
        DocumentSyncRunItemRequest request = new DocumentSyncRunItemRequest(
                DocumentSyncDocumentKind.JSON_RECORD,
                "record-1",
                "r1",
                "Record",
                null,
                "Searchable body",
                new ObjectMapper().createObjectNode().nullNode(),
                "cms",
                null,
                Map.of(),
                EmbeddingPolicy.SKIP);

        assertThrows(IllegalArgumentException.class,
                () -> service.upsertSyncRunItemInCurrentTransaction(
                        COLLECTION_ID, "catalog", "cms-main",
                        request, 20L));
    }
}

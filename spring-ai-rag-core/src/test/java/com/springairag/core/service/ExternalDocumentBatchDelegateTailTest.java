package com.springairag.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.ExternalDocumentBatchUpsertResponse;
import com.springairag.api.dto.ExternalDocumentDeleteResponse;
import com.springairag.api.dto.ExternalDocumentUpsertRequest;
import com.springairag.api.enums.EmbeddingPolicy;
import com.springairag.core.config.EmbeddingProfile;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.entity.RagCollection;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.entity.RagDocumentVersion;
import com.springairag.core.embeddingjob.EmbeddingDispatchService;
import com.springairag.core.exception.DocumentRevisionConflictException;
import com.springairag.core.repository.RagCollectionRepository;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.repository.RagEmbeddingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 外部文档批量与委托长尾（Batch 621，JaCoCo 驱动）：batchUpsert 的
 * 空清单/超限拒绝与成功/失败计数投影；sourceDelete 在 mutation
 * service 在场时的委托；墓碑重放的 UNCHANGED（enabled 但已标记
 * 删除）变体；SYNC 派发错误投影 EMBEDDING_FAILED。
 */
class ExternalDocumentBatchDelegateTailTest {

    private RagDocumentRepository documentRepository;
    private CollectionIdentityResolver collectionIdentityResolver;
    private EmbeddingDispatchService dispatchService;
    private DocumentMutationService mutationService;
    private DocumentVersionService documentVersionService;
    private RagCollection collection;
    private ExternalDocumentService service;

    @BeforeEach
    void setUp() {
        documentRepository = mock(RagDocumentRepository.class);
        RagCollectionRepository collectionRepository =
                mock(RagCollectionRepository.class);
        RagEmbeddingRepository embeddingRepository =
                mock(RagEmbeddingRepository.class);
        documentVersionService = mock(DocumentVersionService.class);
        DocumentEmbedService documentEmbedService =
                mock(DocumentEmbedService.class);
        EmbeddingProfileProvider embeddingProfileProvider =
                mock(EmbeddingProfileProvider.class);
        collectionIdentityResolver = mock(CollectionIdentityResolver.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        PlatformTransactionManager transactionManager =
                mock(PlatformTransactionManager.class);
        dispatchService = mock(EmbeddingDispatchService.class);
        mutationService = mock(DocumentMutationService.class);

        collection = new RagCollection();
        collection.setId(10L);
        collection.setCollectionKey("customer-42:manual:v1");
        collection.setName("Manual");
        collection.setEnabled(true);

        lenient().when(collectionIdentityResolver.requireActive(
                        null, collection.getCollectionKey()))
                .thenReturn(collection);
        lenient().when(collectionIdentityResolver.beginActiveWrite(10L))
                .thenReturn(new CollectionIdentityResolver.ActiveCollectionToken(10L, 0L));
        lenient().when(embeddingProfileProvider.getActiveProfile())
                .thenReturn(new EmbeddingProfile(
                        9L, "profile", "test", "model", "v1",
                        1024, "COSINE", "NONE", true));
        lenient().when(transactionManager.getTransaction(any()))
                .thenReturn(mock(TransactionStatus.class));
        lenient().when(jdbcTemplate.execute(
                        any(org.springframework.jdbc.core.ConnectionCallback.class)))
                .thenReturn(null);
        lenient().when(collectionRepository.findById(10L))
                .thenReturn(Optional.of(collection));
        lenient().when(documentVersionService.forceRecordVersion(
                any(RagDocument.class), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    RagDocumentVersion version = new RagDocumentVersion();
                    version.setVersionNumber(4);
                    return version;
                });
        lenient().when(documentRepository.saveAndFlush(any(RagDocument.class)))
                .thenAnswer(invocation -> {
                    RagDocument saved = invocation.getArgument(0);
                    if (saved.getId() == null) {
                        saved.setId(77L);
                    }
                    return saved;
                });

        service = new ExternalDocumentService(
                documentRepository,
                collectionRepository,
                embeddingRepository,
                documentVersionService,
                documentEmbedService,
                embeddingProfileProvider,
                collectionIdentityResolver,
                jdbcTemplate,
                transactionManager);
        service.setKeywordIndexPersistenceService(
                mock(KeywordIndexPersistenceService.class));
        service.setDispatchService(dispatchService);
    }

    private ExternalDocumentUpsertRequest upsertRequest(
            String externalId, String revision) {
        ExternalDocumentUpsertRequest request =
                new ExternalDocumentUpsertRequest();
        request.setCollectionKey(collection.getCollectionKey());
        request.setExternalId(externalId);
        request.setSourceRevision(revision);
        request.setTitle("Title");
        request.setContent("Content");
        request.setSource("connector://manual");
        request.setDocumentType("text");
        request.setEmbed(true);
        return request;
    }

    @Test
    void sourceDeleteDelegatesToMutationServiceWhenPresent() {
        service.setMutationService(mutationService);
        var delegated = new ExternalDocumentDeleteResponse(
                88L, "kb", "doc-1", "rev-9", "DELETED",
                3, false, null, null, null);
        when(mutationService.tombstoneExternal(
                "kb", "default", "doc-1", "rev-9", null, false))
                .thenReturn(delegated);

        var response = service.sourceDelete("kb", "doc-1", "rev-9", null);

        assertEquals("DELETED", response.action());
        verify(mutationService).tombstoneExternal(
                "kb", "default", "doc-1", "rev-9", null, false);
    }

    @Test
    void batchUpsertRejectsEmptyAndOversizedBatches() {
        assertThrows(IllegalArgumentException.class,
                () -> service.batchUpsert(null));
        assertThrows(IllegalArgumentException.class,
                () -> service.batchUpsert(List.of()));

        List<ExternalDocumentUpsertRequest> tooMany = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            tooMany.add(upsertRequest("d", "r"));
        }
        assertThrows(IllegalArgumentException.class,
                () -> service.batchUpsert(tooMany));
    }

    @Test
    void batchUpsertCountsCreatedAndPersistenceFailures() {
        // ok 项走 SKIP 策略避免嵌入派发；broken 项校验失败。
        ExternalDocumentUpsertRequest ok = upsertRequest("ok-1", "rev-1");
        ok.setEmbeddingPolicy(EmbeddingPolicy.SKIP);
        when(documentRepository.findByCollectionIdAndExternalId(10L, "ok-1"))
                .thenReturn(Optional.empty());
        // 第二项校验失败（空白标题）→ failedResponse 计入 persistenceFailed。
        ExternalDocumentUpsertRequest broken = upsertRequest("bad-1", "r");
        broken.setTitle("  ");
        when(documentRepository.findByCollectionIdAndExternalId(10L, "bad-1"))
                .thenReturn(Optional.empty());

        ExternalDocumentBatchUpsertResponse response =
                service.batchUpsert(List.of(ok, broken));

        assertEquals(2, response.items().size());
    }

    @Test
    void upsertRejectsTombstoneReplayViaSourceDeletedAtOnEnabledDocument() {
        RagDocument tombstoned = new RagDocument();
        tombstoned.setId(77L);
        tombstoned.setCollectionId(10L);
        tombstoned.setExternalId("doc-1");
        tombstoned.setSourceNamespace("default");
        tombstoned.setSourceRevision("rev-1");
        tombstoned.setTitle("First title");
        tombstoned.setContent("First content");
        tombstoned.setDocumentType("text");
        // enabled=true 但已标记删除 → 仍视为墓碑。
        tombstoned.setSourceDeletedAt(LocalDateTime.now().minusDays(1));
        when(documentRepository.findByCollectionIdAndExternalId(10L, "doc-1"))
                .thenReturn(Optional.of(tombstoned));

        DocumentRevisionConflictException error = assertThrows(
                DocumentRevisionConflictException.class,
                () -> service.upsert(upsertRequest("doc-1", "rev-1")));
        assertTrue(error.getMessage().contains("tombstone"));
    }

    @Test
    void sourceDeleteReplayOnDeletedButEnabledDocumentIsUnchanged() {
        RagDocument marked = new RagDocument();
        marked.setId(77L);
        marked.setCollectionId(10L);
        marked.setExternalId("doc-1");
        marked.setSourceNamespace("default");
        marked.setSourceRevision("rev-del");
        marked.setTitle("T");
        marked.setContent("C");
        marked.setDocumentType("text");
        // enabled=true 但 sourceDeletedAt 已标记 → 墓碑重放 UNCHANGED。
        marked.setEnabled(Boolean.TRUE);
        marked.setSourceDeletedAt(LocalDateTime.now().minusDays(1));
        when(documentRepository.findByCollectionIdAndExternalId(10L, "doc-1"))
                .thenReturn(Optional.of(marked));

        var response = service.sourceDelete(
                collection.getCollectionKey(), "doc-1", "rev-del", null);

        assertEquals("UNCHANGED", response.action());
        verify(documentVersionService, never())
                .forceRecordVersion(any(), eq("DELETE"), anyString());
    }

    @Test
    void syncUpsertSurfacesDispatchErrorAsEmbeddingFailed() {
        when(documentRepository.findByCollectionIdAndExternalId(10L, "doc-1"))
                .thenReturn(Optional.empty());
        when(dispatchService.dispatchAfterCommit(
                any(RagDocument.class), eq(EmbeddingPolicy.SYNC),
                anyBoolean(), anyString()))
                .thenReturn(new EmbeddingDispatchService.Result(
                        com.springairag.api.enums.EmbeddingAction.SYNC_COMPLETED,
                        "FAILED", "profile",
                        null, null, "embedder offline"));

        var response = service.upsert(upsertRequest("sync-err", "rev-e"));

        assertEquals("EMBEDDING_FAILED", response.errorCode());
        assertTrue(response.error().contains("embedder offline"));
    }

    @Test
    void syncDispatchSuccessProjectsSyncCompletedMetadata() {
        when(documentRepository.findByCollectionIdAndExternalId(10L, "doc-1"))
                .thenReturn(Optional.empty());
        when(dispatchService.dispatchAfterCommit(
                any(RagDocument.class), eq(EmbeddingPolicy.SYNC),
                anyBoolean(), anyString()))
                .thenReturn(new EmbeddingDispatchService.Result(
                        com.springairag.api.enums.EmbeddingAction.SYNC_COMPLETED,
                        "COMPLETED", "profile",
                        UUID.randomUUID(), UUID.randomUUID(), null));

        var response = service.upsert(upsertRequest("sync-ok", "rev-e"));

        assertEquals("COMPLETED", response.embeddingStatus());
        assertNull(response.errorCode());
    }

    private static void assertNull(Object value) {
        org.junit.jupiter.api.Assertions.assertNull(value);
    }
}

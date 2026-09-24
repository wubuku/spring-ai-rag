package com.springairag.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.ExternalDocumentUpsertRequest;
import com.springairag.api.enums.EmbeddingAction;
import com.springairag.api.enums.EmbeddingPolicy;
import java.util.Map;
import com.springairag.core.config.EmbeddingProfile;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.entity.RagCollection;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.entity.RagDocumentVersion;
import com.springairag.core.exception.DocumentRevisionConflictException;
import com.springairag.core.embeddingjob.EmbeddingDispatchService;
import com.springairag.core.repository.RagCollectionRepository;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.repository.RagEmbeddingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 外部文档删除重放与嵌入派发长尾（Batch 611，JaCoCo 驱动）：
 * sourceDelete 对墓碑重放返回 UNCHANGED、活文档同版本删除拒绝；
 * finishUpsert 对 ASYNC 派发结果与派发错误的元数据投影、SYNC 内联
 * 嵌入的失败/成功、新鲜嵌入 CACHED 投影。
 */
class ExternalDocumentDeleteDispatchTailTest {

    private static final EmbeddingProfile PROFILE = new EmbeddingProfile(
            9L, "profile", "test", "model", "v1",
            1024, "COSINE", "NONE", true);

    private RagDocumentRepository documentRepository;
    private RagEmbeddingRepository embeddingRepository;
    private DocumentVersionService documentVersionService;
    private DocumentEmbedService documentEmbedService;
    private EmbeddingProfileProvider embeddingProfileProvider;
    private EmbeddingDispatchService dispatchService;
    private RagCollection collection;
    private ExternalDocumentService service;

    @BeforeEach
    void setUp() {
        documentRepository = mock(RagDocumentRepository.class);
        RagCollectionRepository collectionRepository =
                mock(RagCollectionRepository.class);
        embeddingRepository = mock(RagEmbeddingRepository.class);
        documentVersionService = mock(DocumentVersionService.class);
        documentEmbedService = mock(DocumentEmbedService.class);
        embeddingProfileProvider = mock(EmbeddingProfileProvider.class);
        CollectionIdentityResolver collectionIdentityResolver =
                mock(CollectionIdentityResolver.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        PlatformTransactionManager transactionManager =
                mock(PlatformTransactionManager.class);
        dispatchService = mock(EmbeddingDispatchService.class);

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
                .thenReturn(PROFILE);
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

    private RagDocument liveDocument(String revision) {
        RagDocument document = new RagDocument();
        document.setId(77L);
        document.setCollectionId(10L);
        document.setExternalId("doc-1");
        document.setSourceNamespace("default");
        document.setSourceRevision(revision);
        document.setTitle("T");
        document.setContent("C");
        document.setDocumentType("text");
        document.setEnabled(Boolean.TRUE);
        document.setContentHash(
                com.springairag.core.util.DigestUtils.sha256("C"));
        return document;
    }

    private RagDocument tombstonedDocument(String revision) {
        RagDocument document = liveDocument(revision);
        document.setEnabled(Boolean.FALSE);
        document.setSourceDeletedAt(java.time.LocalDateTime.now().minusDays(1));
        return document;
    }

    @Test
    void sourceDeleteReplayOnTombstonedDocumentIsUnchanged() {
        when(documentRepository.findByCollectionIdAndExternalId(10L, "doc-1"))
                .thenReturn(Optional.of(tombstonedDocument("rev-del")));

        var response = service.sourceDelete(
                collection.getCollectionKey(), "doc-1", "rev-del", null);

        assertEquals("UNCHANGED", response.action());
        verify(documentVersionService, org.mockito.Mockito.never())
                .forceRecordVersion(any(), eq("DELETE"), anyString());
    }

    @Test
    void sourceDeleteOnLiveDocumentWithSameRevisionConflicts() {
        when(documentRepository.findByCollectionIdAndExternalId(10L, "doc-1"))
                .thenReturn(Optional.of(liveDocument("rev-1")));

        DocumentRevisionConflictException error = assertThrows(
                DocumentRevisionConflictException.class,
                () -> service.sourceDelete(
                        collection.getCollectionKey(), "doc-1", "rev-1", null));
        assertEquals("A source deletion must use a new sourceRevision",
                error.getMessage());
    }

    @Test
    void asyncUpsertProjectsQueuedDispatchResult() throws Exception {
        when(documentRepository.findByCollectionIdAndExternalId(10L, "doc-1"))
                .thenReturn(Optional.empty());
        when(documentEmbedService.hasFreshEmbedding(any(RagDocument.class)))
                .thenReturn(false);
        UUID jobId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        when(dispatchService.enqueueInCurrentTransaction(
                any(RagDocument.class), anyBoolean(), anyBoolean(), anyString()))
                .thenReturn(new EmbeddingDispatchService.Result(
                        com.springairag.api.enums.EmbeddingAction.ASYNC_QUEUED, "QUEUED", "profile-9",
                        jobId, batchId, null));

        var request = upsertRequest();
        request.setEmbeddingPolicy(EmbeddingPolicy.ASYNC);
        var response = service.upsert(request);

        assertEquals("QUEUED", response.embeddingStatus());
        assertEquals("profile-9", response.embeddingProfileKey());
        assertEquals(jobId, response.embeddingJobId());
        assertNull(response.errorCode());
    }

    private static void assertNull(Object value) {
        org.junit.jupiter.api.Assertions.assertNull(value);
    }

    @Test
    void asyncUpsertSurfacesDispatchErrorAsEmbeddingFailed() throws Exception {
        when(documentRepository.findByCollectionIdAndExternalId(10L, "doc-1"))
                .thenReturn(Optional.empty());
        var request = upsertRequest();
        request.setEmbeddingPolicy(EmbeddingPolicy.ASYNC);
        when(dispatchService.enqueueInCurrentTransaction(
                any(RagDocument.class), anyBoolean(), anyBoolean(), anyString()))
                .thenReturn(new EmbeddingDispatchService.Result(
                        com.springairag.api.enums.EmbeddingAction.ASYNC_QUEUED, "FAILED", "profile-9",
                        jobId(), batchId(), "model unavailable"));

        var response = service.upsert(request);

        assertEquals("EMBEDDING_FAILED", response.errorCode());
        assertEquals("model unavailable", response.error());
    }

    private UUID jobId() { return UUID.randomUUID(); }
    private UUID batchId() { return UUID.randomUUID(); }

    @Test
    void syncUpsertWithoutDispatcherEmbedsInlineAndReportsFailures() {
        // dispatchService 缺失 → SYNC 走内联 embedDocument。
        ExternalDocumentService noDispatch = service;
        noDispatch.setDispatchService(null);
        when(documentRepository.findByCollectionIdAndExternalId(10L, "doc-1"))
                .thenReturn(Optional.empty());
        when(documentEmbedService.hasFreshEmbedding(any(RagDocument.class)))
                .thenReturn(false);
        when(documentEmbedService.embedDocument(anyLong(), anyBoolean()))
                .thenReturn(Map.of("status", "FAILED",
                        "error", "embedder offline"));

        var response = noDispatch.upsert(upsertRequest());

        assertEquals("FAILED", response.embeddingStatus());
        assertEquals("EMBEDDING_FAILED", response.errorCode());
        assertEquals("embedder offline", response.error());
    }

    @Test
    void syncUpsertWithFreshEmbeddingReportsCached() {
        ExternalDocumentService noDispatch = service;
        noDispatch.setDispatchService(null);
        when(documentRepository.findByCollectionIdAndExternalId(10L, "doc-1"))
                .thenReturn(Optional.empty());
        when(documentEmbedService.hasFreshEmbedding(any(RagDocument.class)))
                .thenReturn(true);

        var response = noDispatch.upsert(upsertRequest());

        assertEquals("CACHED", response.embeddingStatus());
        assertNotNull(response.embeddingProfileKey());
    }

    private ExternalDocumentUpsertRequest upsertRequest() {
        ExternalDocumentUpsertRequest request =
                new ExternalDocumentUpsertRequest();
        request.setCollectionKey(collection.getCollectionKey());
        request.setExternalId("doc-1");
        request.setSourceRevision("rev-1");
        request.setTitle("First title");
        request.setContent("First content");
        request.setSource("connector://manual");
        request.setDocumentType("text");
        request.setEmbed(true);
        return request;
    }
}

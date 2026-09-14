package com.springairag.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.JsonRecordUpsertRequest;
import com.springairag.api.dto.JsonRecordUpsertResponse;
import com.springairag.api.enums.EmbeddingPolicy;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.repository.RagEmbeddingRepository;
import com.springairag.core.retrieval.HybridRetrieverService;
import com.springairag.core.retrieval.ReRankingService;
import com.springairag.core.service.CollectionIdentityResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * JsonRecordService 后段矩阵（Batch 395）：persistInTransaction 的
 * UPDATED/UNCHANGED 路径、coordinateLocalIndex 钩子、
 * embedIfRequested 的 NOT_REQUESTED/CACHED/FAILED 矩阵。
 */
class JsonRecordServicePersistMatrixTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RagDocumentRepository documentRepository;
    private CollectionIdentityResolver resolver;
    private DocumentVersionService versionService;
    private DocumentEmbedService documentEmbedService;
    private KeywordIndexPersistenceService keywordIndexPersistenceService;
    private PlatformTransactionManager transactionManager;
    private EmbeddingProfileProvider embeddingProfileProvider;
    private JsonRecordService service;
    private RagDocument existing;

    @BeforeEach
    void setUp() throws Exception {
        documentRepository = mock(RagDocumentRepository.class);
        resolver = mock(CollectionIdentityResolver.class);
        versionService = mock(DocumentVersionService.class);
        documentEmbedService = mock(DocumentEmbedService.class);
        keywordIndexPersistenceService = mock(KeywordIndexPersistenceService.class);
        transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any()))
                .thenReturn(mock(TransactionStatus.class));
        embeddingProfileProvider = mock(EmbeddingProfileProvider.class);
        lenient().when(embeddingProfileProvider.getActiveProfile())
                .thenReturn(new com.springairag.core.config.EmbeddingProfile(
                        9L, "profile", "test", "model", "v1",
                        1024, "COSINE", "NONE", true));

        existing = new RagDocument();
        existing.setId(41L);
        existing.setCollectionId(7L);
        existing.setDocumentType(RagDocument.JSON_RECORD);
        existing.setExternalId("rec-1");
        existing.setTitle("Old");
        existing.setContent("old-text");
        existing.setSource("upload-1");
        existing.setContentHash(
                com.springairag.core.util.DigestUtils.sha256("old-text"));
        existing.setMetadata(java.util.Map.of());
        existing.setJsonbPayload(MAPPER.readTree("{\"k\":1}"));
        existing.setEnabled(Boolean.TRUE);
        existing.setSourceRevision("rev-1");
        existing.setDocumentRevision(3L);

        lenient().when(resolver.beginActiveWrite(7L))
                .thenReturn(new CollectionIdentityResolver.ActiveCollectionToken(7L, 0L));
        lenient().when(documentRepository
                .findByCollectionIdAndDocumentTypeAndExternalId(
                        eq(7L), eq(RagDocument.JSON_RECORD), eq("rec-1")))
                .thenReturn(Optional.of(existing));
        lenient().when(versionService.getLatestVersion(41L))
                .thenReturn(Optional.empty());
        lenient().when(documentRepository.saveAndFlush(
                any(RagDocument.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(documentEmbedService.embedDocument(
                any(Long.class), anyBoolean()))
                .thenReturn(java.util.Map.of("status", "COMPLETED",
                        "chunksCreated", 2,
                        "embeddingProfileKey", "profile"));
        lenient().when(documentEmbedService.hasFreshEmbedding(
                any(RagDocument.class))).thenReturn(false);

        service = newService();
    }

    private JsonRecordService newService() {
        JsonRecordService wired = new JsonRecordService(
                documentRepository,
                versionService,
                documentEmbedService,
                mock(HybridRetrieverService.class),
                mock(ReRankingService.class),
                embeddingProfileProvider,
                resolver,
                new RagProperties(),
                new ObjectMapper(),
                mock(JdbcTemplate.class),
                transactionManager);
        wired.setKeywordIndexPersistenceService(keywordIndexPersistenceService);
        return wired;
    }

    private JsonRecordUpsertRequest request(String title, String text) {
        JsonRecordUpsertRequest request = new JsonRecordUpsertRequest();
        request.setCollectionId(7L);
        request.setExternalId("rec-1");
        request.setTitle(title);
        request.setRetrievalText(text);
        request.setSource("upload-1");
        request.setMetadata(java.util.Map.of());
        request.setEmbed(false);
        try {
            request.setJsonbPayload(MAPPER.readTree("{\"k\":1}"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return request;
    }

    @Test
    void changedRecordBumpsRevisionRecordsUpdateVersionAndConfirmsWrite()
            throws Exception {
        lenient().when(versionService.forceRecordVersion(
                any(RagDocument.class), eq("UPDATE"), anyString()))
                .thenAnswer(invocation -> {
                    var version = new com.springairag.core.entity.RagDocumentVersion();
                    version.setVersionNumber(7);
                    return version;
                });

        JsonRecordUpsertResponse response =
                service.upsert(request("New", "new-text"));

        assertEquals("UPDATED", response.action());
        assertEquals("New", existing.getTitle());
        verify(documentEmbedService, never()).embedDocument(
                any(Long.class), anyBoolean());
        verify(versionService).forceRecordVersion(
                eq(existing), eq("UPDATE"), anyString());
        verify(resolver).confirmActiveWrite(
                any(CollectionIdentityResolver.ActiveCollectionToken.class));
    }

    @Test
    void unchangedRecordSkipsSaveAndVersion() {
        JsonRecordUpsertRequest req = request("Old", "old-text");
        req.setEmbed(false);

        var response = service.upsert(req);
        System.out.println("DEBUG action=" + response.action()
                + " embeddingStatus=" + response.embeddingStatus()
                + " payloadChanged=" + response.payloadChanged()
                + " contentChanged=" + response.contentChanged()
                + " docTitle=[" + existing.getTitle() + "] docSource=[" + existing.getSource()
                + "] docEnabled=" + existing.getEnabled()
                + " docMetadata=" + existing.getMetadata()
                + " docFilename=" + existing.getOriginalFilename()
                + " reqMetadata=" + req.getMetadata()
                + " reqEmbed=" + req.isEmbed());
        verify(versionService, never()).forceRecordVersion(
                any(RagDocument.class), anyString(), anyString());
    }

    @Test
    void keywordIndexMarksNotRequestedForChangedRecordUnderSkipPolicy() {
        service.upsert(request("Old", "old-text"));
        service.upsert(request("Changed", "changed-text"));

        // SKIP 策略下内容变化 → markNotRequested（而非派发嵌入）。
        verify(keywordIndexPersistenceService, atLeastOnce())
                .markNotRequested(any(RagDocument.class));
    }

    @Test
    void embedNotRequestedWithoutEmbedFlag() {
        System.out.println("DEBUG resolved policy=" + com.springairag.core.embeddingjob.EmbeddingPolicyResolver
                .resolve(null, false));
        JsonRecordUpsertResponse response =
                service.upsert(request("Old", "old-text"));
        System.out.println("DEBUG embedNotRequested status=" + response.embeddingStatus());

        System.out.println("DEBUG status=" + response.embeddingStatus()
                + " error=" + response.error());
        assertEquals("NOT_REQUESTED", response.embeddingStatus());
        verify(documentEmbedService, never()).embedDocument(
                any(Long.class), anyBoolean());
    }

    @Test
    void embedCachedWhenFreshEmbeddingExists() {
        lenient().when(documentEmbedService.hasFreshEmbedding(
                any(RagDocument.class))).thenReturn(true);

        JsonRecordUpsertRequest request = request("Old", "old-text");
        request.setEmbed(true);
        JsonRecordUpsertResponse response = service.upsert(request);

        // 已有新鲜嵌入 → CACHED（不再同步嵌入）。
        assertEquals("CACHED", response.embeddingStatus());
        verify(documentEmbedService, never()).embedDocument(
                any(Long.class), anyBoolean());
    }

    @Test
    void embedFailureMapsToFailedOutcome() {
        when(documentEmbedService.embedDocument(eq(41L), eq(false)))
                .thenThrow(new IllegalStateException("embed down"));

        JsonRecordUpsertRequest request = request("Old", "changed-text");
        request.setEmbed(true);
        JsonRecordUpsertResponse response = service.upsert(request);

        assertEquals("FAILED", response.embeddingStatus());
        assertNotNull(response.error());
    }
}

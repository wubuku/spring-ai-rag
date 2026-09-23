package com.springairag.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.ExternalDocumentUpsertRequest;
import com.springairag.api.enums.EmbeddingPolicy;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.config.EmbeddingProfile;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.entity.RagCollection;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.entity.RagDocumentVersion;
import com.springairag.core.exception.DocumentRevisionConflictException;
import com.springairag.core.exception.RagException;
import com.springairag.core.embeddingjob.EmbeddingDispatchService;
import com.springairag.core.repository.RagCollectionRepository;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.repository.RagEmbeddingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 外部文档服务长尾（Batch 593，JaCoCo 驱动）：请求校验拒绝、
 * ASYNC 派发不可用守卫、可重试并发失败耗尽重试后的收敛异常、
 * 无事务模板的直写路径、SKIP+未变化与禁用文档对关键词索引的
 * 零打扰语义。
 */
class ExternalDocumentServiceTailTest {

    private static final EmbeddingProfile PROFILE =
            new EmbeddingProfile(
                    9L, "profile", "test", "model", "v1",
                    1024, "COSINE", "NONE", true);

    private RagDocumentRepository documentRepository;
    private RagCollectionRepository collectionRepository;
    private RagEmbeddingRepository embeddingRepository;
    private DocumentVersionService documentVersionService;
    private DocumentEmbedService documentEmbedService;
    private EmbeddingProfileProvider embeddingProfileProvider;
    private CollectionIdentityResolver collectionIdentityResolver;
    private JdbcTemplate jdbcTemplate;
    private KeywordIndexPersistenceService keywordIndexPersistenceService;
    private RagCollection collection;
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        documentRepository = mock(RagDocumentRepository.class);
        collectionRepository = mock(RagCollectionRepository.class);
        embeddingRepository = mock(RagEmbeddingRepository.class);
        documentVersionService = mock(DocumentVersionService.class);
        documentEmbedService = mock(DocumentEmbedService.class);
        embeddingProfileProvider = mock(EmbeddingProfileProvider.class);
        collectionIdentityResolver = mock(CollectionIdentityResolver.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        keywordIndexPersistenceService =
                mock(KeywordIndexPersistenceService.class);
        transactionManager = mock(PlatformTransactionManager.class);

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
        lenient().when(embeddingRepository.countFreshChunksByDocumentIdAndProfileId(
                anyLong(), eq(PROFILE.id()))).thenReturn(0L);
        lenient().when(documentVersionService.forceRecordVersion(
                any(RagDocument.class), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    RagDocumentVersion version = new RagDocumentVersion();
                    version.setVersionNumber(3);
                    return version;
                });
        lenient().when(documentRepository.saveAndFlush(any(RagDocument.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private ExternalDocumentService service(
            PlatformTransactionManager manager, EmbeddingDispatchService dispatch) {
        ExternalDocumentService service = new ExternalDocumentService(
                documentRepository,
                collectionRepository,
                embeddingRepository,
                documentVersionService,
                documentEmbedService,
                embeddingProfileProvider,
                collectionIdentityResolver,
                jdbcTemplate,
                manager);
        service.setKeywordIndexPersistenceService(keywordIndexPersistenceService);
        if (dispatch != null) {
            service.setDispatchService(dispatch);
        }
        return service;
    }

    private ExternalDocumentUpsertRequest request(
            String externalId, String revision) {
        ExternalDocumentUpsertRequest request =
                new ExternalDocumentUpsertRequest();
        request.setCollectionKey(collection.getCollectionKey());
        request.setExternalId(externalId);
        request.setSourceRevision(revision);
        request.setTitle("First title");
        request.setContent("First content");
        request.setSource("connector://manual");
        request.setDocumentType("text");
        request.setEmbed(true);
        return request;
    }

    private RagDocument existingDocument() {
        RagDocument document = new RagDocument();
        document.setId(77L);
        document.setCollectionId(10L);
        document.setExternalId("doc-1");
        document.setSourceNamespace("default");
        document.setSourceRevision("rev-1");
        document.setTitle("First title");
        document.setContent("First content");
        document.setSource("connector://manual");
        document.setDocumentType("text");
        document.setEnabled(Boolean.TRUE);
        document.setDocumentRevision(2L);
        document.setContentHash(
                com.springairag.core.util.DigestUtils.sha256("First content"));
        return document;
    }

    @Test
    void upsertRejectsNullRequest() {
        assertThrows(IllegalArgumentException.class,
                () -> service(transactionManager, null).upsert(null));
    }

    @Test
    void upsertNormalizesBlankDocumentTypeWithoutRejection() {
        ExternalDocumentUpsertRequest request = request("doc-1", "rev-1");
        request.setDocumentType("   ");
        when(documentRepository.findByCollectionIdAndExternalId(10L, "doc-1"))
                .thenReturn(Optional.empty());
        when(documentEmbedService.hasFreshEmbedding(any(RagDocument.class)))
                .thenReturn(true);

        service(transactionManager, null).upsert(request);

        // 空白 documentType 不拒绝，落库时归一为默认 text。
        ArgumentCaptor<RagDocument> captor =
                ArgumentCaptor.forClass(RagDocument.class);
        verify(documentRepository).saveAndFlush(captor.capture());
        assertEquals("text", captor.getValue().getDocumentType());
    }

    @Test
    void upsertRejectsAsyncPolicyWhenDispatchUnavailable() {
        ExternalDocumentUpsertRequest request = request("doc-1", "rev-1");
        request.setEmbeddingPolicy(EmbeddingPolicy.ASYNC);

        RagException error = assertThrows(RagException.class,
                () -> service(transactionManager, null).upsert(request));
        assertEquals(
                com.springairag.api.enums.ErrorCode.EMBEDDING_JOBS_DISABLED,
                error.getErrorCodeEnum());
    }

    @Test
    void retryablePersistenceFailuresExhaustAttemptsAndConvergeToConflict() {
        when(documentRepository.findByCollectionIdAndExternalId(10L, "doc-1"))
                .thenReturn(Optional.empty());
        when(documentRepository.saveAndFlush(any(RagDocument.class)))
                .thenThrow(new DataIntegrityViolationException("race"));

        DocumentRevisionConflictException error = assertThrows(
                DocumentRevisionConflictException.class,
                () -> service(transactionManager, null)
                        .upsert(request("doc-1", "rev-1")));

        assertTrue(error.getMessage().contains("did not converge after 3"));
        verify(documentRepository, Mockito.times(3))
                .saveAndFlush(any(RagDocument.class));
    }

    @Test
    void upsertWorksWithoutTransactionTemplate() {
        when(documentRepository.findByCollectionIdAndExternalId(10L, "doc-1"))
                .thenReturn(Optional.empty());
        when(documentEmbedService.hasFreshEmbedding(any(RagDocument.class)))
                .thenReturn(true);

        service(null, null).upsert(request("doc-1", "rev-2"));

        // 无事务模板时 token 为 null，confirm 直接跳过。
        verify(documentRepository).saveAndFlush(any(RagDocument.class));
        verify(keywordIndexPersistenceService).ensureCurrent(any(RagDocument.class));
        verify(collectionIdentityResolver, never()).confirmActiveWrite(any());
    }

    @Test
    void skipUpsertUnchangedEnabledDocumentLeavesIndexAlone() {
        when(documentRepository.findByCollectionIdAndExternalId(10L, "doc-1"))
                .thenReturn(Optional.of(existingDocument()));
        ExternalDocumentUpsertRequest request = request("doc-1", "rev-1");
        request.setEmbeddingPolicy(EmbeddingPolicy.SKIP);
        request.setEmbed(false);

        service(transactionManager, null).upsert(request);

        verify(keywordIndexPersistenceService, never())
                .markNotRequested(any(RagDocument.class));
        verify(keywordIndexPersistenceService, never())
                .ensureCurrent(any(RagDocument.class));
    }

    @Test
    void tombstoneRevisionReplayIsRejected() {
        RagDocument disabled = existingDocument();
        disabled.setEnabled(Boolean.FALSE);
        when(documentRepository.findByCollectionIdAndExternalId(10L, "doc-1"))
                .thenReturn(Optional.of(disabled));

        DocumentRevisionConflictException error = assertThrows(
                DocumentRevisionConflictException.class,
                () -> service(transactionManager, null)
                        .upsert(request("doc-1", "rev-1")));
        assertTrue(error.getMessage().contains("tombstone"));
    }

    @Test
    void legacyDocumentCannotBeClaimedWithExpectedRevision() {
        RagDocument legacy = existingDocument();
        legacy.setSourceRevision(null);
        when(documentRepository.findByCollectionIdAndExternalId(10L, "doc-1"))
                .thenReturn(Optional.of(legacy));
        ExternalDocumentUpsertRequest request = request("doc-1", "rev-9");
        request.setExpectedSourceRevision("rev-1");

        DocumentRevisionConflictException error = assertThrows(
                DocumentRevisionConflictException.class,
                () -> service(transactionManager, null).upsert(request));
        assertTrue(error.getMessage().contains(
                "claimed without expectedSourceRevision"));
    }
}

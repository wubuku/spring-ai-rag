package com.springairag.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.ExternalDocumentDeleteResponse;
import com.springairag.api.dto.ExternalDocumentUpsertRequest;
import com.springairag.api.enums.EmbeddingPolicy;
import com.springairag.core.config.EmbeddingProfile;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.exception.DocumentRevisionConflictException;
import com.springairag.core.embeddingjob.EmbeddingDispatchService;
import com.springairag.core.exception.RagException;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.RagCollection;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.entity.RagDocumentVersion;
import com.springairag.core.repository.RagCollectionRepository;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.repository.RagEmbeddingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
 * ExternalDocumentService 遗留删除与索引协调长尾（Batch 482，
 * JaCoCo 驱动）：sourceDelete 的墓碑/未变/同版本冲突/期望版本漂移/
 * JSON 记录拒绝/缺失文档六分支，以及 coordinateLocalIndex 的
 * SKIP 标记与启用文档 ensureCurrent 分支。
 */
class ExternalDocumentServiceDeleteIndexTailTest {

    private static final EmbeddingProfile PROFILE = new EmbeddingProfile(
            9L, "profile", "test", "model", "v1",
            1024, "COSINE", "NONE", true);

    @Mock
    private RagDocumentRepository documentRepository;
    @Mock
    private RagCollectionRepository collectionRepository;
    @Mock
    private RagEmbeddingRepository embeddingRepository;
    @Mock
    private DocumentVersionService documentVersionService;
    @Mock
    private DocumentEmbedService documentEmbedService;
    @Mock
    private EmbeddingProfileProvider embeddingProfileProvider;
    @Mock
    private CollectionIdentityResolver collectionIdentityResolver;
    @Mock
    private JdbcTemplate jdbcTemplate;
    @Mock
    private JpaTransactionManager transactionManager;

    private KeywordIndexPersistenceService keywordIndexPersistenceService;
    private ExternalDocumentService service;
    private RagCollection collection;

    @BeforeEach
    void setUp() {
        org.mockito.MockitoAnnotations.openMocks(this);
        documentRepository = mock(RagDocumentRepository.class);
        collectionRepository = mock(RagCollectionRepository.class);
        embeddingRepository = mock(RagEmbeddingRepository.class);
        documentVersionService = mock(DocumentVersionService.class);
        documentEmbedService = mock(DocumentEmbedService.class);
        embeddingProfileProvider = mock(EmbeddingProfileProvider.class);
        collectionIdentityResolver = mock(CollectionIdentityResolver.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        transactionManager = mock(JpaTransactionManager.class);
        keywordIndexPersistenceService =
                mock(KeywordIndexPersistenceService.class);

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
        lenient().when(embeddingProfileProvider.getActiveProfile()).thenReturn(PROFILE);
        lenient().when(transactionManager.getTransaction(any()))
                .thenReturn(mock(TransactionStatus.class));
        lenient().when(jdbcTemplate.execute(any(org.springframework.jdbc.core.ConnectionCallback.class)))
                .thenReturn(null);
        lenient().when(collectionRepository.findById(10L))
                .thenReturn(Optional.of(collection));
        lenient().when(embeddingRepository.countFreshChunksByDocumentIdAndProfileId(
                anyLong(), eq(PROFILE.id()))).thenReturn(0L);
        lenient().when(documentVersionService.forceRecordVersion(
                any(RagDocument.class), anyString(), anyString()))
                .thenAnswer(invocation -> version(1));
        lenient().when(documentRepository.saveAndFlush(any(RagDocument.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

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
        service.setKeywordIndexPersistenceService(keywordIndexPersistenceService);
        service.setDispatchService(mock(EmbeddingDispatchService.class));
    }

    private RagDocumentVersion version(int number) {
        RagDocumentVersion value = new RagDocumentVersion();
        value.setVersionNumber(number);
        return value;
    }

    private RagDocument document(Long id, String externalId,
                                 String revision, String content) {
        RagDocument document = new RagDocument();
        document.setId(id);
        document.setCollectionId(collection.getId());
        document.setExternalId(externalId);
        document.setSourceNamespace("default");
        document.setSourceRevision(revision);
        document.setTitle("First title");
        document.setContent(content);
        document.setSource("connector://manual");
        document.setDocumentType("text");
        document.setContentHash(
                com.springairag.core.util.DigestUtils.sha256(content));
        document.setEnabled(true);
        document.setProcessingStatus("COMPLETED");
        return document;
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

    // ── sourceDelete 六分支 ───────────────────────────────────────

    @Test
    void deleteTombstonesLiveDocumentAndNotifiesKeywordIndex() {
        RagDocument live = document(41L, "doc-1", "rev-1", "First content");
        when(documentRepository.findByCollectionIdAndExternalId(10L, "doc-1"))
                .thenReturn(Optional.of(live));

        ExternalDocumentDeleteResponse response = service.sourceDelete(
                collection.getCollectionKey(), "default", "doc-1",
                "rev-2", null);

        assertEquals("DELETED", response.action());
        assertEquals(41L, response.documentId());
        assertNotNull(response.sourceDeletedAt());
        org.mockito.ArgumentCaptor<RagDocument> captor =
                org.mockito.ArgumentCaptor.forClass(RagDocument.class);
        verify(documentRepository).saveAndFlush(captor.capture());
        assertEquals(false, captor.getValue().getEnabled());
        assertEquals("rev-2", captor.getValue().getSourceRevision());
        verify(keywordIndexPersistenceService).markNotRequested(live);
        verify(documentVersionService).forceRecordVersion(
                any(RagDocument.class), eq("DELETE"), anyString());
    }

    @Test
    void deleteTombstonedSameRevisionIsUnchanged() {
        RagDocument tombstoned = document(41L, "doc-1", "rev-1", "First content");
        tombstoned.setEnabled(false);
        tombstoned.setSourceDeletedAt(LocalDateTime.now());
        when(documentRepository.findByCollectionIdAndExternalId(10L, "doc-1"))
                .thenReturn(Optional.of(tombstoned));

        ExternalDocumentDeleteResponse response = service.sourceDelete(
                collection.getCollectionKey(), "default", "doc-1",
                "rev-1", null);

        assertEquals("UNCHANGED", response.action());
        verify(documentRepository, never()).saveAndFlush(any());
        verify(keywordIndexPersistenceService, never())
                .markNotRequested(any());
    }

    @Test
    void deleteLiveDocumentWithSameRevisionConflicts() {
        RagDocument live = document(41L, "doc-1", "rev-1", "First content");
        when(documentRepository.findByCollectionIdAndExternalId(10L, "doc-1"))
                .thenReturn(Optional.of(live));

        DocumentRevisionConflictException error = assertThrows(
                DocumentRevisionConflictException.class,
                () -> service.sourceDelete(
                        collection.getCollectionKey(), "default", "doc-1",
                        "rev-1", null));
        assertTrue(error.getMessage().contains("new sourceRevision"));
    }

    @Test
    void deleteExpectedRevisionMismatchConflicts() {
        RagDocument live = document(41L, "doc-1", "rev-1", "First content");
        when(documentRepository.findByCollectionIdAndExternalId(10L, "doc-1"))
                .thenReturn(Optional.of(live));

        DocumentRevisionConflictException error = assertThrows(
                DocumentRevisionConflictException.class,
                () -> service.sourceDelete(
                        collection.getCollectionKey(), "default", "doc-1",
                        "rev-2", "rev-9"));
        assertTrue(error.getMessage().contains("expectedSourceRevision"));
    }

    @Test
    void deleteJsonRecordIdentityIsRejected() {
        RagDocument jsonRecord = document(41L, "doc-1", "rev-1", "First content");
        jsonRecord.setDocumentType(RagDocument.JSON_RECORD);
        when(documentRepository.findByCollectionIdAndExternalId(10L, "doc-1"))
                .thenReturn(Optional.of(jsonRecord));

        DocumentRevisionConflictException error = assertThrows(
                DocumentRevisionConflictException.class,
                () -> service.sourceDelete(
                        collection.getCollectionKey(), "default", "doc-1",
                        "rev-2", null));
        assertTrue(error.getMessage().contains("JSON record"));
    }

    @Test
    void deleteMissingDocumentIsRejected() {
        when(documentRepository.findByCollectionIdAndExternalId(10L, "doc-1"))
                .thenReturn(Optional.empty());

        RagException error = assertThrows(RagException.class,
                () -> service.sourceDelete(
                        collection.getCollectionKey(), "default", "doc-1",
                        "rev-2", null));
        assertEquals(com.springairag.api.enums.ErrorCode.DOCUMENT_NOT_FOUND,
                error.getErrorCodeEnum());
    }

    // ── coordinateLocalIndex 分支 ─────────────────────────────────

    @Test
    void skipUpsertMarksKeywordIndexNotRequested() {
        when(documentRepository.findByCollectionIdAndExternalId(10L, "doc-1"))
                .thenReturn(Optional.empty());
        ExternalDocumentUpsertRequest request =
                request("doc-1", "rev-1");
        request.setEmbeddingPolicy(EmbeddingPolicy.SKIP);
        request.setEmbed(false);

        service.upsert(request);

        verify(keywordIndexPersistenceService).markNotRequested(any());
        verify(keywordIndexPersistenceService, never()).ensureCurrent(any());
    }

    @Test
    void enabledCreateUpsertEnsuresCurrentKeywordIndex() {
        when(documentRepository.findByCollectionIdAndExternalId(10L, "doc-1"))
                .thenReturn(Optional.empty());
        when(documentEmbedService.hasFreshEmbedding(any(RagDocument.class)))
                .thenReturn(true);
        ExternalDocumentUpsertRequest request =
                request("doc-1", "rev-1");
        request.setEmbeddingPolicy(EmbeddingPolicy.ASYNC);

        service.upsert(request);

        verify(keywordIndexPersistenceService).ensureCurrent(any());
    }
}

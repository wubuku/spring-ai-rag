package com.springairag.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.DocumentDisableRequest;
import com.springairag.api.dto.DocumentLifecycleResponse;
import com.springairag.api.dto.DocumentRestoreRequest;
import com.springairag.api.dto.DocumentUpdateRequest;
import com.springairag.api.dto.JsonRecordUpsertRequest;
import com.springairag.api.enums.EmbeddingAction;
import com.springairag.api.enums.EmbeddingPolicy;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.config.RagProperties;
import com.springairag.core.embeddingjob.EmbeddingDispatchService;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.entity.RagDocumentVersion;
import com.springairag.core.exception.DocumentRevisionConflictException;
import com.springairag.core.exception.RagException;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.repository.RagEmbeddingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DocumentMutationServiceTest {

    private RagDocumentRepository documentRepository;
    private RagEmbeddingRepository embeddingRepository;
    private DocumentVersionService versionService;
    private EmbeddingDispatchService dispatchService;
    private DocumentEmbedService documentEmbedService;
    private DocumentLifecycleService lifecycleService;
    private JdbcTemplate jdbcTemplate;
    private DocumentMutationService service;
    private RagDocument document;

    @BeforeEach
    void setUp() {
        documentRepository = mock(RagDocumentRepository.class);
        embeddingRepository = mock(RagEmbeddingRepository.class);
        versionService = mock(DocumentVersionService.class);
        dispatchService = mock(EmbeddingDispatchService.class);
        documentEmbedService = mock(DocumentEmbedService.class);
        lifecycleService = mock(DocumentLifecycleService.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        PlatformTransactionManager transactionManager =
                mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any()))
                .thenReturn(mock(TransactionStatus.class));

        service = new DocumentMutationService(
                documentRepository,
                embeddingRepository,
                mock(CollectionIdentityResolver.class),
                versionService,
                dispatchService,
                documentEmbedService,
                lifecycleService,
                jdbcTemplate,
                new ObjectMapper(),
                new RagProperties(),
                transactionManager);

        document = localDocument();
        when(documentRepository.findById(41L))
                .thenReturn(Optional.of(document));
        when(documentRepository.saveAndFlush(any(RagDocument.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(versionService.forceRecordVersion(
                any(RagDocument.class), any(), any()))
                .thenAnswer(invocation -> version(5));
        when(lifecycleService.read(any(RagDocument.class)))
                .thenReturn(lifecycle("READY"));
    }

    @Test
    void metadataOnlyPatchIncrementsBusinessRevisionWithoutEmbedding() {
        DocumentUpdateRequest request = new DocumentUpdateRequest();
        request.setExpectedDocumentRevision(4L);
        request.setTitle(" Updated title ");
        request.setMetadata(Map.of("locale", "zh-CN"));

        var response = service.updateLocal(41L, request);

        assertEquals("UPDATED", response.action());
        assertEquals(5L, response.documentRevision());
        assertFalse(response.contentChanged());
        assertTrue(response.metadataChanged());
        assertEquals("NONE", response.embeddingAction());
        assertEquals("Updated title", document.getTitle());
        assertEquals(Map.of("locale", "zh-CN"), document.getMetadata());
        verifyNoInteractions(dispatchService);
    }

    @Test
    void contentPatchInvalidatesOldInputAndQueuesPersistentEmbedding() {
        UUID jobId = UUID.randomUUID();
        when(dispatchService.enqueueInCurrentTransaction(
                any(RagDocument.class), any(Boolean.class),
                any(Boolean.class), any(String.class)))
                .thenReturn(new EmbeddingDispatchService.Result(
                        EmbeddingAction.ASYNC_QUEUED,
                        "QUEUED",
                        "bge-m3-1024",
                        jobId,
                        UUID.randomUUID(),
                        null));
        when(lifecycleService.read(any(RagDocument.class)))
                .thenReturn(lifecycle("INDEXING"));

        DocumentUpdateRequest request = new DocumentUpdateRequest();
        request.setExpectedDocumentRevision(4L);
        request.setContent("  New searchable body\n");
        request.setEmbeddingPolicy(EmbeddingPolicy.ASYNC);

        var response = service.updateLocal(41L, request);

        assertTrue(response.contentChanged());
        assertEquals("ASYNC_QUEUED", response.embeddingAction());
        assertEquals(jobId, response.embeddingJobId());
        assertEquals("PENDING", document.getProcessingStatus());
        assertEquals("  New searchable body\n", document.getContent());
        verify(dispatchService).enqueueInCurrentTransaction(
                document, true, false, "LOCAL_PATCH");
    }

    @Test
    void staleRevisionRejectsMutationBeforePersistence() {
        DocumentUpdateRequest request = new DocumentUpdateRequest();
        request.setExpectedDocumentRevision(3L);
        request.setTitle("Concurrent update");

        assertThrows(DocumentRevisionConflictException.class,
                () -> service.updateLocal(41L, request));

        verify(documentRepository, never()).saveAndFlush(any());
        verifyNoInteractions(dispatchService);
    }

    @Test
    void emptyAndUnknownPatchAreRejectedBeforeDatabaseAccess() {
        DocumentUpdateRequest empty = new DocumentUpdateRequest();
        empty.setExpectedDocumentRevision(4L);
        RagException emptyError = assertThrows(
                RagException.class,
                () -> service.updateLocal(41L, empty));
        assertEquals(ErrorCode.EMPTY_PATCH, emptyError.getErrorCodeEnum());

        DocumentUpdateRequest unknown = new DocumentUpdateRequest();
        unknown.setExpectedDocumentRevision(4L);
        unknown.captureUnknown("unsupported", true);
        RagException unknownError = assertThrows(
                RagException.class,
                () -> service.updateLocal(41L, unknown));
        assertEquals(ErrorCode.UNKNOWN_DOCUMENT_FIELD,
                unknownError.getErrorCodeEnum());

        verify(documentRepository, never()).saveAndFlush(any());
    }

    @Test
    void disableCancelsJobsAndRestoreReusesFreshDerivedIndex() {
        DocumentDisableRequest disable = new DocumentDisableRequest();
        disable.setExpectedDocumentRevision(4L);
        when(lifecycleService.read(any(RagDocument.class)))
                .thenAnswer(invocation -> Boolean.TRUE.equals(
                        invocation.<RagDocument>getArgument(0).getEnabled())
                        ? lifecycle("READY") : lifecycle("DISABLED"));

        var disabled = service.disableLocal(41L, disable);

        assertEquals("DISABLED", disabled.action());
        assertFalse(document.getEnabled());
        assertEquals(5L, document.getDocumentRevision());
        verify(dispatchService).cancelActiveInCurrentTransaction(41L);

        when(documentEmbedService.hasFreshEmbedding(document))
                .thenReturn(true);
        DocumentRestoreRequest restore = new DocumentRestoreRequest();
        restore.setExpectedDocumentRevision(5L);
        restore.setEmbeddingPolicy(EmbeddingPolicy.ASYNC);

        var restored = service.restoreLocal(41L, restore);

        assertEquals("RESTORED", restored.action());
        assertTrue(document.getEnabled());
        assertNull(document.getDisabledAt());
        assertEquals(6L, document.getDocumentRevision());
        verify(dispatchService, never()).enqueueInCurrentTransaction(
                any(), any(Boolean.class), any(Boolean.class), any());
    }

    @Test
    void hardDeleteUsesCasAndRemovesLegacyEmbeddings() {
        when(embeddingRepository.countByDocumentId(41L))
                .thenReturn(3L);

        var deleted = service.hardDeleteLocal(41L, 4L);

        assertEquals(41L, deleted.documentId());
        assertEquals(4L, deleted.documentRevision());
        assertEquals(3L, deleted.embeddingsRemoved());
        verify(dispatchService).cancelActiveInCurrentTransaction(41L);
        verify(embeddingRepository).deleteByDocumentId(41L);
        verify(documentRepository).delete(document);
        verify(documentRepository).flush();
    }

    @Test
    void externallyManagedDocumentCannotUseLocalCrud() {
        document.setExternalId("article-1");
        DocumentDisableRequest request = new DocumentDisableRequest();
        request.setExpectedDocumentRevision(4L);

        RagException error = assertThrows(
                RagException.class,
                () -> service.disableLocal(41L, request));

        assertEquals(ErrorCode.EXTERNAL_DOCUMENT_MANAGED,
                error.getErrorCodeEnum());
        verify(documentRepository, never()).saveAndFlush(any());
    }

    @Test
    void legacyJsonExactReplayRemainsUnchangedWithoutSourceRevision()
            throws Exception {
        document.setCollectionId(10L);
        document.setExternalId("product-1");
        document.setSourceNamespace("default");
        document.setDocumentType(RagDocument.JSON_RECORD);
        document.setJsonbPayload(new ObjectMapper().readTree(
                "{\"sku\":\"product-1\"}"));
        when(documentRepository
                .findByCollectionIdAndSourceNamespaceAndExternalId(
                        10L, "default", "product-1"))
                .thenReturn(Optional.of(document));
        when(versionService.getLatestVersion(41L))
                .thenReturn(Optional.of(version(4)));

        JsonRecordUpsertRequest request = new JsonRecordUpsertRequest();
        request.setCollectionId(10L);
        request.setExternalId("product-1");
        request.setTitle(document.getTitle());
        request.setRetrievalText(document.getContent());
        request.setSource(document.getSource());
        request.setMetadata(document.getMetadata());
        request.setJsonbPayload(document.getJsonbPayload().deepCopy());
        request.setEmbeddingPolicy(EmbeddingPolicy.SKIP);

        var result = service.upsertJsonRecord(
                request, 10L, "catalog", null, null);

        assertEquals("UNCHANGED", result.action());
        assertEquals(4L, result.document().getDocumentRevision());
        assertEquals(4, result.versionNumber());
        verify(documentRepository, never()).saveAndFlush(any());
        verify(versionService, never()).forceRecordVersion(any(), any(), any());
        verifyNoInteractions(dispatchService);
        verifyNoInteractions(jdbcTemplate);
    }

    private static RagDocument localDocument() {
        RagDocument value = new RagDocument();
        value.setId(41L);
        value.setVersion(2L);
        value.setDocumentRevision(4L);
        value.setNextHistoryVersion(5);
        value.setTitle("Current title");
        value.setContent("Current searchable body");
        value.setContentHash(
                com.springairag.core.util.DigestUtils.sha256(
                        value.getContent()));
        value.setSource("manual");
        value.setDocumentType("text");
        value.setMetadata(Map.of("locale", "en-US"));
        value.setEnabled(true);
        value.setProcessingStatus("COMPLETED");
        return value;
    }

    private static RagDocumentVersion version(int number) {
        RagDocumentVersion value = new RagDocumentVersion();
        value.setVersionNumber(number);
        return value;
    }

    private static DocumentLifecycleResponse lifecycle(
            String searchability) {
        return new DocumentLifecycleResponse(
                "DISABLED".equals(searchability) ? "DISABLED" : "ACTIVE",
                searchability,
                searchability,
                searchability,
                "bge-m3-1024",
                null,
                null,
                null,
                !"READY".equals(searchability)
                        && !"DISABLED".equals(searchability));
    }
}

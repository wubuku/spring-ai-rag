package com.springairag.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.DocumentLifecycleResponse;
import com.springairag.api.dto.ExternalDocumentUpsertRequest;
import com.springairag.api.dto.JsonRecordUpsertRequest;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.config.RagProperties;
import com.springairag.core.embeddingjob.EmbeddingDispatchService;
import com.springairag.core.entity.RagCollection;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.entity.RagDocumentVersion;
import com.springairag.core.exception.DocumentRevisionConflictException;
import com.springairag.core.exception.StructuredRecordConflictException;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.repository.RagEmbeddingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * executeExternalInTransaction 重试语义（MAX=3）：可重试并发失败
 * （DataIntegrityViolation/ConcurrencyFailure）重试至成功；耗尽后按
 * 文档种类抛 DocumentRevisionConflict / StructuredRecordConflict 并保
 * 留 cause；不可重试的 RuntimeException 立即透传不重试。
 */
class DocumentMutationExternalRetryTest {

    private static final long COLLECTION_ID = 5L;
    private static final String COLLECTION_KEY = "kb";

    private DocumentMutationService service;
    private RagDocumentRepository documentRepository;
    private CollectionIdentityResolver resolver;
    private DocumentVersionService versionService;
    private EmbeddingDispatchService dispatchService;
    private DocumentLifecycleService lifecycleService;
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        documentRepository = mock(RagDocumentRepository.class);
        RagEmbeddingRepository embeddingRepository =
                mock(RagEmbeddingRepository.class);
        resolver = mock(CollectionIdentityResolver.class);
        versionService = mock(DocumentVersionService.class);
        dispatchService = mock(EmbeddingDispatchService.class);
        DocumentEmbedService documentEmbedService =
                mock(DocumentEmbedService.class);
        lifecycleService = mock(DocumentLifecycleService.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        PlatformTransactionManager transactionManager =
                mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any()))
                .thenReturn(mock(TransactionStatus.class));
        when(versionService.forceRecordVersion(
                any(RagDocument.class), anyString(), anyString()))
                .thenAnswer(invocation -> version(3));
        when(lifecycleService.read(any(RagDocument.class)))
                .thenReturn(lifecycle());

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

        RagCollection collection = new RagCollection();
        collection.setId(COLLECTION_ID);
        collection.setCollectionKey(COLLECTION_KEY);
        authenticateAsDatabaseKey();
        when(resolver.requireActive(isNull(), eq(COLLECTION_KEY)))
                .thenReturn(collection);
    }

    private ExternalDocumentUpsertRequest upsertRequest() {
        ExternalDocumentUpsertRequest request =
                new ExternalDocumentUpsertRequest();
        request.setCollectionKey(COLLECTION_KEY);
        request.setSourceNamespace("crm");
        request.setExternalId("ext-retry");
        request.setSourceRevision("rev-1");
        request.setTitle("Retry Doc");
        request.setContent("Retry content");
        request.setDocumentType("text");
        request.setEmbed(false);
        return request;
    }

    @Test
    void retriesDataIntegrityViolationAndSucceedsOnSecondAttempt() {
        when(documentRepository
                .findByCollectionIdAndSourceNamespaceAndExternalId(
                        COLLECTION_ID, "crm", "ext-retry"))
                .thenThrow(new DataIntegrityViolationException("race"))
                .thenReturn(Optional.empty());
        when(jdbcTemplate.update(anyString(), any(), any())).thenReturn(1);
        when(jdbcTemplate.queryForObject(
                anyString(), eq(Long.class), eq(COLLECTION_ID), eq("crm")))
                .thenReturn(7L);
        java.util.concurrent.atomic.AtomicReference<RagDocument> savedRef =
                new java.util.concurrent.atomic.AtomicReference<>();
        when(documentRepository.saveAndFlush(any(RagDocument.class)))
                .thenAnswer(invocation -> {
                    RagDocument doc = invocation.getArgument(0);
                    doc.setId(99L);
                    savedRef.set(doc);
                    return doc;
                });
        when(documentRepository.findById(99L))
                .thenAnswer(invocation -> Optional.of(savedRef.get()));

        var response = service.upsertExternal(upsertRequest());

        // 第一次尝试并发失败，第二次重试成功完成新建。
        assertEquals("CREATED", response.action());
        assertEquals(99L, response.documentId());
        assertEquals("rev-1", response.sourceRevision());
        assertEquals(3, response.versionNumber());
        verify(documentRepository, times(2))
                .findByCollectionIdAndSourceNamespaceAndExternalId(
                        COLLECTION_ID, "crm", "ext-retry");
    }

    @Test
    void exhaustedRetriesSurfaceDocumentRevisionConflictWithCause() {
        when(documentRepository
                .findByCollectionIdAndSourceNamespaceAndExternalId(
                        COLLECTION_ID, "crm", "ext-retry"))
                .thenThrow(new DataIntegrityViolationException("race"));

        DocumentRevisionConflictException failure =
                assertThrows(DocumentRevisionConflictException.class,
                        () -> service.upsertExternal(upsertRequest()));

        assertInstanceOf(DataIntegrityViolationException.class,
                failure.getCause());
        // 最多 MAX_EXTERNAL_TRANSACTION_ATTEMPTS 次尝试后有界放弃。
        verify(documentRepository, times(3))
                .findByCollectionIdAndSourceNamespaceAndExternalId(
                        COLLECTION_ID, "crm", "ext-retry");
    }

    @Test
    void nonRetryableFailureFailsFastWithoutSecondAttempt() {
        when(documentRepository
                .findByCollectionIdAndSourceNamespaceAndExternalId(
                        COLLECTION_ID, "crm", "ext-retry"))
                .thenThrow(new IllegalStateException("boom"));

        assertThrows(IllegalStateException.class,
                () -> service.upsertExternal(upsertRequest()));

        verify(documentRepository, times(1))
                .findByCollectionIdAndSourceNamespaceAndExternalId(
                        COLLECTION_ID, "crm", "ext-retry");
    }

    @Test
    void jsonRecordExhaustedRetriesSurfaceStructuredRecordConflict() {
        JsonRecordUpsertRequest request = new JsonRecordUpsertRequest();
        request.setSourceNamespace("crm");
        request.setExternalId("json-retry");
        request.setSourceRevision("rev-1");
        request.setTitle("Json Retry");
        request.setRetrievalText("Json retrieval");
        request.setJsonbPayload(new ObjectMapper().createObjectNode()
                .put("state", "open"));

        when(documentRepository
                .findByCollectionIdAndSourceNamespaceAndExternalId(
                        COLLECTION_ID, "crm", "json-retry"))
                .thenThrow(new DataIntegrityViolationException("race"));

        StructuredRecordConflictException failure =
                assertThrows(StructuredRecordConflictException.class,
                        () -> service.upsertJsonRecord(
                                request, COLLECTION_ID, COLLECTION_KEY,
                                null, null));

        assertInstanceOf(DataIntegrityViolationException.class,
                failure.getCause());
        verify(documentRepository, times(3))
                .findByCollectionIdAndSourceNamespaceAndExternalId(
                        COLLECTION_ID, "crm", "json-retry");
    }

    private void authenticateAsDatabaseKey() {
        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", "/documents/external");
        request.setAttribute("authenticatedPrincipalType", "DATABASE_API_KEY");
        request.setAttribute("authenticatedApiKey", "key-42");
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(request));
    }

    private static RagDocumentVersion version(int number) {
        RagDocumentVersion version = new RagDocumentVersion();
        version.setVersionNumber(number);
        return version;
    }

    private static DocumentLifecycleResponse lifecycle() {
        return new DocumentLifecycleResponse(
                "ACTIVE",
                "READY",
                "READY",
                "READY",
                "bge-m3-1024",
                null,
                null,
                null,
                false);
    }
}

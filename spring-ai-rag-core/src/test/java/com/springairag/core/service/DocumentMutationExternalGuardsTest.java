package com.springairag.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.ExternalDocumentUpsertRequest;
import com.springairag.api.dto.JsonRecordUpsertRequest;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 外部文档守卫语义：requireKind 文档种类冲突（text↔json-record 双向）、
 * reconcileMissingExternal 快照边界守卫与墓碑化成功路径、
 * unlinkLocalDocumentsFromCollection 外部托管保护与本地解绑编排。
 */
class DocumentMutationExternalGuardsTest {

    private static final long COLLECTION_ID = 5L;

    private DocumentMutationService service;
    private RagDocumentRepository documentRepository;
    private DocumentVersionService versionService;
    private EmbeddingDispatchService dispatchService;

    @BeforeEach
    void setUp() {
        documentRepository = mock(RagDocumentRepository.class);
        RagEmbeddingRepository embeddingRepository =
                mock(RagEmbeddingRepository.class);
        CollectionIdentityResolver resolver =
                mock(CollectionIdentityResolver.class);
        versionService = mock(DocumentVersionService.class);
        dispatchService = mock(EmbeddingDispatchService.class);
        DocumentEmbedService documentEmbedService =
                mock(DocumentEmbedService.class);
        DocumentLifecycleService lifecycleService =
                mock(DocumentLifecycleService.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        PlatformTransactionManager transactionManager =
                mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any()))
                .thenReturn(mock(TransactionStatus.class));
        when(versionService.forceRecordVersion(
                any(RagDocument.class), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    RagDocumentVersion version = new RagDocumentVersion();
                    version.setVersionNumber(9);
                    return version;
                });
        when(documentRepository.saveAndFlush(any(RagDocument.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        RagCollection collection = new RagCollection();
        collection.setId(COLLECTION_ID);
        collection.setCollectionKey("kb");
        MockHttpServletRequest httpRequest =
                new MockHttpServletRequest("POST", "/documents/external");
        httpRequest.setAttribute("authenticatedPrincipalType",
                "DATABASE_API_KEY");
        httpRequest.setAttribute("authenticatedApiKey", "key-42");
        org.springframework.web.context.request.RequestContextHolder
                .setRequestAttributes(
                        new org.springframework.web.context.request
                                .ServletRequestAttributes(httpRequest));
        when(resolver.requireActive(isNull(), eq("kb")))
                .thenReturn(collection);

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

    // ─── requireKind：种类冲突双向拒绝 ───────────────────────────

    @Test
    void upsertTextOverJsonRecordIdentityIsRejected() {
        RagDocument existing = jsonRecordDocument();
        when(documentRepository
                .findByCollectionIdAndSourceNamespaceAndExternalId(
                        COLLECTION_ID, "crm", "ext-kind"))
                .thenReturn(Optional.of(existing));

        assertThrows(DocumentRevisionConflictException.class,
                () -> service.upsertExternal(request()));
    }

    @Test
    void upsertJsonRecordOverTextIdentityIsRejected() {
        RagDocument existing = textDocument();
        when(documentRepository
                .findByCollectionIdAndSourceNamespaceAndExternalId(
                        COLLECTION_ID, "crm", "json-kind"))
                .thenReturn(Optional.of(existing));

        assertThrows(StructuredRecordConflictException.class,
                () -> service.upsertJsonRecord(
                        jsonRequest(), COLLECTION_ID, "kb", null, null));
    }

    // ─── reconcileMissingExternal：快照边界守卫 ──────────────────

    @Test
    void reconcileMissingDocumentReturnsFalse() {
        when(documentRepository.findById(41L))
                .thenReturn(Optional.empty());

        assertFalse(service.reconcileMissingExternal(
                41L, UUID.randomUUID(), 20L));
    }

    @Test
    void reconcileLocalDocumentWithoutExternalIdReturnsFalse() {
        RagDocument document = textDocument();
        document.setId(41L);
        document.setExternalId(null);
        when(documentRepository.findById(41L))
                .thenReturn(Optional.of(document));

        assertFalse(service.reconcileMissingExternal(
                41L, UUID.randomUUID(), 20L));
    }

    @Test
    void reconcileDocumentWithoutSourceRevisionReturnsFalse() {
        RagDocument document = textDocument();
        document.setId(41L);
        document.setSourceRevision(null);
        when(documentRepository.findById(41L))
                .thenReturn(Optional.of(document));

        assertFalse(service.reconcileMissingExternal(
                41L, UUID.randomUUID(), 20L));
    }

    @Test
    void reconcileAlreadyDisabledDocumentReturnsFalse() {
        RagDocument document = textDocument();
        document.setId(41L);
        document.setEnabled(Boolean.FALSE);
        when(documentRepository.findById(41L))
                .thenReturn(Optional.of(document));

        assertFalse(service.reconcileMissingExternal(
                41L, UUID.randomUUID(), 20L));
    }

    @Test
    void reconcileDocumentNewerThanSnapshotReturnsFalse() {
        RagDocument document = textDocument();
        document.setId(41L);
        document.setSourceMutationSequence(21L);
        when(documentRepository.findById(41L))
                .thenReturn(Optional.of(document));

        // 快照起点 20，文档 mutation sequence 21 属于快照后新变更。
        assertFalse(service.reconcileMissingExternal(
                41L, UUID.randomUUID(), 20L));
    }

    @Test
    void reconcileEligibleDocumentTombstonesWithReconciliationOrigin() {
        RagDocument document = textDocument();
        document.setId(41L);
        document.setDocumentRevision(3L);
        document.setSourceMutationSequence(10L);
        when(documentRepository.findById(41L))
                .thenReturn(Optional.of(document));
        UUID runId = UUID.randomUUID();

        boolean reconciled = service.reconcileMissingExternal(
                41L, runId, 20L);

        assertTrue(reconciled);
        assertEquals(Boolean.FALSE, document.getEnabled());
        assertEquals("RECONCILIATION", document.getDeletionOrigin());
        assertEquals(runId, document.getReconciliationTombstoneRunId());
        assertEquals(4L, document.getDocumentRevision());
        ArgumentCaptor<RagDocument> saved =
                ArgumentCaptor.forClass(RagDocument.class);
        verify(documentRepository).saveAndFlush(saved.capture());
        assertEquals(41L, saved.getValue().getId());
        verify(versionService).forceRecordVersion(
                saved.capture(), eq("TOMBSTONE"), anyString());
        verify(dispatchService).markNotRequestedInCurrentTransaction(document);
        verify(dispatchService).cancelActiveInCurrentTransaction(41L);
    }

    // ─── unlinkLocalDocumentsFromCollection ─────────────────────

    @Test
    void unlinkRejectsCollectionWithExternalManagedDocuments() {
        RagDocument local = textDocument();
        local.setId(1L);
        local.setExternalId(null);
        RagDocument external = textDocument();
        external.setId(2L);
        external.setExternalId("ext-managed");
        when(documentRepository.findAllByCollectionId(COLLECTION_ID))
                .thenReturn(List.of(local, external));

        assertThrows(DocumentRevisionConflictException.class,
                () -> service.unlinkLocalDocumentsFromCollection(
                        COLLECTION_ID));

        verify(documentRepository, never()).saveAndFlush(any());
    }

    @Test
    void unlinkUnassignsLocalDocumentsAndRecordsMoveVersions() {
        RagDocument first = textDocument();
        first.setId(1L);
        first.setCollectionId(COLLECTION_ID);
        first.setExternalId(null);
        RagDocument second = textDocument();
        second.setId(2L);
        second.setCollectionId(COLLECTION_ID);
        second.setExternalId(null);
        when(documentRepository.findAllByCollectionId(COLLECTION_ID))
                .thenReturn(List.of(first, second));
        when(documentRepository.saveAndFlush(any(RagDocument.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        int unlinked = service.unlinkLocalDocumentsFromCollection(
                COLLECTION_ID);

        assertEquals(2, unlinked);
        assertNull(first.getCollectionId());
        assertNull(second.getCollectionId());
        verify(versionService).forceRecordVersion(
                eq(first), eq("COLLECTION_MOVE"), anyString());
        verify(versionService).forceRecordVersion(
                eq(second), eq("COLLECTION_MOVE"), anyString());
    }

    // ─── fixtures ────────────────────────────────────────────────

    private ExternalDocumentUpsertRequest request() {
        ExternalDocumentUpsertRequest request =
                new ExternalDocumentUpsertRequest();
        request.setCollectionKey("kb");
        request.setSourceNamespace("crm");
        request.setExternalId("ext-kind");
        request.setSourceRevision("rev-1");
        request.setTitle("Kind Doc");
        request.setContent("Kind content");
        request.setDocumentType("text");
        request.setEmbed(false);
        return request;
    }

    private JsonRecordUpsertRequest jsonRequest() {
        JsonRecordUpsertRequest request = new JsonRecordUpsertRequest();
        request.setSourceNamespace("crm");
        request.setExternalId("json-kind");
        request.setSourceRevision("rev-1");
        request.setTitle("Json Kind");
        request.setRetrievalText("Json retrieval");
        request.setJsonbPayload(new ObjectMapper().createObjectNode()
                .put("state", "open"));
        return request;
    }

    private RagDocument textDocument() {
        RagDocument document = new RagDocument();
        document.setCollectionId(COLLECTION_ID);
        document.setSourceNamespace("crm");
        document.setExternalId("ext-1");
        document.setSourceRevision("rev-1");
        document.setDocumentType("text");
        document.setEnabled(Boolean.TRUE);
        return document;
    }

    private RagDocument jsonRecordDocument() {
        RagDocument document = textDocument();
        document.setExternalId("ext-kind");
        document.setDocumentType(RagDocument.JSON_RECORD);
        return document;
    }
}

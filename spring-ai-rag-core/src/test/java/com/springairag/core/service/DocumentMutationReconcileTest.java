package com.springairag.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.embeddingjob.EmbeddingDispatchService;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.repository.RagEmbeddingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 对账墓碑（reconcileMissingExternal）守卫与墓碑写入（Batch 380）：
 * 缺失/非外部/禁用/代际较新的一票否决，以及合格文档的墓碑字段
 * 与派发终止。
 */
class DocumentMutationReconcileTest {

    private static final long DOCUMENT_ID = 41L;

    private RagDocumentRepository documentRepository;
    private DocumentVersionService versionService;
    private EmbeddingDispatchService dispatchService;
    private DocumentMutationService service;
    private RagDocument document;

    @BeforeEach
    void setUp() {
        documentRepository = mock(RagDocumentRepository.class);
        versionService = mock(DocumentVersionService.class);
        dispatchService = mock(EmbeddingDispatchService.class);
        lenient().when(versionService.forceRecordVersion(
                any(RagDocument.class), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    var version = new com.springairag.core.entity.RagDocumentVersion();
                    version.setVersionNumber(7);
                    return version;
                });
        lenient().when(documentRepository.saveAndFlush(any(RagDocument.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(dispatchService.markNotRequestedInCurrentTransaction(
                any(RagDocument.class))).thenReturn(null);

        service = new DocumentMutationService(
                documentRepository,
                mock(RagEmbeddingRepository.class),
                mock(CollectionIdentityResolver.class),
                versionService,
                dispatchService,
                mock(DocumentEmbedService.class),
                mock(DocumentLifecycleService.class),
                mock(JdbcTemplate.class),
                new ObjectMapper(),
                new RagProperties(),
                mock(PlatformTransactionManager.class));

        document = externalDocument(null);
    }

    private RagDocument externalDocument(Long mutationSequence) {
        RagDocument value = new RagDocument();
        value.setId(DOCUMENT_ID);
        value.setTitle("External doc");
        value.setContent("content");
        value.setExternalId("ext-1");
        value.setSourceRevision("rev-1");
        value.setEnabled(Boolean.TRUE);
        value.setDocumentType("text");
        value.setCollectionId(7L);
        value.setSourceMutationSequence(mutationSequence);
        return value;
    }

    private void stubFound(RagDocument value) {
        when(documentRepository.findById(DOCUMENT_ID))
                .thenReturn(java.util.Optional.of(value));
    }

    @Test
    void reconcileReturnsFalseWhenDocumentMissing() {
        when(documentRepository.findById(DOCUMENT_ID))
                .thenReturn(java.util.Optional.empty());

        assertFalse(service.reconcileMissingExternal(
                DOCUMENT_ID, UUID.randomUUID(), 0L));
    }

    @Test
    void reconcileRejectsDocumentsFailingEligibilityChecks() {
        // 非外部文档（无 externalId）。
        RagDocument noExternal = externalDocument(null);
        noExternal.setExternalId(null);
        stubFound(noExternal);
        assertFalse(service.reconcileMissingExternal(
                DOCUMENT_ID, UUID.randomUUID(), 0L));

        // 无 sourceRevision。
        RagDocument noRevision = externalDocument(null);
        noRevision.setSourceRevision(null);
        stubFound(noRevision);
        assertFalse(service.reconcileMissingExternal(
                DOCUMENT_ID, UUID.randomUUID(), 0L));

        // 已禁用。
        RagDocument disabled = externalDocument(null);
        disabled.setEnabled(Boolean.FALSE);
        stubFound(disabled);
        assertFalse(service.reconcileMissingExternal(
                DOCUMENT_ID, UUID.randomUUID(), 0L));

        // 快照开始后又有新变更（代际较新）。
        stubFound(externalDocument(99L));
        assertFalse(service.reconcileMissingExternal(
                DOCUMENT_ID, UUID.randomUUID(), 10L));

        verify(documentRepository, org.mockito.Mockito.never())
                .saveAndFlush(any());
    }

    @Test
    void reconcileTombstonesEligibleDocument() {
        UUID runId = UUID.randomUUID();
        stubFound(externalDocument(null));

        assertTrue(service.reconcileMissingExternal(
                DOCUMENT_ID, runId, 10L));

        var saved = org.mockito.ArgumentCaptor.forClass(RagDocument.class);
        verify(documentRepository).saveAndFlush(saved.capture());
        assertEquals(Boolean.FALSE, saved.getValue().getEnabled());
        assertNotNull(saved.getValue().getSourceDeletedAt());
        assertEquals("RECONCILIATION", saved.getValue().getDeletionOrigin());
        assertEquals(runId, saved.getValue().getReconciliationTombstoneRunId());
        verify(versionService).forceRecordVersion(
                eq(saved.getValue()), eq("TOMBSTONE"), anyString());
        verify(dispatchService).markNotRequestedInCurrentTransaction(
                saved.getValue());
        verify(dispatchService).cancelActiveInCurrentTransaction(DOCUMENT_ID);
    }
}

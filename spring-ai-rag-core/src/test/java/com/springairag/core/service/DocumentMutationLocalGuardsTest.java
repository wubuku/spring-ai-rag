package com.springairag.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.DocumentDisableRequest;
import com.springairag.api.dto.DocumentRequest;
import com.springairag.api.dto.DocumentRestoreRequest;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.entity.RagDocumentVersion;
import com.springairag.core.embeddingjob.EmbeddingDispatchService;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.repository.RagEmbeddingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 本地变更守卫残余（Batch 379）：null existingId 委派 createLocal
 * 并深拷贝 jsonb 载荷、重复禁用/重复恢复的 UNCHANGED 短路。
 */
class DocumentMutationLocalGuardsTest {

    private static final long DOCUMENT_ID = 41L;

    private RagDocumentRepository documentRepository;
    private DocumentMutationService service;
    private RagDocument document;

    @BeforeEach
    void setUp() {
        documentRepository = mock(RagDocumentRepository.class);
        CollectionIdentityResolver resolver =
                mock(CollectionIdentityResolver.class);
        DocumentVersionService versionService =
                mock(DocumentVersionService.class);
        EmbeddingDispatchService dispatchService =
                mock(EmbeddingDispatchService.class);
        DocumentLifecycleService lifecycleService =
                mock(DocumentLifecycleService.class);
        PlatformTransactionManager transactionManager =
                mock(PlatformTransactionManager.class);
        lenient().when(transactionManager.getTransaction(any()))
                .thenReturn(mock(TransactionStatus.class));
        lenient().when(versionService.forceRecordVersion(
                any(RagDocument.class), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    RagDocumentVersion version = new RagDocumentVersion();
                    version.setVersionNumber(6);
                    return version;
                });
        lenient().when(dispatchService.enqueueInCurrentTransaction(
                any(RagDocument.class), anyBoolean(), anyBoolean(), anyString()))
                .thenReturn(null);
        lenient().when(dispatchService.markNotRequestedInCurrentTransaction(
                any(RagDocument.class))).thenReturn(null);

        service = new DocumentMutationService(
                documentRepository,
                mock(RagEmbeddingRepository.class),
                resolver,
                versionService,
                dispatchService,
                mock(DocumentEmbedService.class),
                lifecycleService,
                mock(JdbcTemplate.class),
                new ObjectMapper(),
                new RagProperties(),
                transactionManager);

        document = document(true);
        lenient().when(documentRepository.saveAndFlush(any(RagDocument.class)))
                .thenAnswer(invocation -> {
                    RagDocument value = invocation.getArgument(0);
                    if (value.getId() == null) {
                        value.setId(DOCUMENT_ID);
                    }
                    return value;
                });
        lenient().when(documentRepository.findById(DOCUMENT_ID))
                .thenReturn(Optional.of(document));
    }

    private RagDocument document(boolean enabled) {
        RagDocument value = new RagDocument();
        value.setId(DOCUMENT_ID);
        value.setTitle("Current Title");
        value.setContent("Current content");
        value.setContentHash(
                com.springairag.core.util.DigestUtils.sha256("Current content"));
        value.setSource("upload-1");
        value.setDocumentType("text");
        value.setCollectionId(7L);
        value.setDocumentRevision(4L);
        value.setEnabled(enabled);
        return value;
    }

    private DocumentRequest request() {
        DocumentRequest request = new DocumentRequest();
        request.setTitle("New Title");
        request.setContent("New content");
        request.setSource("upload-1");
        request.setDocumentType("text");
        return request;
    }

    @Test
    void nullExistingIdDelegatesToCreateLocalWithDeepCopiedPayload()
            throws Exception {
        var payload = new ObjectMapper().readTree("{\"k\":1}");

        var created = service.upsertLocalImport(
                null, request(), 7L, "file.pdf", payload,
                true, com.springairag.api.enums.EmbeddingPolicy.ASYNC,
                false, "TEST");

        // 深拷贝分支：保存的新文档携带 jsonb 载荷副本。
        var saved = org.mockito.ArgumentCaptor.forClass(RagDocument.class);
        verify(documentRepository).saveAndFlush(saved.capture());
        assertNotNull(saved.getValue().getJsonbPayload());
        assertEquals(payload, saved.getValue().getJsonbPayload());
        assertEquals("CREATED", created.mutation().action());
    }

    @Test
    void disableAlreadyDisabledDocumentReturnsUnchanged() {
        document.setEnabled(false);
        DocumentDisableRequest request = new DocumentDisableRequest();
        request.setExpectedDocumentRevision(4L);

        var response = service.disableLocal(DOCUMENT_ID, request);

        assertEquals("UNCHANGED", response.action());
    }

    @Test
    void restoreAlreadyEnabledDocumentReturnsUnchanged() {
        document.setEnabled(true);
        DocumentRestoreRequest request = new DocumentRestoreRequest();
        request.setExpectedDocumentRevision(4L);

        var response = service.restoreLocal(DOCUMENT_ID, request);

        assertEquals("UNCHANGED", response.action());
    }
}

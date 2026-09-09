package com.springairag.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.DocumentRequest;
import com.springairag.api.enums.DocumentDeduplicationScope;
import com.springairag.api.enums.EmbeddingPolicy;
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

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * upsertLocalImport（本地上传幂等同步）：无变化 UNCHANGED（不落库
 * 不派发）、内容变更 UPDATED 并排队嵌入、强制重嵌绕过新鲜度、集合
 * 变更记录 COLLECTION_MOVE、禁用+内容变更走 SKIP 派发。
 */
class DocumentMutationUpsertLocalImportTest {

    private static final long DOCUMENT_ID = 41L;

    private RagDocumentRepository documentRepository;
    private RagEmbeddingRepository embeddingRepository;
    private DocumentVersionService versionService;
    private EmbeddingDispatchService dispatchService;
    private DocumentEmbedService documentEmbedService;
    private DocumentMutationService service;
    private RagDocument document;

    @BeforeEach
    void setUp() {
        documentRepository = mock(RagDocumentRepository.class);
        RagEmbeddingRepository embeddingRepository =
                mock(RagEmbeddingRepository.class);
        CollectionIdentityResolver resolver =
                mock(CollectionIdentityResolver.class);
        DocumentVersionService versionServiceMock =
                mock(DocumentVersionService.class);
        this.versionService = versionServiceMock;
        EmbeddingDispatchService dispatchServiceMock =
                mock(EmbeddingDispatchService.class);
        this.dispatchService = dispatchServiceMock;
        DocumentEmbedService documentEmbedService =
                mock(DocumentEmbedService.class);
        this.documentEmbedService = documentEmbedService;
        DocumentLifecycleService lifecycleService =
                mock(DocumentLifecycleService.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        PlatformTransactionManager transactionManager =
                mock(PlatformTransactionManager.class);
        lenient().when(transactionManager.getTransaction(any()))
                .thenReturn(mock(TransactionStatus.class));
        service = new DocumentMutationService(
                documentRepository,
                embeddingRepository,
                resolver,
                versionServiceMock,
                dispatchServiceMock,
                documentEmbedService,
                lifecycleService,
                jdbcTemplate,
                new ObjectMapper(),
                new RagProperties(),
                transactionManager);

        document = document("Current content", 7L);
        lenient().when(documentRepository.saveAndFlush(any(RagDocument.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(documentRepository.findById(DOCUMENT_ID))
                .thenReturn(Optional.of(document));
        lenient().when(versionService.getLatestVersion(DOCUMENT_ID))
                .thenReturn(Optional.empty());
        lenient().when(versionService.forceRecordVersion(
                any(RagDocument.class), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    RagDocumentVersion version = new RagDocumentVersion();
                    version.setVersionNumber(6);
                    return version;
                });
        lenient().when(documentEmbedService.hasFreshEmbedding(document))
                .thenReturn(false);
        lenient().when(dispatchService.enqueueInCurrentTransaction(
                any(RagDocument.class), anyBoolean(), anyBoolean(), anyString()))
                .thenReturn(null);
    }

    private RagDocument document(String content, Long collectionId) {
        RagDocument value = new RagDocument();
        value.setId(DOCUMENT_ID);
        value.setTitle("Current Title");
        value.setContent(content);
        value.setContentHash(
                com.springairag.core.util.DigestUtils.sha256(content));
        value.setSource("upload-1");
        value.setDocumentType("text");
        value.setCollectionId(collectionId);
        value.setDocumentRevision(4L);
        value.setEnabled(Boolean.TRUE);
        return value;
    }

    private DocumentRequest request(String content) {
        DocumentRequest request = new DocumentRequest();
        request.setTitle("Current Title");
        request.setContent(content);
        request.setSource("upload-1");
        request.setDocumentType("text");
        return request;
    }

    private void stubDispatchNull() {
        when(dispatchService.enqueueInCurrentTransaction(
                any(RagDocument.class), anyBoolean(), anyBoolean(), anyString()))
                .thenReturn(null);
    }

    @Test
    void unchangedImportReturnsUnchangedWithoutSaveOrDispatch() {
        stubDispatchNull();

        var created = service.upsertLocalImport(
                DOCUMENT_ID, request("Current content"), 7L,
                null, null, null, EmbeddingPolicy.ASYNC, false, "TEST");

        assertEquals("UNCHANGED", created.mutation().action());
        verify(documentRepository, never()).saveAndFlush(any());
        verify(dispatchService, never()).enqueueInCurrentTransaction(
                any(), anyBoolean(), anyBoolean(), anyString());
    }

    @Test
    void changedContentBumpsRevisionAndEnqueuesEmbedding() {
        stubDispatchNull();

        var created = service.upsertLocalImport(
                DOCUMENT_ID, request("New content"), 7L,
                null, null, null, EmbeddingPolicy.ASYNC, false, "TEST");

        assertEquals("UPDATED", created.mutation().action());
        assertEquals(Boolean.TRUE, created.mutation().contentChanged());
        assertEquals(5L, document.getDocumentRevision());
        verify(dispatchService).enqueueInCurrentTransaction(
                any(RagDocument.class), eq(true), eq(false), eq("TEST"));
    }

    @Test
    void forceReembedsEvenWhenContentUnchanged() {
        stubDispatchNull();

        var created = service.upsertLocalImport(
                DOCUMENT_ID, request("Current content"), 7L,
                null, null, null, EmbeddingPolicy.ASYNC, true, "TEST");

        assertEquals("UPDATED", created.mutation().action());
        verify(dispatchService).enqueueInCurrentTransaction(
                any(RagDocument.class), eq(false), eq(true), eq("TEST"));
    }

    @Test
    void collectionMoveRecordsCollectionMoveVersion() {
        stubDispatchNull();

        var created = service.upsertLocalImport(
                DOCUMENT_ID, request("Current content"), 9L,
                null, null, null, EmbeddingPolicy.ASYNC, false, "TEST");

        assertEquals("UPDATED", created.mutation().action());
        verify(versionService).forceRecordVersion(
                any(RagDocument.class), eq("COLLECTION_MOVE"), anyString());
    }

    @Test
    void disabledOverrideWithContentChangeDispatchesSkip() {
        stubDispatchNull();

        var created = service.upsertLocalImport(
                DOCUMENT_ID, request("New content"), 7L,
                null, null, Boolean.FALSE, EmbeddingPolicy.ASYNC, false,
                "TEST");

        // enabled=false 时改用 SKIP 派发（标记不请求嵌入）。
        verify(dispatchService).markNotRequestedInCurrentTransaction(
                any(RagDocument.class));
        verify(dispatchService, never()).enqueueInCurrentTransaction(
                any(), anyBoolean(), anyBoolean(), anyString());
    }
}

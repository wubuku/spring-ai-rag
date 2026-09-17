package com.springairag.core.service;

import com.springairag.api.dto.BatchCreateResponse;
import com.springairag.api.dto.DocumentRequest;
import com.springairag.api.enums.EmbeddingAction;
import com.springairag.api.enums.EmbeddingPolicy;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.embeddingjob.EmbeddingDispatchService;
import com.springairag.core.exception.DocumentNotFoundException;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.repository.RagEmbeddingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BatchDocumentService 遗留批量长尾（Batch 485，JaCoCo 驱动）：
 * 重复内容去重、ASYNC 无事务管理器拒绝、ASYNC 事务内入队、SYNC
 * 嵌入失败标记、SYNC 缓存命中、未新建不强制跳过嵌入、单文档删除
 * 级联与缺失拒绝，以及批量删除的外部管理文档保护。
 */
class BatchDocumentServiceLegacyTailTest {

    private RagDocumentRepository documentRepository;
    private RagEmbeddingRepository embeddingRepository;
    private DocumentEmbedService documentEmbedService;
    private EmbeddingDispatchService dispatchService;

    @BeforeEach
    void setUp() {
        documentRepository = mock(RagDocumentRepository.class);
        embeddingRepository = mock(RagEmbeddingRepository.class);
        documentEmbedService = mock(DocumentEmbedService.class);
        dispatchService = mock(EmbeddingDispatchService.class);
    }

    private BatchDocumentService service() {
        return new BatchDocumentService(
                documentRepository, embeddingRepository, documentEmbedService);
    }

    private BatchDocumentService transactionalService() {
        return new BatchDocumentService(
                documentRepository,
                embeddingRepository,
                documentEmbedService,
                mock(PlatformTransactionManager.class));
    }

    private DocumentRequest request(String title, String content) {
        DocumentRequest req = new DocumentRequest();
        req.setTitle(title);
        req.setContent(content);
        req.setSource("test");
        return req;
    }

    private void stubSaveAssignsId() {
        when(documentRepository.save(any(RagDocument.class)))
                .thenAnswer(invocation -> {
                    RagDocument doc = invocation.getArgument(0);
                    if (doc.getId() == null) {
                        doc.setId(41L);
                    }
                    return doc;
                });
        when(documentRepository.saveAndFlush(any(RagDocument.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void duplicateContentSkipsCreationAndEmbedding() {
        BatchDocumentService service = service();
        RagDocument existing = new RagDocument();
        existing.setId(7L);
        existing.setTitle("Existing");
        when(documentRepository.findByContentHash(any()))
                .thenReturn(List.of(existing));

        BatchCreateResponse response = service.batchCreateDocuments(
                List.of(request("T", "same content")), true, null, false);

        assertEquals(0, response.created());
        assertEquals(1, response.skipped());
        assertEquals("Existing", response.results().getFirst().title());
        verify(documentEmbedService, never())
                .embedDocument(anyLong(), anyBoolean());
    }

    @Test
    void asyncWithoutTransactionManagerFailsItemGracefully() {
        BatchDocumentService service = service();
        // dispatchService 满足 ASYNC 前置门禁；事务管理器缺失在
        // 单条创建时才暴露，并按条目粒度降级为失败结果。
        service.setDispatchService(dispatchService);

        BatchCreateResponse response = service.batchCreateDocuments(
                List.of(request("T", "content")),
                true, null, false, EmbeddingPolicy.ASYNC, null);

        assertEquals(1, response.failed());
        assertNotNull(response.results().getFirst().error());
        assertTrue(response.results().getFirst().error()
                .contains("transaction manager"));
    }

    @Test
    void asyncWithTransactionManagerEnqueuesPerItem() {
        BatchDocumentService service = transactionalService();
        service.setDispatchService(dispatchService);
        stubSaveAssignsId();
        when(documentRepository.findByContentHash(any()))
                .thenReturn(List.of());
        when(dispatchService.enqueueInCurrentTransaction(
                any(RagDocument.class), anyBoolean(), anyBoolean(),
                eq("BATCH_CREATE")))
                .thenReturn(new EmbeddingDispatchService.Result(
                        EmbeddingAction.ASYNC_QUEUED, "QUEUED", "profile",
                        UUID.randomUUID(), UUID.randomUUID(), null));

        BatchCreateResponse response = service.batchCreateDocuments(
                List.of(request("T", "content")),
                true, null, false, EmbeddingPolicy.ASYNC, null);

        assertEquals(1, response.created());
        assertEquals("ASYNC_QUEUED",
                response.results().getFirst().embeddingAction());
    }

    @Test
    void syncEmbeddingFailureMarksDocumentAndReportsError() {
        BatchDocumentService service = service();
        stubSaveAssignsId();
        when(documentRepository.findByContentHash(any()))
                .thenReturn(List.of());
        when(documentEmbedService.embedDocument(41L, false))
                .thenReturn(Map.of("status", "FAILED", "error", "boom"));

        BatchCreateResponse response = service.batchCreateDocuments(
                List.of(request("T", "content")), true, null, false);

        assertEquals(1, response.failed());
        assertTrue(response.results().getFirst().error()
                .contains("Embedding failed"));
        org.mockito.ArgumentCaptor<RagDocument> captor =
                org.mockito.ArgumentCaptor.forClass(RagDocument.class);
        verify(documentRepository, org.mockito.Mockito.atLeastOnce())
                .save(captor.capture());
        assertEquals("EMBEDDING_FAILED",
                captor.getValue().getProcessingStatus());
    }

    @Test
    void syncEmbeddingCachedReturnsCachedAction() {
        BatchDocumentService service = service();
        stubSaveAssignsId();
        when(documentRepository.findByContentHash(any()))
                .thenReturn(List.of());
        when(documentEmbedService.embedDocument(41L, false))
                .thenReturn(Map.of("status", "CACHED"));

        BatchCreateResponse response = service.batchCreateDocuments(
                List.of(request("T", "content")), true, null, false);

        assertEquals(1, response.created());
        assertEquals("SYNC_CACHED",
                response.results().getFirst().embeddingAction());
    }

    @Test
    void duplicateWithSyncAndNoForceSkipsEmbedding() {
        BatchDocumentService service = service();
        RagDocument existing = new RagDocument();
        existing.setId(7L);
        existing.setTitle("Existing");
        when(documentRepository.findByContentHash(any()))
                .thenReturn(List.of(existing));

        BatchCreateResponse response = service.batchCreateDocuments(
                List.of(request("T", "same content")),
                true, null, false, EmbeddingPolicy.SYNC, null);

        // 重复文档且不强制 → 既不嵌入也不标记，直接返回裸结果。
        assertEquals(1, response.skipped());
        assertEquals("Existing",
                response.results().getFirst().title());
        verify(documentEmbedService, never())
                .embedDocument(anyLong(), anyBoolean());
    }

    @Test
    void deleteSingleDocumentCascadesAndRejectsMissing() {
        BatchDocumentService service = service();
        when(documentRepository.existsById(9L)).thenReturn(false);

        assertThrows(DocumentNotFoundException.class,
                () -> service.deleteDocument(9L));

        when(documentRepository.existsById(9L)).thenReturn(true);
        when(embeddingRepository.countByDocumentId(9L)).thenReturn(3L);

        var response = service.deleteDocument(9L);

        assertEquals(3L, response.embeddingsRemoved());
        verify(embeddingRepository).deleteByDocumentId(9L);
        verify(documentRepository).deleteById(9L);
    }

    @Test
    void batchDeleteRejectsExternalManagedDocuments() {
        BatchDocumentService service = service();
        RagDocument external = new RagDocument();
        external.setId(9L);
        external.setExternalId("ext-1");
        when(documentRepository.findAllById(List.of(9L)))
                .thenReturn(List.of(external));

        assertThrows(
                com.springairag.core.exception.DocumentRevisionConflictException.class,
                () -> service.batchDeleteDocuments(List.of(9L)));
    }
}

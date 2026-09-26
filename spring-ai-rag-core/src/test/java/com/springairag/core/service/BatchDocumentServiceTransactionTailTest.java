package com.springairag.core.service;

import com.springairag.api.dto.DocumentRequest;
import com.springairag.api.enums.EmbeddingPolicy;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.repository.RagEmbeddingRepository;
import com.springairag.core.embeddingjob.EmbeddingDispatchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BatchDocumentService ASYNC 事务模板长尾（Batch 667，JaCoCo 驱
 * 动）：ASYNC 策略在事务模板内执行遗留创建链并提交事务、per-doc
 * collectionId 优先于批次级 collectionId。
 */
class BatchDocumentServiceTransactionTailTest {

    private RagDocumentRepository documentRepository;
    private RagEmbeddingRepository embeddingRepository;
    private DocumentEmbedService documentEmbedService;
    private EmbeddingDispatchService dispatchService;
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        documentRepository = mock(RagDocumentRepository.class);
        embeddingRepository = mock(RagEmbeddingRepository.class);
        documentEmbedService = mock(DocumentEmbedService.class);
        dispatchService = mock(EmbeddingDispatchService.class);
        transactionManager = mock(PlatformTransactionManager.class);
        lenient().when(dispatchService.enqueueInCurrentTransaction(
                        any(), anyBoolean(), anyBoolean(), anyString()))
                .thenAnswer(invocation -> new com.springairag.core.embeddingjob
                        .EmbeddingDispatchService.Result(
                        com.springairag.api.enums.EmbeddingAction.ASYNC_QUEUED,
                        "QUEUED", "bge-m3", java.util.UUID.randomUUID(),
                        java.util.UUID.randomUUID(), null));
    }

    private DocumentRequest request(Long collectionId) {
        DocumentRequest req = new DocumentRequest();
        req.setTitle("文档");
        req.setContent("正文内容");
        req.setSource("batch");
        req.setDocumentType("text");
        req.setCollectionId(collectionId);
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
        lenient().when(documentRepository.saveAndFlush(any(RagDocument.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private BatchDocumentService service(boolean async) {
        var service = new BatchDocumentService(
                documentRepository,
                embeddingRepository,
                documentEmbedService,
                transactionManager);
        if (async) {
            service.setDispatchService(dispatchService);
        }
        return service;
    }

    @Test
    void asyncBatchCreationRunsInsideTransactionTemplate() {
        stubSaveAssignsId();
        when(documentRepository.findFirstBySourceOrderByIdAsc(anyString()))
                .thenReturn(java.util.Optional.empty());
        when(documentRepository.findByContentHash(anyString()))
                .thenReturn(List.of());

        var response = service(true).batchCreateDocuments(
                List.of(request(7L)),
                true, 7L, false, EmbeddingPolicy.ASYNC, null);

        assertEquals(1, response.created());
        verify(transactionManager).commit(any());
    }

    @Test
    void perDocCollectionIdTakesPrecedenceOverBatchLevel() {
        stubSaveAssignsId();
        when(documentRepository.findFirstBySourceOrderByIdAsc(anyString()))
                .thenReturn(java.util.Optional.empty());
        when(documentRepository.findByContentHash(anyString()))
                .thenReturn(List.of());

        service(false).batchCreateDocuments(
                List.of(request(7L)), false, 99L, false);

        org.mockito.ArgumentCaptor<RagDocument> captor =
                org.mockito.ArgumentCaptor.forClass(RagDocument.class);
        verify(documentRepository).save(captor.capture());
        assertEquals(7L, captor.getValue().getCollectionId());
    }
}

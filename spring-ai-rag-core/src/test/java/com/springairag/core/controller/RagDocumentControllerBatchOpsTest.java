package com.springairag.core.controller;

import com.springairag.api.dto.BatchCreateResponse;
import com.springairag.api.dto.BatchDeleteResponse;
import com.springairag.api.dto.BatchEmbedResponse;
import com.springairag.api.dto.BatchDeleteItem;
import com.springairag.api.dto.BatchDeleteSummary;
import com.springairag.api.dto.DocumentRequest;
import com.springairag.api.dto.BatchDocumentRequest;
import com.springairag.api.enums.EmbeddingPolicy;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.repository.RagCollectionRepository;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.repository.RagEmbeddingRepository;
import com.springairag.core.service.BatchDocumentService;
import com.springairag.core.service.CollectionIdentityResolver;
import com.springairag.core.service.DocumentEmbedService;
import com.springairag.core.service.DocumentVersionService;
import com.springairag.core.embeddingjob.EmbeddingDispatchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 批量端点守卫与委派（Batch 387）：批量删除空列表守卫与委派、
 * 批量嵌入的 ids 校验/上限、SYNC 结果映射与 ASYNC 逐文档排队。
 */
class RagDocumentControllerBatchOpsTest {

    private RagDocumentRepository documentRepository;
    private DocumentEmbedService documentEmbedService;
    private EmbeddingDispatchService dispatchService;
    private BatchDocumentService batchDocumentService;
    private RagDocumentController controller;

    @BeforeEach
    void setUp() {
        documentRepository = mock(RagDocumentRepository.class);
        documentEmbedService = mock(DocumentEmbedService.class);
        dispatchService = mock(EmbeddingDispatchService.class);
        batchDocumentService = mock(BatchDocumentService.class);
        controller = new RagDocumentController(
                documentRepository,
                mock(RagEmbeddingRepository.class),
                mock(RagCollectionRepository.class),
                documentEmbedService,
                batchDocumentService,
                mock(DocumentVersionService.class),
                mock(EmbeddingProfileProvider.class),
                mock(CollectionIdentityResolver.class),
                null);
        controller.setDispatchService(dispatchService);
    }

    @Test
    void batchDeleteRejectsEmptyIds() {
        assertThrows(IllegalArgumentException.class,
                () -> controller.batchDeleteDocuments(Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> controller.batchDeleteDocuments(Map.of("ids", List.of())));
    }

    @Test
    void batchDeleteDelegatesToService() {
        var canned = new BatchDeleteResponse(
                List.of(new BatchDeleteItem(1L, "DELETED")),
                new BatchDeleteSummary(2, 1, 1));
        when(batchDocumentService.batchDeleteDocuments(List.of(1L, 2L)))
                .thenReturn(canned);

        ResponseEntity<BatchDeleteResponse> response =
                controller.batchDeleteDocuments(Map.of("ids", List.of(1L, 2L)));

        assertEquals(200, response.getStatusCode().value());
        assertSame(canned, response.getBody());
    }

    @Test
    void batchEmbedRejectsInvalidOrOversizedIds() {
        assertThrows(IllegalArgumentException.class,
                () -> controller.batchEmbedDocuments(Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> controller.batchEmbedDocuments(Map.of("ids", List.of())));

        List<Long> oversized = new ArrayList<>();
        for (long i = 1; i <= 51; i++) {
            oversized.add(i);
        }
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> controller.batchEmbedDocuments(Map.of("ids", oversized)));
        assertEquals("Batch embedding limited to 50 documents per request (API rate limit)",
                error.getMessage());
    }

    @Test
    void batchEmbedSyncMapsRawResultsAndSummary() {
        Map<String, Object> raw = Map.of(
                "results", List.of(
                        Map.of("documentId", 1, "status", "COMPLETED",
                                "chunksCreated", 2, "embeddingsStored", 2),
                        Map.of("documentId", 2, "status", "FAILED",
                                "error", "boom", "reason", "provider")),
                "summary", Map.of("total", 2, "success", 1,
                        "cached", 0, "failed", 1, "skipped", 0));
        when(documentEmbedService.batchEmbedDocuments(List.of(1L, 2L)))
                .thenReturn(raw);

        ResponseEntity<BatchEmbedResponse> response = controller.batchEmbedDocuments(
                Map.of("ids", List.of(1L, 2L)));

        assertEquals(200, response.getStatusCode().value());
        assertEquals(2, response.getBody().results().size());
        assertEquals(2, response.getBody().results().get(0).chunksCreated());
        assertEquals("boom", response.getBody().results().get(1).error());
        assertEquals(1, response.getBody().summary().success());
    }

    @Test
    void batchEmbedAsyncQueuesPerDocument() {
        List<RagDocument> documents = List.of(document(1L), document(2L));
        when(documentRepository.findAllById(List.of(1L, 2L)))
                .thenReturn(documents);
        when(dispatchService.enqueueInCurrentTransaction(
                any(RagDocument.class), anyBoolean(), anyBoolean(), anyString()))
                .thenReturn(new EmbeddingDispatchService.Result(
                        com.springairag.api.enums.EmbeddingAction.ASYNC_QUEUED,
                        "QUEUED", "profile", java.util.UUID.randomUUID(),
                        java.util.UUID.randomUUID(), null));

        ResponseEntity<BatchEmbedResponse> response = controller.batchEmbedDocuments(
                Map.of("ids", List.of(1L, 2L)), EmbeddingPolicy.ASYNC);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(2, response.getBody().summary().total());
        assertEquals(2, response.getBody().summary().success());
        assertEquals("QUEUED",
                response.getBody().results().getFirst().status());
    }

    private RagDocument document(Long id) {
        RagDocument value = new RagDocument();
        value.setId(id);
        value.setTitle("Doc " + id);
        value.setEnabled(Boolean.TRUE);
        return value;
    }
}

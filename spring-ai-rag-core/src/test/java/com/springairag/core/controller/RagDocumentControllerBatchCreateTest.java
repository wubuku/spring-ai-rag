package com.springairag.core.controller;

import com.springairag.api.dto.BatchCreateResponse;
import com.springairag.api.dto.DocumentRequest;
import com.springairag.api.dto.BatchDocumentRequest;
import com.springairag.core.config.EmbeddingProfileProvider;
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
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * batchCreateDocuments 委派（Batch 383）：单参重载、批次集合解析
 * 与文档集合作用域归一、审计写入路径。
 */
class RagDocumentControllerBatchCreateTest {

    private BatchDocumentService batchDocumentService;
    private RagDocumentController controller;

    @BeforeEach
    void setUp() {
        batchDocumentService = mock(BatchDocumentService.class);
        controller = new RagDocumentController(
                mock(RagDocumentRepository.class),
                mock(RagEmbeddingRepository.class),
                mock(RagCollectionRepository.class),
                mock(DocumentEmbedService.class),
                batchDocumentService,
                mock(DocumentVersionService.class),
                mock(EmbeddingProfileProvider.class),
                mock(CollectionIdentityResolver.class),
                null);
    }

    @Test
    void batchCreateDelegatesWithResolvedCollectionAndReturnsResponse() {
        DocumentRequest document = new DocumentRequest();
        document.setTitle("Doc A");
        BatchDocumentRequest request = new BatchDocumentRequest();
        request.setDocuments(List.of(document));
        request.setCollectionId(7L);

        BatchCreateResponse canned = new BatchCreateResponse(
                1, 0, 0, List.of());
        when(batchDocumentService.batchCreateDocuments(
                any(List.class), eq(false), eq(7L), eq(false),
                isNull(), isNull())).thenReturn(canned);

        ResponseEntity<BatchCreateResponse> response =
                controller.batchCreateDocuments(request);

        assertEquals(200, response.getStatusCode().value());
        assertSame(canned, response.getBody());
        // 文档集合作用域已按批次默认归一。
        assertEquals(7L, request.getDocuments().getFirst().getCollectionId());
        assertNull(request.getDocuments().getFirst().getCollectionKey());
    }

    @Test
    void batchCreateCapturesDelegatedArgumentsIncludingIdempotencyKey() {
        DocumentRequest document = new DocumentRequest();
        document.setTitle("Doc B");
        BatchDocumentRequest request = new BatchDocumentRequest();
        request.setDocuments(List.of(document));
        request.setCollectionId(8L);
        request.setEmbed(true);

        BatchCreateResponse canned = new BatchCreateResponse(
                1, 0, 0, List.of());
        ArgumentCaptor<List<DocumentRequest>> documents =
                ArgumentCaptor.forClass(List.class);
        when(batchDocumentService.batchCreateDocuments(
                documents.capture(), eq(true), eq(8L), eq(false),
                isNull(), eq("idem-1"))).thenReturn(canned);

        ResponseEntity<BatchCreateResponse> response =
                controller.batchCreateDocuments(request, "idem-1");

        assertSame(canned, response.getBody());
        assertEquals(1, documents.getValue().size());
    }
}
